package com.example.steptwin.domain.gait

import kotlin.math.abs
import kotlin.math.sqrt
import javax.inject.Inject

/**
 * TUG 프로토콜(일어서기 → 3m 보행 → 회전 → 복귀 → 앉기) 수행 신호를 오프라인 분석한다.
 * 1) 시간축을 고정 간격으로 나눠 각 구간을 정지/보행/회전으로 분류
 * 2) 구간별 시간·보행속도·회전/일어서기 세기 등 TUG 지표 산출
 * 3) 정규화된 6개 지표를 온디바이스 MLP([TugModel])에 넣어 3대 취약도 추론
 */
class TugCalculator @Inject constructor() {

    fun calculate(samples: List<SensorSample>): TugAnalysis {
        val accel = samples.filter { it.type == SensorSampleType.LinearAcceleration }
        val gyro = samples.filter { it.type == SensorSampleType.Gyroscope }
        if (accel.isEmpty() && gyro.isEmpty()) return TugAnalysis.neutral()

        val seg = segment(accel, gyro) ?: return TugAnalysis.neutral()

        val features = floatArrayOf(
            normalize(seg.metrics.gaitSpeedMps.toDouble(), 0.4, 1.4),   // gaitSpeedN
            normalize(seg.metrics.tugTimeSec.toDouble(), 8.0, 25.0),    // tugTimeN
            normalize(seg.metrics.turnSec.toDouble(), 0.5, 4.0),        // turnSecN
            normalize(seg.turnPeak.toDouble(), 1.0, 5.0),               // turnPeakN
            normalize(seg.metrics.standSec.toDouble(), 0.4, 3.0),       // standSecN
            normalize(seg.standPeak.toDouble(), 1.5, 8.0),              // standPeakN
        )
        val out = TugModel.predict(features)

        return TugAnalysis(
            weights = TugWeights(
                speedWeight = out[0],
                turnWeight = out[1],
                strengthWeight = out[2],
            ),
            metrics = seg.metrics,
        )
    }

    private class Segmentation(
        val metrics: TugMetrics,
        val turnPeak: Float,
        val standPeak: Float,
    )

    private fun segment(
        accel: List<SensorSample>,
        gyro: List<SensorSample>,
    ): Segmentation? {
        val startNs = listOfNotNull(
            accel.firstOrNull()?.timestampNanos,
            gyro.firstOrNull()?.timestampNanos,
        ).minOrNull() ?: return null
        val endNs = listOfNotNull(
            accel.lastOrNull()?.timestampNanos,
            gyro.lastOrNull()?.timestampNanos,
        ).maxOrNull() ?: return null

        val bins = (((endNs - startNs) / DtNs) + 1).toInt().coerceIn(1, 4000)
        val accelMag = binMeans(accel, startNs, bins) { magnitude(it.x, it.y, it.z) }
        val gyroMag = binMeans(gyro, startNs, bins) { magnitude(it.x, it.y, it.z) }

        var baseline = accelMag.firstOrNull() ?: 0f
        var moveEma = 0f
        var gyroEma = 0f
        val dtSec = DtNs / 1_000_000_000f

        var walkBins = 0
        var turnBins = 0
        var onsetBin = -1
        var firstWalkBin = -1
        var turnPeak = 0f
        var standPeak = 0f

        for (b in 0 until bins) {
            baseline = baseline * (1f - BaselineAlpha) + accelMag[b] * BaselineAlpha
            val movement = abs(accelMag[b] - baseline)
            moveEma = moveEma * (1f - SmoothAlpha) + movement * SmoothAlpha
            gyroEma = gyroEma * (1f - SmoothAlpha) + gyroMag[b] * SmoothAlpha

            if (onsetBin < 0 && movement > OnsetThreshold) onsetBin = b
            if (gyroMag[b] > turnPeak) turnPeak = gyroMag[b]

            val phase = when {
                gyroEma > TurnGyroThreshold -> TugPhase.Turn
                moveEma > WalkAccelThreshold -> TugPhase.Walk
                else -> TugPhase.Still
            }
            when (phase) {
                TugPhase.Walk -> {
                    walkBins++
                    if (firstWalkBin < 0) firstWalkBin = b
                }
                TugPhase.Turn -> turnBins++
                TugPhase.Still -> Unit
            }
            // 일어서기 세기: 움직임 시작 후 약 2.5초 내 최대 움직임 가속
            if (onsetBin >= 0 && b <= onsetBin + StandWindowBins && movement > standPeak) {
                standPeak = movement
            }
        }

        val onset = if (onsetBin >= 0) onsetBin else 0
        val tugTimeSec = ((bins - 1 - onset).coerceAtLeast(0)) * dtSec
        val walkSec = walkBins * dtSec
        val turnSec = turnBins * dtSec
        val standSec = if (firstWalkBin > onset) {
            ((firstWalkBin - onset) * dtSec).coerceIn(0.3f, 4.0f)
        } else {
            0.6f
        }
        val gaitSpeed = if (walkSec > 0.5f) (6.0f / walkSec).coerceIn(0.2f, 2.0f) else 0.6f

        val metrics = TugMetrics(
            tugTimeSec = tugTimeSec,
            standSec = standSec,
            walkSec = walkSec,
            turnSec = turnSec,
            gaitSpeedMps = gaitSpeed,
            fallRisk = FallRisk.fromTugTime(tugTimeSec),
        )
        return Segmentation(metrics, turnPeak, standPeak)
    }

    /** 샘플을 고정 간격 bin 으로 나눠 각 bin 의 평균값을 만든다(빈 bin 은 직전 값 유지). */
    private inline fun binMeans(
        samples: List<SensorSample>,
        startNs: Long,
        bins: Int,
        value: (SensorSample) -> Double,
    ): FloatArray {
        val sum = DoubleArray(bins)
        val count = IntArray(bins)
        for (s in samples) {
            val idx = ((s.timestampNanos - startNs) / DtNs).toInt()
            if (idx in 0 until bins) {
                sum[idx] += value(s)
                count[idx]++
            }
        }
        val result = FloatArray(bins)
        var last = 0f
        for (b in 0 until bins) {
            last = if (count[b] > 0) (sum[b] / count[b]).toFloat() else last
            result[b] = last
        }
        return result
    }

    private fun magnitude(x: Float, y: Float, z: Float): Double {
        return sqrt((x * x + y * y + z * z).toDouble())
    }

    private fun normalize(value: Double, min: Double, max: Double): Float {
        return ((value - min) / (max - min)).coerceIn(0.0, 1.0).toFloat()
    }

    private companion object {
        const val DtNs = 50_000_000L // 50ms bin
        const val BaselineAlpha = 0.05f
        const val SmoothAlpha = 0.5f
        const val TurnGyroThreshold = 1.0f
        const val WalkAccelThreshold = 1.2f
        const val OnsetThreshold = 0.6f
        const val StandWindowBins = 50 // 약 2.5초
    }
}
