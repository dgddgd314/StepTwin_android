package com.example.steptwin.domain.gait

import kotlin.math.abs
import kotlin.math.sqrt
import javax.inject.Inject

/**
 * TUG 프로토콜(착석→기립→3m 보행→180° 회전→복귀→의자앞 회전→착석) 신호를 오프라인 분석한다.
 *
 * 기준신호([TugBaseline])의 중력벡터 ĝ 로 회전을 yaw(수직축) 성분으로 투영해 방향 무관하게 검출하고,
 * 선형가속도의 ĝ 투영(수직가속)으로 기립/착석 세기를 계산한다. 자이로 영점도 보정한다.
 * 기준신호가 없으면 자이로 크기 기반으로 폴백한다.
 */
class TugCalculator @Inject constructor() {

    fun calculate(
        samples: List<SensorSample>,
        baseline: TugBaseline = TugBaseline.UNKNOWN,
    ): TugAnalysis {
        val accel = samples.filter { it.type == SensorSampleType.LinearAcceleration }
        val gyro = samples.filter { it.type == SensorSampleType.Gyroscope }
        if (accel.isEmpty() && gyro.isEmpty()) return TugAnalysis.neutral()

        val seg = segment(accel, gyro, baseline) ?: return TugAnalysis.neutral()

        // 라우팅용 취약도 벡터(온디바이스 MLP). 임상 판정과 분리된 개인화 입력.
        val features = floatArrayOf(
            normalize(seg.metrics.gaitSpeedMps.toDouble(), 0.4, 1.4),
            normalize(seg.metrics.clinicalTugSec.toDouble(), 8.0, 25.0),
            normalize(seg.metrics.turn180Sec.toDouble(), 0.5, 4.0),
            normalize(seg.turnPeak.toDouble(), 1.0, 5.0),
            normalize(seg.metrics.standSec.toDouble(), 0.4, 3.0),
            normalize(seg.standPeak.toDouble(), 1.5, 8.0),
        )
        val out = TugModel.predict(features)

        return TugAnalysis(
            weights = TugWeights(out[0], out[1], out[2]),
            metrics = seg.metrics,
        )
    }

    /**
     * 의료진이 다른 곳에서 측정한 TUG 값을 직접 입력할 때 사용.
     * 총시간은 필수, 보행속도·180° 회전시간은 선택(없으면 총시간에서 추정).
     */
    fun fromManual(
        clinicalTugSec: Float,
        gaitSpeedMps: Float?,
        turn180Sec: Float?,
    ): TugAnalysis {
        val gait = (gaitSpeedMps ?: (6f / (clinicalTugSec - 4f).coerceAtLeast(3f)))
            .coerceIn(0.2f, 2.0f)
        val turn = (turn180Sec ?: (clinicalTugSec * 0.15f)).coerceIn(0.5f, 5.0f)
        val standSec = 1.0f
        val turnPeakEst = 2.5f
        val standPeakEst = 4.0f

        val features = floatArrayOf(
            normalize(gait.toDouble(), 0.4, 1.4),
            normalize(clinicalTugSec.toDouble(), 8.0, 25.0),
            normalize(turn.toDouble(), 0.5, 4.0),
            normalize(turnPeakEst.toDouble(), 1.0, 5.0),
            normalize(standSec.toDouble(), 0.4, 3.0),
            normalize(standPeakEst.toDouble(), 1.5, 8.0),
        )
        val out = TugModel.predict(features)

        val metrics = TugMetrics(
            clinicalTugSec = clinicalTugSec,
            movementSec = clinicalTugSec,
            reactionSec = 0f,
            standSec = standSec,
            walkSec = (6f / gait).coerceIn(2f, 30f),
            turn180Sec = turn,
            turnToSitSec = 1.0f,
            sitSec = 1.0f,
            gaitSpeedMps = gait,
            unstableMount = false,
            assessment = buildAssessment(clinicalTugSec, gait, turn),
        )
        return TugAnalysis(TugWeights(out[0], out[1], out[2]), metrics)
    }

    private class Segmentation(
        val metrics: TugMetrics,
        val turnPeak: Float,
        val standPeak: Float,
    )

    private fun segment(
        accel: List<SensorSample>,
        gyro: List<SensorSample>,
        baseline: TugBaseline,
    ): Segmentation? {
        val startNs = listOfNotNull(
            accel.firstOrNull()?.timestampNanos,
            gyro.firstOrNull()?.timestampNanos,
        ).minOrNull() ?: return null
        val endNs = listOfNotNull(
            accel.lastOrNull()?.timestampNanos,
            gyro.lastOrNull()?.timestampNanos,
        ).maxOrNull() ?: return null

        // 중력 단위벡터(down) + 자이로 영점
        val useGravity = baseline.hasGravity
        val gmag = if (useGravity) baseline.gravityMag else 1f
        val gx = if (useGravity) baseline.gravityX / gmag else 0f
        val gy = if (useGravity) baseline.gravityY / gmag else 0f
        val gz = if (useGravity) baseline.gravityZ / gmag else 0f
        val ox = baseline.gyroOffsetX
        val oy = baseline.gyroOffsetY
        val oz = baseline.gyroOffsetZ

        val bins = (((endNs - startNs) / DtNs) + 1).toInt().coerceIn(1, 4000)
        val accelMag = binMeans(accel, startNs, bins) { magnitude(it.x, it.y, it.z) }
        // 회전: 중력축(yaw) 투영. 폴백은 자이로 크기.
        val yawArr = binMeans(gyro, startNs, bins) { s ->
            val cx = s.x - ox; val cy = s.y - oy; val cz = s.z - oz
            if (useGravity) abs(cx * gx + cy * gy + cz * gz).toDouble()
            else magnitude(cx, cy, cz)
        }
        // 수직 가속(기립/착석 세기): 선형가속도의 ĝ 투영. 폴백은 가속 크기.
        val vertArr = binMeans(accel, startNs, bins) { s ->
            if (useGravity) abs(s.x * gx + s.y * gy + s.z * gz).toDouble()
            else magnitude(s.x, s.y, s.z)
        }
        val dtSec = DtNs / 1_000_000_000f

        var baselineMag = accelMag.firstOrNull() ?: 0f
        var moveEma = 0f
        var yawEma = 0f
        val phases = Array(bins) { TugPhase.Still }
        var onsetBin = -1
        var firstWalkBin = -1
        var lastWalkBin = -1
        var turnPeak = 0f

        for (b in 0 until bins) {
            baselineMag = baselineMag * (1f - BaselineAlpha) + accelMag[b] * BaselineAlpha
            val movement = abs(accelMag[b] - baselineMag)
            moveEma = moveEma * (1f - SmoothAlpha) + movement * SmoothAlpha
            yawEma = yawEma * (1f - SmoothAlpha) + yawArr[b] * SmoothAlpha

            if (onsetBin < 0 && movement > OnsetThreshold) onsetBin = b
            if (yawArr[b] > turnPeak) turnPeak = yawArr[b]

            val phase = when {
                yawEma > TurnYawThreshold -> TugPhase.Turn
                moveEma > WalkAccelThreshold -> TugPhase.Walk
                else -> TugPhase.Still
            }
            phases[b] = phase
            if (phase == TugPhase.Walk) {
                if (firstWalkBin < 0) firstWalkBin = b
                lastWalkBin = b
            }
        }

        val onset = if (onsetBin >= 0) onsetBin else 0
        var settleBin = onset
        for (b in bins - 1 downTo 0) {
            if (phases[b] != TugPhase.Still) { settleBin = b; break }
        }
        settleBin = settleBin.coerceIn(onset, bins - 1)

        val reactionSec = onset * dtSec
        val clinicalTugSec = settleBin * dtSec
        val movementSec = (settleBin - onset).coerceAtLeast(0) * dtSec

        var walkBins = 0
        for (b in 0 until bins) if (phases[b] == TugPhase.Walk) walkBins++
        val walkSec = walkBins * dtSec

        // 회전 run 분리: 각 run 의 (start,endInclusive)
        val turnRuns = mutableListOf<IntRange>()
        var runStart = -1
        for (b in 0 until bins) {
            if (phases[b] == TugPhase.Turn) {
                if (runStart < 0) runStart = b
            } else if (runStart >= 0) {
                turnRuns += runStart..(b - 1)
                runStart = -1
            }
        }
        if (runStart >= 0) turnRuns += runStart..(bins - 1)
        val turn180Sec = (turnRuns.firstOrNull()?.let { it.last - it.first + 1 } ?: 0) * dtSec
        val turnToSit = if (turnRuns.size >= 2) turnRuns.last() else null
        val turnToSitSec = (turnToSit?.let { it.last - it.first + 1 } ?: 0) * dtSec

        // 기립(sit-to-stand): onset → 첫 보행
        val standEnd = if (firstWalkBin > onset) firstWalkBin else (onset + 1).coerceAtMost(settleBin)
        val standSec = ((standEnd - onset) * dtSec).coerceIn(0.3f, 4.0f)
        val standPeak = maxInRange(vertArr, onset, standEnd)

        // 착석(stand-to-sit): 마지막 보행/의자앞회전 이후 → 착석 완료
        val sitStart = maxOf(lastWalkBin, turnToSit?.last ?: -1, onset)
        val sitSec = if (settleBin > sitStart) ((settleBin - sitStart) * dtSec).coerceIn(0.3f, 4.0f) else 0.6f
        val standPeakOrSit = maxOf(standPeak, maxInRange(vertArr, sitStart, settleBin))

        val gaitSpeed = if (walkSec > 0.5f) (6.0f / walkSec).coerceIn(0.2f, 2.0f) else 0.6f

        val metrics = TugMetrics(
            clinicalTugSec = clinicalTugSec,
            movementSec = movementSec,
            reactionSec = reactionSec,
            standSec = standSec,
            walkSec = walkSec,
            turn180Sec = turn180Sec,
            turnToSitSec = turnToSitSec,
            sitSec = sitSec,
            gaitSpeedMps = gaitSpeed,
            unstableMount = !baseline.stableMount,
            assessment = buildAssessment(clinicalTugSec, gaitSpeed, turn180Sec),
        )
        return Segmentation(metrics, turnPeak, standPeakOrSit)
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

    private fun maxInRange(arr: FloatArray, from: Int, toInclusive: Int): Float {
        var m = 0f
        val lo = from.coerceIn(0, arr.size - 1)
        val hi = toInclusive.coerceIn(lo, arr.size - 1)
        for (b in lo..hi) if (arr[b] > m) m = arr[b]
        return m
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

    private fun magnitude(x: Float, y: Float, z: Float): Double =
        sqrt((x * x + y * y + z * z).toDouble())

    private fun normalize(value: Double, min: Double, max: Double): Float =
        ((value - min) / (max - min)).coerceIn(0.0, 1.0).toFloat()

    private companion object {
        const val DtNs = 50_000_000L
        const val BaselineAlpha = 0.05f
        const val SmoothAlpha = 0.5f
        const val TurnYawThreshold = 1.0f
        const val WalkAccelThreshold = 1.2f
        const val OnsetThreshold = 0.6f

        const val ClinicalFollowUpSec = 13.5f
        const val TurnDelaySec = 2.45f
    }
}
