package com.example.steptwin.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.steptwin.data.favorites.FavoritesStore
import com.example.steptwin.domain.favorites.FavoriteRoute
import com.example.steptwin.domain.gait.TugWeights
import com.example.steptwin.domain.repository.TugRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val repository: TugRepository,
    private val favoritesStore: FavoritesStore,
) : ViewModel() {
    val uiState: StateFlow<ProfileUiState> = combine(
        repository.latestWeights,
        favoritesStore.favorites,
    ) { weights, favorites ->
        ProfileUiState(weights = weights, favorites = favorites)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = ProfileUiState(),
    )

    /** 지도 탭이 이어받아 실행하도록 즐겨찾기를 요청한다. */
    fun openFavorite(favorite: FavoriteRoute) = favoritesStore.requestRoute(favorite)

    fun removeFavorite(id: String) = favoritesStore.remove(id)

    /** 잊혀질 권리: 기기에 저장된 즐겨찾기 + 보행 프로필 전체 삭제. */
    fun clearAllData() {
        favoritesStore.clearAll()
        repository.clearLocal()
    }
}

data class ProfileUiState(
    val weights: TugWeights? = null,
    val favorites: List<FavoriteRoute> = emptyList(),
)
