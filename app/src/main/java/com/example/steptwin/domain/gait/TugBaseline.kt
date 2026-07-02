package com.example.steptwin.domain.gait

import kotlin.math.sqrt

/**
 * 측정 시작 전 2–3초 정지(착석) 구간에서 계산한 기준신호.
 *  - gravity: 중력 방향(down) 벡터 → 회전을 yaw(수직축)/tilt(수평축)로 분해하는 기준
 *  - gyroOffset: 자이로 영점 오차(정지 시 각속도 평균)
 *  - stableMount: 정지 중 신호 분산이 낮아 폰이 잘 고정됐는지 여부
 */
data class TugBaseline(
    val gravityX: Float,
    val gravityY: Float,
    val gravityZ: Float,
    val gyroOffsetX: Float,
    val gyroOffsetY: Float,
    val gyroOffsetZ: Float,
    val stableMount: Boolean,
) {
    val gravityMag: Float get() = sqrt(gravityX * gravityX + gravityY * gravityY + gravityZ * gravityZ)

    /** 중력벡터가 유효(크기 ~9.8)하면 yaw/tilt 분해 가능. */
    val hasGravity: Boolean get() = gravityMag > 3f

    companion object {
        /** 기준신호 없음(중력 미측정) → 계산기는 자이로 크기 기반으로 폴백한다. */
        val UNKNOWN = TugBaseline(0f, 0f, 0f, 0f, 0f, 0f, stableMount = true)
    }
}
