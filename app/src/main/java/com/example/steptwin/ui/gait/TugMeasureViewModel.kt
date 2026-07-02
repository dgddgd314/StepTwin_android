package com.example.steptwin.ui.gait

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.steptwin.domain.gait.SensorSample
import com.example.steptwin.domain.gait.SensorSampleType
import com.example.steptwin.domain.gait.TugBaseline
import com.example.steptwin.domain.gait.TugMetrics
import com.example.steptwin.domain.gait.TugPhase
import com.example.steptwin.domain.gait.TugWeights
import com.example.steptwin.domain.repository.TugRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sqrt
import javax.inject.Inject

@HiltViewModel
class TugMeasureViewModel @Inject constructor(
    private val repository: TugRepository,
) : ViewModel() {
    private val sampleBuffer = mutableListOf<SensorSample>()
    private val baselineBuffer = mutableListOf<SensorSample>()
    private var capturedBaseline: TugBaseline = TugBaseline.UNKNOWN
    private var recordingJob: Job? = null
    private var stopRequested = false

    // ---- 실시간 그래프/구간 분류 상태 (메인 스레드 전용) ----
    private var accelSum = 0f
    private var accelCount = 0
    private var gyroSum = 0f
    private var gyroCount = 0
    private var lastAccelMag = 0f
    private var lastGyroMag = 0f
    private var accelBaseline = 0f
    private var accelBaselineReady = false
    private var dynAccelEma = 0f
    private var gyroEma = 0f

    private val moveSeries = ArrayDeque<Float>()
    private val gyroSeries = ArrayDeque<Float>()
    private val phaseSeries = ArrayDeque<TugPhase>()

    // ---- TUG 단계 진행 추적 ----
    private var standDone = false
    private var walkDone = false
    private var turnDone = false
    private var stillTicks = 0

    private val _uiState = MutableStateFlow(TugMeasureUiState())
    val uiState: StateFlow<TugMeasureUiState> = _uiState.asStateFlow()

    fun startRecording() {
        if (_uiState.value.status == TugMeasureStatus.Recording ||
            _uiState.value.status == TugMeasureStatus.Countdown
        ) {
            return
        }

        recordingJob?.cancel()
        sampleBuffer.clear()
        baselineBuffer.clear()
        capturedBaseline = TugBaseline.UNKNOWN
        resetSignalState()
        resetSteps()
        stopRequested = false

        recordingJob = viewModelScope.launch {
            // 준비 카운트다운 (앉은 자세로 폰을 몸에 지니고 대기)
            for (v in CountdownFrom downTo 1) {
                _uiState.value = TugMeasureUiState(
                    status = TugMeasureStatus.Countdown,
                    countdownValue = v,
                    message = "준비하세요. 의자에 앉은 채로 시작합니다.",
                )
                delay(CountdownStepMillis)
            }

            // 정지 기준신호 측정(2초): 중력방향·자이로 영점·고정 안정성.
            baselineBuffer.clear()
            _uiState.value = TugMeasureUiState(
                status = TugMeasureStatus.Baseline,
                message = "폰이 흔들리지 않게 2초간 가만히 계세요.",
            )
            delay(BaselineMillis)
            capturedBaseline = computeBaseline()

            // 측정 시작(카운트업). 여기서부터 동작 센서 수집.
            resetSignalState()
            _uiState.value = TugMeasureUiState(
                status = TugMeasureStatus.Recording,
                message = "일어나 3m 걷고 돌아와 앉으세요. 앉으면 자동으로 종료됩니다.",
            )
            val startedAt = SystemClock.elapsedRealtime()

            while (true) {
                val elapsed = SystemClock.elapsedRealtime() - startedAt
                sampleTick()
                val phase = phaseSeries.lastOrNull()
                updateSteps(phase)

                _uiState.update {
                    it.copy(
                        elapsedMillis = elapsed,
                        sampleCount = sampleBuffer.size,
                        accelWave = moveSeries.toList(),
                        gyroWave = gyroSeries.toList(),
                        phaseWave = phaseSeries.toList(),
                        currentPhase = phase,
                        standDone = standDone,
                        walkDone = walkDone,
                        turnDone = turnDone,
                    )
                }

                val autoStop = walkDone && turnDone &&
                    elapsed >= MinDurationMillis &&
                    stillTicks >= AutoStopStillTicks
                if (stopRequested || autoStop || elapsed >= MaxDurationMillis) break
                delay(WaveTickMillis)
            }

            completeRecording()
        }
    }

    /** 사용자가 "완료(앉았어요)"를 눌렀을 때. */
    fun finishRecording() {
        if (_uiState.value.status == TugMeasureStatus.Recording) {
            stopRequested = true
        }
    }

    // ---- 의료진 직접 입력 ----
    fun updateManualTug(text: String) =
        _uiState.update { it.copy(manualTug = text, manualError = null) }

    fun updateManualGait(text: String) =
        _uiState.update { it.copy(manualGait = text, manualError = null) }

    fun updateManualTurn(text: String) =
        _uiState.update { it.copy(manualTurn = text, manualError = null) }

    fun submitManual() {
        val state = _uiState.value
        val tug = state.manualTug.trim().toFloatOrNull()
        if (tug == null || tug <= 0f || tug > 120f) {
            _uiState.update { it.copy(manualError = "TUG 총시간(초)을 올바르게 입력하세요.") }
            return
        }
        val gait = state.manualGait.trim().takeIf { it.isNotEmpty() }?.toFloatOrNull()
        if (state.manualGait.isNotBlank() && gait == null) {
            _uiState.update { it.copy(manualError = "보행속도(m/s)를 숫자로 입력하세요.") }
            return
        }
        val turn = state.manualTurn.trim().takeIf { it.isNotEmpty() }?.toFloatOrNull()
        if (state.manualTurn.isNotBlank() && turn == null) {
            _uiState.update { it.copy(manualError = "180° 회전시간(초)을 숫자로 입력하세요.") }
            return
        }

        recordingJob?.cancel()
        recordingJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    status = TugMeasureStatus.Analyzing,
                    manualError = null,
                    message = "입력한 TUG 값으로 분석하고 있습니다.",
                )
            }
            val result = repository.submitManual(tug, gait, turn)
            _uiState.update {
                it.copy(
                    status = TugMeasureStatus.Complete,
                    weights = result.weights,
                    metrics = result.metrics,
                    manualEntry = true,
                    message = "의료진이 입력한 TUG 값으로 결과를 계산했습니다.",
                    syncMessage = result.syncMessage,
                )
            }
        }
    }

    fun addSensorData(
        type: SensorSampleType,
        timestampNanos: Long,
        x: Float,
        y: Float,
        z: Float,
    ) {
        val sample = SensorSample(type, timestampNanos, x, y, z)
        when (_uiState.value.status) {
            TugMeasureStatus.Baseline -> baselineBuffer += sample // 중력·자이로·가속 모두 수집
            TugMeasureStatus.Recording -> {
                if (type == SensorSampleType.Gravity) return // 동작 분석엔 선형가속+자이로만
                sampleBuffer += sample
                val magnitude = sqrt(x * x + y * y + z * z)
                if (type == SensorSampleType.Gyroscope) {
                    gyroSum += magnitude
                    gyroCount++
                    lastGyroMag = magnitude
                } else {
                    accelSum += magnitude
                    accelCount++
                    lastAccelMag = magnitude
                }
            }
            else -> Unit
        }
    }

    /** 정지 기준신호에서 중력방향·자이로 영점·고정 안정성을 계산한다. */
    private fun computeBaseline(): TugBaseline {
        val gravity = baselineBuffer.filter { it.type == SensorSampleType.Gravity }
        if (gravity.isEmpty()) return TugBaseline.UNKNOWN
        val gyro = baselineBuffer.filter { it.type == SensorSampleType.Gyroscope }
        val accel = baselineBuffer.filter { it.type == SensorSampleType.LinearAcceleration }

        val gx = gravity.map { it.x }.average().toFloat()
        val gy = gravity.map { it.y }.average().toFloat()
        val gz = gravity.map { it.z }.average().toFloat()
        val ox = if (gyro.isEmpty()) 0f else gyro.map { it.x }.average().toFloat()
        val oy = if (gyro.isEmpty()) 0f else gyro.map { it.y }.average().toFloat()
        val oz = if (gyro.isEmpty()) 0f else gyro.map { it.z }.average().toFloat()

        // 정지 중 선형가속/자이로 변동이 작아야 고정이 안정적
        val accelStd = magStdev(accel)
        val gyroStd = magStdev(gyro)
        val stable = accelStd < StableAccelStd && gyroStd < StableGyroStd

        return TugBaseline(gx, gy, gz, ox, oy, oz, stableMount = stable)
    }

    private fun magStdev(samples: List<SensorSample>): Float {
        if (samples.size < 2) return 0f
        val mags = samples.map { sqrt(it.x * it.x + it.y * it.y + it.z * it.z) }
        val mean = mags.average()
        val variance = mags.sumOf { (it - mean) * (it - mean) } / mags.size
        return sqrt(variance).toFloat()
    }

    private fun sampleTick() {
        val accelMag = if (accelCount > 0) accelSum / accelCount else lastAccelMag
        val gyroMag = if (gyroCount > 0) gyroSum / gyroCount else lastGyroMag
        accelSum = 0f; accelCount = 0
        gyroSum = 0f; gyroCount = 0

        if (!accelBaselineReady) {
            accelBaseline = accelMag
            accelBaselineReady = true
        } else {
            accelBaseline = accelBaseline * (1f - BaselineAlpha) + accelMag * BaselineAlpha
        }
        val movement = abs(accelMag - accelBaseline)
        dynAccelEma = dynAccelEma * (1f - SmoothAlpha) + movement * SmoothAlpha
        gyroEma = gyroEma * (1f - SmoothAlpha) + gyroMag * SmoothAlpha

        val phase = when {
            gyroEma > TurnGyroThreshold -> TugPhase.Turn
            dynAccelEma > WalkAccelThreshold -> TugPhase.Walk
            else -> TugPhase.Still
        }

        moveSeries.addLast(movement)
        gyroSeries.addLast(gyroMag)
        phaseSeries.addLast(phase)
        trim(moveSeries); trim(gyroSeries); trim(phaseSeries)
    }

    private fun updateSteps(phase: TugPhase?) {
        if (dynAccelEma > OnsetThreshold) standDone = true
        if (phase == TugPhase.Walk) walkDone = true
        if (phase == TugPhase.Turn && walkDone) turnDone = true
        // 일어선 뒤 다시 정지가 지속되면 = 앉았다고 보고 자동 종료 카운트
        stillTicks = if (phase == TugPhase.Still && standDone) stillTicks + 1 else 0
    }

    private fun <T> trim(deque: ArrayDeque<T>) {
        while (deque.size > WaveWindowSize) deque.removeFirst()
    }

    private fun resetSignalState() {
        accelSum = 0f; accelCount = 0
        gyroSum = 0f; gyroCount = 0
        lastAccelMag = 0f; lastGyroMag = 0f
        accelBaseline = 0f; accelBaselineReady = false
        dynAccelEma = 0f; gyroEma = 0f
        moveSeries.clear(); gyroSeries.clear(); phaseSeries.clear()
    }

    private fun resetSteps() {
        standDone = false; walkDone = false; turnDone = false; stillTicks = 0
    }

    fun reset() {
        recordingJob?.cancel()
        stopRequested = false
        sampleBuffer.clear()
        baselineBuffer.clear()
        capturedBaseline = TugBaseline.UNKNOWN
        resetSignalState()
        resetSteps()
        _uiState.value = TugMeasureUiState()
    }

    private suspend fun completeRecording() {
        val samples = sampleBuffer.toList()
        _uiState.update {
            it.copy(
                status = TugMeasureStatus.Analyzing,
                message = "AI가 TUG 구간을 분석하고 있습니다.",
                currentPhase = null,
            )
        }

        val result = repository.analyzeAndSync(samples, capturedBaseline)
        _uiState.update {
            it.copy(
                status = TugMeasureStatus.Complete,
                weights = result.weights,
                metrics = result.metrics,
                message = if (result.syncedToServer) {
                    "측정 완료. 서버 동기화까지 완료했습니다."
                } else {
                    "측정 완료. 서버가 아직 없어 로컬 결과만 저장했습니다."
                },
                syncMessage = result.syncMessage,
                currentPhase = null,
            )
        }
    }

    companion object {
        const val WaveWindowSize = 160
        const val WaveTickMillis = 60L

        private const val CountdownFrom = 3
        private const val CountdownStepMillis = 800L
        private const val BaselineMillis = 2_000L // 정지 기준신호 수집 창
        private const val StableAccelStd = 0.8f // 정지 중 선형가속 크기 표준편차 상한
        private const val StableGyroStd = 0.3f // 정지 중 자이로 크기 표준편차 상한
        private const val MinDurationMillis = 4_000L // 자동 종료 최소 경과
        private const val MaxDurationMillis = 40_000L // 안전 상한
        private const val AutoStopStillTicks = 25 // 약 1.5초 정지 지속

        private const val BaselineAlpha = 0.03f
        private const val SmoothAlpha = 0.4f
        private const val TurnGyroThreshold = 1.0f
        private const val WalkAccelThreshold = 1.2f
        private const val OnsetThreshold = 0.6f
    }
}

data class TugMeasureUiState(
    val status: TugMeasureStatus = TugMeasureStatus.Idle,
    val countdownValue: Int = 0,
    val elapsedMillis: Long = 0L,
    val sampleCount: Int = 0,
    val weights: TugWeights? = null,
    val metrics: TugMetrics? = null,
    val message: String = "TUG 검사를 시작하세요.",
    val syncMessage: String? = null,
    val accelWave: List<Float> = emptyList(),
    val gyroWave: List<Float> = emptyList(),
    val phaseWave: List<TugPhase> = emptyList(),
    val currentPhase: TugPhase? = null,
    val standDone: Boolean = false,
    val walkDone: Boolean = false,
    val turnDone: Boolean = false,
    val manualTug: String = "",
    val manualGait: String = "",
    val manualTurn: String = "",
    val manualError: String? = null,
    val manualEntry: Boolean = false,
) {
    val isRecording: Boolean = status == TugMeasureStatus.Recording
    /** 센서 리스너를 켜둘 구간(기준신호 + 측정). */
    val isSensing: Boolean = status == TugMeasureStatus.Baseline || status == TugMeasureStatus.Recording
    val elapsedSeconds: Int = (elapsedMillis / 1_000L).toInt()
    val elapsedTenths: String = "%.1f".format(elapsedMillis / 1_000.0)
    val hasWaveform: Boolean = accelWave.isNotEmpty() || gyroWave.isNotEmpty()
}

enum class TugMeasureStatus {
    Idle,
    Countdown,
    Baseline,
    Recording,
    Analyzing,
    Complete,
}
