package com.example.steptwin.data.remote

import com.example.steptwin.domain.preview.GeoPoint
import com.example.steptwin.domain.preview.PlaceSuggestion
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Query

/**
 * 카카오 로컬 API(키워드 검색)로 장소 이름 → 좌표 지오코딩.
 * base URL: https://dapi.kakao.com/, 인증: "KakaoAK {REST_API_KEY}".
 */
interface KakaoLocalApi {
    @GET("v2/local/search/keyword.json")
    suspend fun searchKeyword(
        @Header("Authorization") authorization: String,
        @Query("query") query: String,
        @Query("size") size: Int = 1,
    ): KakaoLocalResponse
}

data class KakaoLocalResponse(
    val documents: List<KakaoPlaceDto> = emptyList(),
)

data class KakaoPlaceDto(
    val place_name: String? = null,
    val address_name: String? = null,
    val road_address_name: String? = null,
    val x: String? = null, // longitude
    val y: String? = null, // latitude
) {
    fun toPoint(): GeoPoint? {
        val lng = x?.toDoubleOrNull() ?: return null
        val lat = y?.toDoubleOrNull() ?: return null
        return GeoPoint(lat, lng)
    }

    fun toSuggestion(): PlaceSuggestion? {
        val point = toPoint() ?: return null
        val name = place_name ?: return null
        val address = road_address_name?.takeIf { it.isNotBlank() } ?: address_name.orEmpty()
        return PlaceSuggestion(name = name, address = address, point = point)
    }
}
