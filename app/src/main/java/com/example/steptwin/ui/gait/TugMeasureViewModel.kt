package com.example.steptwin.ui.gait

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.steptwin.domain.gait.SensorSample
import com.example.steptwin.domain.gait.SensorSampleType
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
    private var recordingJob: Job? = null

    // ---- 실시간 그래프/구간 분류용 상태 (모두 메인 스레드에서만 접근) ----
    // 틱 사이에 들어온 센서 표본을 누적했다가 틱마다 평균을 낸다(센서마다 표본율이 달라 시간정렬을 위해).
    private var accelSum = 0f
    private var accelCount = 0
    private var gyroSum = 0f
    private var gyroCount = 0
    private var lastAccelMag = 0f
    private var lastGyroMag = 0f

    // 중력/기기 방향 성분을 제거하기 위한 느린 기준선(선형가속도든 가속도계든 정지=0 으로 보이게).
    private var accelBaseline = 0f
    private var accelBaselineReady = false
    // 구간 분류 안정화를 위한 지수이동평균.
    private var dynAccelEma = 0f
    private var gyroEma = 0f

    // 틱 단위(시간정렬)로 쌓는 그래프 시계열.
    private val moveSeries = ArrayDeque<Float>() // 움직임 세기(기준선 제거 가속도)
    private val gyroSeries = ArrayDeque<Float>() // 회전 세기(자이로 크기)
    private val phaseSeries = ArrayDeque<TugPhase>()

    private val _uiState = MutableStateFlow(TugMeasureUiState())
    val uiState: StateFlow<TugMeasureUiState> = _uiState.asStateFlow()

    fun startRecording() {
        if (_uiState.value.status == TugMeasureStatus.Recording) return

        recordingJob?.cancel()
        sampleBuffer.clear()
        resetSignalState()
        _uiState.value = TugMeasureUiState(
            status = TugMeasureStatus.Recording,
            message = "측정 중입니다. 의자에서 일어나 3m를 걷고 돌아와 앉아주세요.",
        )

        recordingJob = viewModelScope.launch {
            val startedAtMillis = SystemClock.elapsedRealtime()

            while (true) {
                val elapsedMillis = SystemClock.elapsedRealtime() - startedAtMillis
                sampleTick()
                _uiState.update {
                    it.copy(
                        elapsedMillis = elapsedMillis.coerceAtMost(MeasurementDurationMillis),
                        sampleCount = sampleBuffer.size,
                        accelWave = moveSeries.toList(),
                        gyroWave = gyroSeries.toList(),
                        phaseWave = phaseSeries.toList(),
                        currentPhase = phaseSeries.lastOrNull(),
                    )
                }

                if (elapsedMillis >= MeasurementDurationMillis) break
                delay(WaveTickMillis)
            }

            completeRecording()
        }
    }

    fun addSensorData(
        type: SensorSampleType,
        timestampNanos: Long,
        x: Float,
        y: Float,
        z: Float,
    ) {
        if (_uiState.value.status != TugMeasureStatus.Recording) return

        sampleBuffer += SensorSample(
            type = type,
            timestampNanos = timestampNanos,
            x = x,
            y = y,
            z = z,
        )

        // 다음 틱까지 센서 크기를 누적한다.
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

    /** 틱마다 누적값을 평균 내어 움직임 세기/회전 세기/구간을 계산해 시계열에 추가한다. */
    private fun sampleTick() {
        val accelMag = if (accelCount > 0) accelSum / accelCount else lastAccelMag
        val gyroMag = if (gyroCount > 0) gyroSum / gyroCount else lastGyroMag
        accelSum = 0f; accelCount = 0
        gyroSum = 0f; gyroCount = 0

        // 느린 기준선 추적 후 제거 → 정지 상태는 0 근처, 움직임만 남는다.
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
        trim(moveSeries)
        trim(gyroSeries)
        trim(phaseSeries)
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
        moveSeries.clear()
        gyroSeries.clear()
        phaseSeries.clear()
    }

    fun reset() {
        recordingJob?.cancel()
        sampleBuffer.clear()
        resetSignalState()
        _uiState.value = TugMeasureUiState()
    }

    private suspend fun completeRecording() {
        val samples = sampleBuffer.toList()
        _uiState.update {
            it.copy(
                status = TugMeasureStatus.Analyzing,
                elapsedMillis = MeasurementDurationMillis,
                sampleCount = samples.size,
                message = "보행 특성을 계산하고 있습니다.",
                currentPhase = null,
            )
        }

        val result = repository.analyzeAndSync(samples)
        _uiState.update {
            it.copy(
                status = TugMeasureStatus.Complete,
                weights = result.weights,
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
        const val MeasurementDurationMillis = 15_000L

        /** 그래프에 표시할 최근 틱 수(가로축 길이). 60ms * 160 ≈ 9.6초 창. */
        const val WaveWindowSize = 160

        /** 그래프/타이머 갱신 주기(ms). 약 16fps. */
        const val WaveTickMillis = 60L

        // 구간 분류 파라미터 (선형가속도 m/s^2, 자이로 rad/s 기준).
        private const val BaselineAlpha = 0.03f // 기준선 추적 속도(느리게)
        private const val SmoothAlpha = 0.4f // 분류 안정화용 EMA
        private const val TurnGyroThreshold = 1.0f // 이보다 회전 크면 '회전'
        private const val WalkAccelThreshold = 1.2f // 이보다 움직임 크면 '보행'
    }
}

data class TugMeasureUiState(
    val status: TugMeasureStatus = TugMeasureStatus.Idle,
    val elapsedMillis: Long = 0L,
    val sampleCount: Int = 0,
    val weights: TugWeights? = null,
    val message: String = "TUG 기반 보행 검사를 시작하세요.",
    val syncMessage: String? = null,
    /** 실시간 움직임 세기(기준선 제거 가속도) 파형. */
    val accelWave: List<Float> = emptyList(),
    /** 실시간 회전(자이로) 세기 파형. */
    val gyroWave: List<Float> = emptyList(),
    /** 각 표본 시점의 자동 분류 구간(그래프 배경 색 구분용). */
    val phaseWave: List<TugPhase> = emptyList(),
    /** 측정 중 현재 구간. */
    val currentPhase: TugPhase? = null,
) {
    val isRecording: Boolean = status == TugMeasureStatus.Recording
    val elapsedSeconds: Int = (elapsedMillis / 1_000L).toInt()
    val hasWaveform: Boolean = accelWave.isNotEmpty() || gyroWave.isNotEmpty()
}

enum class TugMeasureStatus {
    Idle,
    Recording,
    Analyzing,
    Complete,
}

/** TUG 동작 구간 자동 분류 결과. */
enum class TugPhase {
    Still, // 정지 / 자세 전환(앉기·일어서기)
    Walk, // 보행
    Turn, // 회전
}
