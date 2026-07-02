package com.example.steptwin.data.favorites

import android.content.Context
import com.example.steptwin.domain.favorites.FavoriteRoute
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 즐겨찾기 경로를 기기 내부(SharedPreferences)에만 저장한다. 서버로 전송하지 않는다.
 * pendingRoute 는 "내 보행정보"에서 고른 즐겨찾기를 지도 탭이 이어받아 실행하기 위한 통로다.
 */
@Singleton
class FavoritesStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences("steptwin_favorites", Context.MODE_PRIVATE)
    private val gson = Gson()

    private val _favorites = MutableStateFlow(load())
    val favorites: StateFlow<List<FavoriteRoute>> = _favorites.asStateFlow()

    private val _pendingRoute = MutableStateFlow<FavoriteRoute?>(null)
    val pendingRoute: StateFlow<FavoriteRoute?> = _pendingRoute.asStateFlow()

    /** 최신이 위로 오도록 추가(중복 id 는 갱신). */
    fun add(favorite: FavoriteRoute) {
        val next = (listOf(favorite) + _favorites.value.filterNot { it.id == favorite.id })
            .take(MAX_FAVORITES)
        _favorites.value = next
        save(next)
    }

    fun remove(id: String) {
        val next = _favorites.value.filterNot { it.id == id }
        _favorites.value = next
        save(next)
    }

    /** 잊혀질 권리: 저장된 즐겨찾기 전체 삭제. */
    fun clearAll() {
        _favorites.value = emptyList()
        prefs.edit().clear().apply()
    }

    fun requestRoute(favorite: FavoriteRoute) {
        _pendingRoute.value = favorite
    }

    fun consumePending() {
        _pendingRoute.value = null
    }

    private fun load(): List<FavoriteRoute> {
        val json = prefs.getString(KEY, null) ?: return emptyList()
        return runCatching {
            val type = object : TypeToken<List<FavoriteRoute>>() {}.type
            gson.fromJson<List<FavoriteRoute>>(json, type) ?: emptyList()
        }.getOrDefault(emptyList())
    }

    private fun save(list: List<FavoriteRoute>) {
        prefs.edit().putString(KEY, gson.toJson(list)).apply()
    }

    private companion object {
        const val KEY = "favorites_json"
        const val MAX_FAVORITES = 20
    }
}
