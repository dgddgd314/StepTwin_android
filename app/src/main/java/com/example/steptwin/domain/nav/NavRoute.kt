package com.example.steptwin.domain.nav

import com.example.steptwin.domain.preview.GeoPoint
import com.example.steptwin.domain.preview.RoutePreview
import com.example.steptwin.domain.preview.SegmentKind
import com.example.steptwin.domain.preview.TransitInfo
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** 길안내 위치 소스 모드. */
enum class NavMode { Simulated, RealGps }

enum class TurnDirection(val ko: String) { LEFT("왼쪽"), RIGHT("오른쪽") }

/**
 * 진행거리(경로 시작점부터의 누적 m) 기준으로 발화할 안내 큐.
 * triggerDistance 이상 진행하면 1회 speak 하고, 그때부터 banner 를 현재 안내로 표시한다.
 */
data class NavCue(
    val id: String,
    val triggerDistance: Double,
    val speak: String,
    val banner: String,
)

/**
 * RoutePreview 의 세그먼트 geometry 를 하나의 경로로 이어, 진행거리 기반 위치/안내를 제공한다.
 * 위치 좌표는 앱 내부에서만 쓰며 서버로 전송하지 않는다.
 */
class NavPath private constructor(
    private val vertices: List<GeoPoint>,
    private val cum: DoubleArray,
    val totalDistance: Double,
    val cues: List<NavCue>,
) {
    /** 진행거리 d(m) 위치를 폴리라인에서 보간해 반환. */
    fun pointAt(distance: Double): GeoPoint {
        if (vertices.isEmpty()) return GeoPoint(0.0, 0.0)
        val d = distance.coerceIn(0.0, totalDistance)
        var i = 1
        while (i < cum.size && cum[i] < d) i++
        if (i >= cum.size) return vertices.last()
        val segLen = cum[i] - cum[i - 1]
        val t = if (segLen <= 0.0) 0.0 else (d - cum[i - 1]) / segLen
        val a = vertices[i - 1]
        val b = vertices[i]
        return GeoPoint(
            latitude = a.latitude + (b.latitude - a.latitude) * t,
            longitude = a.longitude + (b.longitude - a.longitude) * t,
        )
    }

    /** 실제 위치를 경로에 최근접 투영해 진행거리(m)를 구한다. */
    fun project(location: GeoPoint): Double {
        if (vertices.size < 2) return 0.0
        val lat0 = Math.toRadians(location.latitude)
        val kx = EarthRadius * cos(lat0)
        val ky = EarthRadius
        fun x(p: GeoPoint) = Math.toRadians(p.longitude) * kx
        fun y(p: GeoPoint) = Math.toRadians(p.latitude) * ky
        val px = x(location); val py = y(location)

        var bestDist = Double.MAX_VALUE
        var bestCum = 0.0
        for (i in 1 until vertices.size) {
            val ax = x(vertices[i - 1]); val ay = y(vertices[i - 1])
            val bx = x(vertices[i]); val by = y(vertices[i])
            val dx = bx - ax; val dy = by - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 <= 0.0) 0.0 else (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0.0, 1.0)
            val cx = ax + dx * t; val cy = ay + dy * t
            val dd = (px - cx) * (px - cx) + (py - cy) * (py - cy)
            if (dd < bestDist) {
                bestDist = dd
                bestCum = cum[i - 1] + (cum[i] - cum[i - 1]) * t
            }
        }
        return bestCum
    }

    companion object {
        private const val EarthRadius = 6_371_000.0
        private const val WalkLeadM = 20.0 // 도보 꺾임 예고 거리
        private const val TransitLeadM = 80.0 // 대중교통 승차 예고(더 일찍)
        private const val AlightEarlyFraction = 0.8 // 대중교통 구간 80% 지점서 하차 예고
        private const val TurnAngleDeg = 45.0
        private const val JunctionEpsilonM = 3.0

        fun from(preview: RoutePreview): NavPath? {
            val segs = preview.segments.filter { it.geometry.size >= 2 }
            if (segs.isEmpty()) return null

            val verts = ArrayList<GeoPoint>()
            val segRanges = ArrayList<IntRange>()
            for (seg in segs) {
                val startIdx = verts.size
                for (p in seg.geometry) {
                    if (verts.isNotEmpty() && distMeters(verts.last(), p) < JunctionEpsilonM) continue
                    verts.add(p)
                }
                val endIdx = verts.size - 1
                // 접합점 중복 제거로 정점이 하나도 안 늘 수 있으니 방어
                segRanges.add(startIdx.coerceAtMost(endIdx)..endIdx)
            }
            if (verts.size < 2) return null

            val cum = DoubleArray(verts.size)
            for (i in 1 until verts.size) cum[i] = cum[i - 1] + distMeters(verts[i - 1], verts[i])
            val total = cum.last()

            val cues = ArrayList<NavCue>()
            segs.forEachIndexed { si, seg ->
                val range = segRanges[si]
                when (seg.kind) {
                    SegmentKind.TRANSIT -> {
                        val boardAt = cum[range.first]
                        val alightAt = cum[range.last]
                        val segLen = (alightAt - boardAt).coerceAtLeast(1.0)
                        val line = spokenLine(seg.transit)
                        val board = seg.transit?.boardingStop?.takeIf { it.isNotBlank() } ?: "정류장"
                        val alight = seg.transit?.alightingStop?.takeIf { it.isNotBlank() } ?: "정류장"
                        cues += NavCue(
                            "board_early_$si",
                            (boardAt - TransitLeadM).coerceAtLeast(0.0),
                            "곧 ${board}에서 ${line}, 탑승하세요. 준비하세요.",
                            "$line 승차 · $board",
                        )
                        cues += NavCue(
                            "board_at_$si", boardAt,
                            "지금 ${line}, 탑승하세요.",
                            "$line 승차 · $board",
                        )
                        cues += NavCue(
                            "alight_early_$si", boardAt + segLen * AlightEarlyFraction,
                            "다음 ${alight}에서 내리세요. 내릴 준비를 하세요.",
                            "$alight 하차 준비",
                        )
                        cues += NavCue(
                            "alight_at_$si", (alightAt - 15.0).coerceAtLeast(boardAt),
                            "이번 ${alight}에서 내리세요.",
                            "$alight 하차",
                        )
                    }
                    else -> { // 도보(custom_walk 등): 45° 이상 꺾임
                        for (v in (range.first + 1) until range.last) {
                            val inB = bearing(verts[v - 1], verts[v])
                            val outB = bearing(verts[v], verts[v + 1])
                            val delta = angleDiff(outB, inB)
                            if (abs(delta) >= TurnAngleDeg) {
                                val dir = if (delta > 0) TurnDirection.RIGHT else TurnDirection.LEFT
                                cues += NavCue(
                                    "turn_${si}_$v",
                                    (cum[v] - WalkLeadM).coerceAtLeast(0.0),
                                    "잠시 후 ${dir.ko}으로 도세요.",
                                    "${dir.ko} 방향 전환",
                                )
                            }
                        }
                    }
                }
            }
            cues += NavCue("arrive", (total - 10.0).coerceAtLeast(0.0), "목적지에 도착했습니다.", "도착")
            cues.sortBy { it.triggerDistance }

            return NavPath(verts, cum, total, cues)
        }

        private fun spokenLine(transit: TransitInfo?): String = when {
            transit == null -> "대중교통"
            transit.isBus -> "${transit.lineLabel ?: ""}번 버스"
            transit.isSubway -> transit.lineLabel ?: "지하철"
            else -> transit.lineLabel ?: "대중교통"
        }

        private fun distMeters(a: GeoPoint, b: GeoPoint): Double {
            val dLat = Math.toRadians(b.latitude - a.latitude)
            val dLon = Math.toRadians(b.longitude - a.longitude)
            val la1 = Math.toRadians(a.latitude)
            val la2 = Math.toRadians(b.latitude)
            val h = sin(dLat / 2) * sin(dLat / 2) +
                cos(la1) * cos(la2) * sin(dLon / 2) * sin(dLon / 2)
            return 2 * EarthRadius * asin(min(1.0, sqrt(h)))
        }

        private fun bearing(a: GeoPoint, b: GeoPoint): Double {
            val la1 = Math.toRadians(a.latitude)
            val la2 = Math.toRadians(b.latitude)
            val dLon = Math.toRadians(b.longitude - a.longitude)
            val y = sin(dLon) * cos(la2)
            val x = cos(la1) * sin(la2) - sin(la1) * cos(la2) * cos(dLon)
            return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
        }

        /** out - in 을 [-180,180] 로 정규화. 양수=우회전(시계방향). */
        private fun angleDiff(outB: Double, inB: Double): Double {
            var d = (outB - inB + 540.0) % 360.0 - 180.0
            if (d == -180.0) d = 180.0
            return d
        }
    }
}
