package com.example.steptwin.domain.favorites

import com.example.steptwin.domain.preview.GeoPoint
import com.example.steptwin.domain.preview.NamedPlace

/** 사용자가 저장한 길찾기 경로(출발/도착). 기기에만 저장된다. */
data class FavoriteRoute(
    val originName: String,
    val originLat: Double,
    val originLng: Double,
    val destName: String,
    val destLat: Double,
    val destLng: Double,
) {
    /** 좌표쌍으로 만든 안정적 식별자(중복 저장 방지). */
    val id: String
        get() = "%.5f,%.5f>%.5f,%.5f".format(originLat, originLng, destLat, destLng)

    val label: String get() = "$originName → $destName"

    fun originPlace(): NamedPlace = NamedPlace(originName, GeoPoint(originLat, originLng))
    fun destPlace(): NamedPlace = NamedPlace(destName, GeoPoint(destLat, destLng))

    companion object {
        fun of(origin: NamedPlace, destination: NamedPlace): FavoriteRoute = FavoriteRoute(
            originName = origin.name,
            originLat = origin.point.latitude,
            originLng = origin.point.longitude,
            destName = destination.name,
            destLat = destination.point.latitude,
            destLng = destination.point.longitude,
        )
    }
}
