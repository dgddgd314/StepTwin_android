package com.example.steptwin.domain.gait

data class SensorSample(
    val type: SensorSampleType,
    val timestampNanos: Long,
    val x: Float,
    val y: Float,
    val z: Float,
)

enum class SensorSampleType {
    LinearAcceleration,
    Gyroscope,
    Gravity,
}
