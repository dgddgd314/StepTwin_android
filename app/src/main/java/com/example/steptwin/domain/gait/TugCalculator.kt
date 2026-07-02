package com.example.steptwin.domain.gait

import kotlin.math.abs
import kotlin.math.sqrt
import javax.inject.Inject

/**
 * TUG 프로토콜(착석→기립→3m 보행→180° 회전→복귀→의자앞 회전→착석) 신호를 오프라인 분석한다.
 * 시간은 임상/동작/반응 3종으로 분리하고, 회전은 180°와 착석전 회전을 분리한다.
 * 서로 다른 조건의 임상 컷오프(13.5초/0.8·1.0/2.45초)는 각각 탐색적으로만 사용한다.
 */
class TugCalculator @Inject constructor() {

    fun calculate(samples: List<SensorSample>): TugAnalysis {
        val accel = samples.filter { it.type == SensorSampleType.LinearAcceleration }
        val gyro = samples.filter { it.type == SensorSampleType.Gyroscope }
        if (accel.isEmpty() && gyro.isEmpty()) return TugAnalysis.neutral()

        val seg = segment(accel, gyro) ?: return TugAnalysis.neutral()

        // 라우팅용 취약도 벡터(온디바이스 MLP). 임상 판정과 분리된 개인화 입력.
        val features = floatArrayOf(
            normalize(seg.metrics.gaitSpeedMps.toDouble(), 0.4, 1.4),   // gaitSpeedN
            normalize(seg.metrics.clinicalTugSec.toDouble(), 8.0, 25.0), // tugTimeN(임상)
            normalize(seg.metrics.turn180Sec.toDouble(), 0.5, 4.0),      // turnSecN(180°)
            normalize(seg.turnPeak.toDouble(), 1.0, 5.0),                // turnPeakN
            normalize(seg.metrics.standSec.toDouble(), 0.4, 3.0),        // standSecN
            normalize(seg.standPeak.toDouble(), 1.5, 8.0),               // standPeakN
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
        val dtSec = DtNs / 1_000_000_000f

        var baseline = accelMag.firstOrNull() ?: 0f
        var moveEma = 0f
        var gyroEma = 0f
        val phases = Array(bins) { TugPhase.Still }
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
            phases[b] = phase
            if (phase == TugPhase.Walk && firstWalkBin < 0) firstWalkBin = b
            if (onsetBin >= 0 && b <= onsetBin + StandWindowBins && movement > standPeak) {
                standPeak = movement
            }
        }

        val onset = if (onsetBin >= 0) onsetBin else 0
        // 마지막 움직임 bin = 착석 완료 지점. 이후의 정지구간(자동종료 대기)은 제외한다.
        var settleBin = onset
        for (b in bins - 1 downTo 0) {
            if (phases[b] != TugPhase.Still) { settleBin = b; break }
        }
        settleBin = settleBin.coerceIn(onset, bins - 1)

        val reactionSec = onset * dtSec                                   // 신호 → 첫 움직임
        val clinicalTugSec = settleBin * dtSec                           // 신호 → 착석
        val movementSec = (settleBin - onset).coerceAtLeast(0) * dtSec    // 첫 움직임 → 착석

        // 보행 총 시간
        var walkBins = 0
        for (b in 0 until bins) if (phases[b] == TugPhase.Walk) walkBins++
        val walkSec = walkBins * dtSec

        // 회전 run 분리: 첫 run = 180° 회전, 마지막 run = 의자앞 회전(착석 전)
        val turnRuns = mutableListOf<Int>()
        var run = 0
        for (b in 0 until bins) {
            if (phases[b] == TugPhase.Turn) {
                run++
            } else if (run > 0) {
                turnRuns += run
                run = 0
            }
        }
        if (run > 0) turnRuns += run
        val turn180Sec = (turnRuns.firstOrNull() ?: 0) * dtSec
        val turnToSitSec = if (turnRuns.size >= 2) turnRuns.last() * dtSec else 0f

        val standSec = if (firstWalkBin > onset) {
            ((firstWalkBin - onset) * dtSec).coerceIn(0.3f, 4.0f)
        } else {
            0.6f
        }
        val gaitSpeed = if (walkSec > 0.5f) (6.0f / walkSec).coerceIn(0.2f, 2.0f) else 0.6f

        val metrics = TugMetrics(
            clinicalTugSec = clinicalTugSec,
            movementSec = movementSec,
            reactionSec = reactionSec,
            standSec = standSec,
            walkSec = walkSec,
            turn180Sec = turn180Sec,
            turnToSitSec = turnToSitSec,
            gaitSpeedMps = gaitSpeed,
            assessment = buildAssessment(clinicalTugSec, gaitSpeed, turn180Sec),
        )
        return Segmentation(metrics, turnPeak, standPeak)
    }

    private fun buildAssessment(
        clinicalSec: Float,
        gaitSpeed: Float,
        turn180Sec: Float,
    ): TugAssessment {
        val band = GaitSpeedBand.of(gaitSpeed)
        val needsFollowUp = clinicalSec >= ClinicalFollowUpSec
        val turnDelay = turn180Sec > TurnDelaySec
        val slow = band == GaitSpeedBand.Slow || band == GaitSpeedBand.VerySlow
        val signals = (if (needsFollowUp) 1 else 0) + (if (slow) 1 else 0) + (if (turnDelay) 1 else 0)
        val complex = signals >= 2

        val tags = buildList {
            if (needsFollowUp) add("이동기능 추가평가 권장")
            when (band) {
                GaitSpeedBand.VerySlow -> add("고도 느린 보행형")
                GaitSpeedBand.Slow -> add("느린 보행형")
                else -> Unit
            }
            if (turnDelay) add("회전 지연형(탐색적)")
            if (complex) add("복합 이동취약 패턴")
        }
        return TugAssessment(needsFollowUp, band, turnDelay, complex, tags)
    }

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

        // 임상 컷오프(각각 다른 조건 → 탐색적 사용)
        const val ClinicalFollowUpSec = 13.5f // Shumway-Cook, 임상(신호기준) 시간에만 적용
        const val TurnDelaySec = 2.45f // 허약 관련 180° 회전 지연(탐색적)
    }
}
