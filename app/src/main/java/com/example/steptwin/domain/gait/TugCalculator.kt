package com.example.steptwin.domain.gait

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt
import javax.inject.Inject

class TugCalculator @Inject constructor() {
    fun calculate(samples: List<SensorSample>): TugWeights {
        val accelerationSamples = samples.filter { it.type == SensorSampleType.LinearAcceleration }
        val gyroSamples = samples.filter { it.type == SensorSampleType.Gyroscope }

        if (accelerationSamples.isEmpty() && gyroSamples.isEmpty()) {
            return TugWeights.neutral()
        }

        return TugWeights(
            speedWeight = calculateSpeedVulnerability(accelerationSamples),
            turnWeight = calculateTurnVulnerability(gyroSamples),
            strengthWeight = calculateStrengthVulnerability(accelerationSamples),
        )
    }

    private fun calculateSpeedVulnerability(samples: List<SensorSample>): Float {
        if (samples.size < 4) return 0.5f

        val magnitudes = samples.map { magnitude(it.x, it.y, it.z) }
        val movementEnergy = magnitudes.average()
        val movementVariance = variance(magnitudes)
        val stepRate = estimatePeakRate(samples, magnitudes)

        val lowCadenceScore = 1f - normalize(stepRate, min = 0.7, max = 1.8)
        val lowEnergyScore = 1f - normalize(movementEnergy, min = 0.3, max = 3.0)
        val irregularityScore = normalize(movementVariance, min = 0.2, max = 6.0)

        return weightedAverage(
            lowCadenceScore to 0.45f,
            lowEnergyScore to 0.25f,
            irregularityScore to 0.30f,
        )
    }

    private fun calculateTurnVulnerability(samples: List<SensorSample>): Float {
        if (samples.size < 4) return 0.5f

        val lateralWobble = samples.map { magnitude(it.x, it.y, 0f) }.average()
        val yawInstability = samples.map { abs(it.z.toDouble()) }.average()
        val combined = lateralWobble * 0.75 + yawInstability * 0.25

        return normalize(combined, min = 0.08, max = 1.5)
    }

    private fun calculateStrengthVulnerability(samples: List<SensorSample>): Float {
        if (samples.size < 4) return 0.5f

        val startNanos = samples.first().timestampNanos
        val earlyWindow = samples.takeWhile { it.timestampNanos - startNanos <= 3_000_000_000L }
        val verticalPeak = earlyWindow.maxOfOrNull { abs(it.z.toDouble()) } ?: 0.0

        return 1f - normalize(verticalPeak, min = 1.5, max = 6.0)
    }

    private fun estimatePeakRate(samples: List<SensorSample>, magnitudes: List<Double>): Double {
        val durationSeconds = ((samples.last().timestampNanos - samples.first().timestampNanos) / 1_000_000_000.0)
            .coerceAtLeast(1.0)
        val average = magnitudes.average()
        val standardDeviation = sqrt(variance(magnitudes))
        val threshold = average + standardDeviation * 0.45

        var peaks = 0
        var lastPeakNanos = samples.first().timestampNanos - 250_000_000L
        for (index in 1 until magnitudes.lastIndex) {
            val isLocalPeak = magnitudes[index] > magnitudes[index - 1] &&
                magnitudes[index] > magnitudes[index + 1] &&
                magnitudes[index] >= threshold
            val isSeparated = samples[index].timestampNanos - lastPeakNanos >= 250_000_000L

            if (isLocalPeak && isSeparated) {
                peaks += 1
                lastPeakNanos = samples[index].timestampNanos
            }
        }

        return peaks / durationSeconds
    }

    private fun magnitude(x: Float, y: Float, z: Float): Double {
        return sqrt(x.toDouble().pow(2) + y.toDouble().pow(2) + z.toDouble().pow(2))
    }

    private fun variance(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val average = values.average()
        return values.sumOf { (it - average).pow(2) } / values.size
    }

    private fun normalize(value: Double, min: Double, max: Double): Float {
        return ((value - min) / (max - min)).coerceIn(0.0, 1.0).toFloat()
    }

    private fun weightedAverage(vararg values: Pair<Float, Float>): Float {
        val weightTotal = values.sumOf { it.second.toDouble() }.toFloat()
        if (weightTotal == 0f) return 0f
        return (values.sumOf { (it.first * it.second).toDouble() } / weightTotal).toFloat()
            .coerceIn(0f, 1f)
    }
}
