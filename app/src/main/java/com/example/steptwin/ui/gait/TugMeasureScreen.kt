package com.example.steptwin.ui.gait

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.steptwin.domain.gait.SensorSampleType
import com.example.steptwin.ui.components.WeightVectorSummary

@Composable
fun TugMeasureScreen(
    viewModel: TugMeasureViewModel,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()

    TugSensorBridge(
        isRecording = uiState.isRecording,
        onSample = viewModel::addSensorData,
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "3축 센서 기반 보행 검사",
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(text = uiState.message)

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                enabled = uiState.status != TugMeasureStatus.Recording &&
                    uiState.status != TugMeasureStatus.Analyzing,
                onClick = viewModel::startRecording,
            ) {
                Text(text = "TUG 테스트 시작")
            }

            OutlinedButton(
                enabled = uiState.status != TugMeasureStatus.Recording,
                onClick = viewModel::reset,
            ) {
                Text(text = "초기화")
            }
        }

        HorizontalDivider()

        Text(text = "측정 상태: ${statusLabel(uiState.status)}")
        Text(text = "경과 시간: ${uiState.elapsedSeconds}초 / 15초")
        Text(text = "수집 샘플: ${uiState.sampleCount}개")

        uiState.weights?.let { weights ->
            HorizontalDivider()
            Text(
                text = "산출된 취약도 벡터",
                style = MaterialTheme.typography.titleMedium,
            )
            WeightVectorSummary(weights = weights)
        }

        uiState.syncMessage?.let { message ->
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "서버 응답: $message",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun TugSensorBridge(
    isRecording: Boolean,
    onSample: (SensorSampleType, Long, Float, Float, Float) -> Unit,
) {
    val context = LocalContext.current
    val latestOnSample by rememberUpdatedState(onSample)

    DisposableEffect(isRecording, context) {
        if (!isRecording) {
            onDispose {}
        } else {
            val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
            val accelerationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
                ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            val gyroscopeSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    val type = if (event.sensor.type == Sensor.TYPE_GYROSCOPE) {
                        SensorSampleType.Gyroscope
                    } else {
                        SensorSampleType.LinearAcceleration
                    }

                    latestOnSample(
                        type,
                        event.timestamp,
                        event.values[0],
                        event.values[1],
                        event.values[2],
                    )
                }

                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            }

            accelerationSensor?.let {
                sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME)
            }
            gyroscopeSensor?.let {
                sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME)
            }

            onDispose {
                sensorManager.unregisterListener(listener)
            }
        }
    }
}

private fun statusLabel(status: TugMeasureStatus): String {
    return when (status) {
        TugMeasureStatus.Idle -> "대기"
        TugMeasureStatus.Recording -> "측정 중"
        TugMeasureStatus.Analyzing -> "분석 중"
        TugMeasureStatus.Complete -> "완료"
    }
}
