package com.example.steptwin.ui.route

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.steptwin.domain.gait.TugWeights
import com.example.steptwin.domain.repository.TugRepository
import com.example.steptwin.domain.route.RecommendedRoute
import com.example.steptwin.domain.route.RouteCalculator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class RouteViewModel @Inject constructor(
    repository: TugRepository,
    private val routeCalculator: RouteCalculator,
) : ViewModel() {
    val uiState: StateFlow<RouteUiState> = repository.latestWeights
        .map { latestWeights ->
            val activeWeights = latestWeights ?: TugWeights.neutral()
            RouteUiState(
                route = routeCalculator.recommend(activeWeights),
                latestWeights = latestWeights,
                isUsingDefaultProfile = latestWeights == null,
            )
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = RouteUiState(
                route = routeCalculator.recommend(TugWeights.neutral()),
                latestWeights = null,
                isUsingDefaultProfile = true,
            ),
        )
}

data class RouteUiState(
    val route: RecommendedRoute,
    val latestWeights: TugWeights?,
    val isUsingDefaultProfile: Boolean,
)
