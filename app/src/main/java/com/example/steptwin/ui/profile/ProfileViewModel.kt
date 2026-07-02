package com.example.steptwin.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.steptwin.domain.gait.TugWeights
import com.example.steptwin.domain.repository.TugRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class ProfileViewModel @Inject constructor(
    repository: TugRepository,
) : ViewModel() {
    val uiState: StateFlow<ProfileUiState> = repository.latestWeights
        .map { ProfileUiState(weights = it) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = ProfileUiState(),
        )
}

data class ProfileUiState(
    val weights: TugWeights? = null,
)
