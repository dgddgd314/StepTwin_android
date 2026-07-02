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
import kotlin.math.sqrt
import javax.inject.Inject

@HiltViewModel
class TugMeasureViewModel @Inject constructor(
    private val repository: TugRepository,
) : ViewModel() {
    private val sampleBuffer = mutableListOf<SensorSample>()
    private var recordingJob: Job? = null

    // 실시간 그래프용 롤링 윈도우(최근 크기값). 센서 콜백/타이머 모두 메인 스레드라 별도 동기화 불필요.
    private val accelWindow = ArrayDeque<Float>()
    private val gyroWindow = ArrayDeque<Float>()

    private val _uiState = MutableStateFlow(TugMeasureUiState())
    val uiState: StateFlow<TugMeasureUiState> = _uiState.asStateFlow()

    fun startRecording() {
        if (_uiState.value.status == TugMeasureStatus.Recording) return

        recordingJob?.cancel()
        sampleBuffer.clear()
        accelWindow.clear()
        gyroWindow.clear()
        _uiState.value = TugMeasureUiState(
            status = TugMeasureStatus.Recording,
            message = "측정 중입니다. 의자에서 일어나 3m를 걷고 돌아와 앉아주세요.",
        )

        recordingJob = viewModelScope.launch {
            val startedAtMillis = SystemClock.elapsedRealtime()

            while (true) {
                val elapsedMillis = SystemClock.elapsedRealtime() - startedAtMillis
                _uiState.update {
                    it.copy(
                        elapsedMillis = elapsedMillis.coerceAtMost(MeasurementDurationMillis),
                        sampleCount = sampleBuffer.size,
                        accelWave = accelWindow.toList(),
                        gyroWave = gyroWindow.toList(),
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

        // 실시간 그래프용: 3축 벡터 크기를 롤링 윈도우에 적재. 상태 갱신은 타이머 루프가 담당(과도한 recomposition 방지).
        val magnitude = sqrt(x * x + y * y + z * z)
        val window = if (type == SensorSampleType.Gyroscope) gyroWindow else accelWindow
        window.addLast(magnitude)
        while (window.size > WaveWindowSize) window.removeFirst()
    }

    fun reset() {
        recordingJob?.cancel()
        sampleBuffer.clear()
        accelWindow.clear()
        gyroWindow.clear()
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
            )
        }
    }

    companion object {
        const val MeasurementDurationMillis = 15_000L

        /** 그래프에 표시할 최근 표본 수(가로축 길이). */
        const val WaveWindowSize = 160

        /** 그래프/타이머 갱신 주기(ms). 약 16fps. */
        const val WaveTickMillis = 60L
    }
}

data class TugMeasureUiState(
    val status: TugMeasureStatus = TugMeasureStatus.Idle,
    val elapsedMillis: Long = 0L,
    val sampleCount: Int = 0,
    val weights: TugWeights? = null,
    val message: String = "TUG 기반 보행 검사를 시작하세요.",
    val syncMessage: String? = null,
    /** 실시간 가속도 크기 파형(최근 표본). */
    val accelWave: List<Float> = emptyList(),
    /** 실시간 자이로(회전) 크기 파형(최근 표본). */
    val gyroWave: List<Float> = emptyList(),
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
