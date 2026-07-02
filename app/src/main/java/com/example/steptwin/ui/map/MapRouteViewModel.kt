package com.example.steptwin.ui.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.steptwin.domain.preview.RoutePreview
import com.example.steptwin.domain.repository.RoutePreviewRepository
import com.example.steptwin.domain.repository.TugRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MapRouteViewModel @Inject constructor(
    private val previewRepository: RoutePreviewRepository,
    private val tugRepository: TugRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MapRouteUiState())
    val uiState: StateFlow<MapRouteUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            val healthy = previewRepository.checkHealth()
            val result = runCatching {
                previewRepository.loadPreview(tugRepository.latestWeights.value)
            }

            _uiState.update { state ->
                result.fold(
                    onSuccess = { preview ->
                        state.copy(
                            isLoading = false,
                            serverHealthy = healthy,
                            preview = preview,
                            error = null,
                        )
                    },
                    onFailure = { throwable ->
                        state.copy(
                            isLoading = false,
                            serverHealthy = healthy,
                            error = throwable.message ?: "경로를 불러오지 못했습니다.",
                        )
                    },
                )
            }
        }
    }
}

data class MapRouteUiState(
    val isLoading: Boolean = false,
    /** null = 아직 확인 전, true/false = health 결과. */
    val serverHealthy: Boolean? = null,
    val preview: RoutePreview? = null,
    val error: String? = null,
)
