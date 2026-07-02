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
import javax.inject.Inject

@HiltViewModel
class TugMeasureViewModel @Inject constructor(
    private val repository: TugRepository,
) : ViewModel() {
    private val sampleBuffer = mutableListOf<SensorSample>()
    private var recordingJob: Job? = null

    private val _uiState = MutableStateFlow(TugMeasureUiState())
    val uiState: StateFlow<TugMeasureUiState> = _uiState.asStateFlow()

    fun startRecording() {
        if (_uiState.value.status == TugMeasureStatus.Recording) return

        recordingJob?.cancel()
        sampleBuffer.clear()
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
                    )
                }

                if (elapsedMillis >= MeasurementDurationMillis) break
                delay(250)
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

        if (sampleBuffer.size % 20 == 0) {
            _uiState.update { it.copy(sampleCount = sampleBuffer.size) }
        }
    }

    fun reset() {
        recordingJob?.cancel()
        sampleBuffer.clear()
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
    }
}

data class TugMeasureUiState(
    val status: TugMeasureStatus = TugMeasureStatus.Idle,
    val elapsedMillis: Long = 0L,
    val sampleCount: Int = 0,
    val weights: TugWeights? = null,
    val message: String = "TUG 기반 보행 검사를 시작하세요.",
    val syncMessage: String? = null,
) {
    val isRecording: Boolean = status == TugMeasureStatus.Recording
    val elapsedSeconds: Int = (elapsedMillis / 1_000L).toInt()
}

enum class TugMeasureStatus {
    Idle,
    Recording,
    Analyzing,
    Complete,
}
