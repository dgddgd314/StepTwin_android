package com.example.steptwin.ui.gait

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
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

        LiveSensorGraph(
            accel = uiState.accelWave,
            gyro = uiState.gyroWave,
            phases = uiState.phaseWave,
            currentPhase = uiState.currentPhase,
            isRecording = uiState.isRecording,
            hasData = uiState.hasWaveform,
        )

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

// 정규화 기준. 움직임 세기/회전이 이 값을 넘으면 그래프 상단에 붙는다.
// (기준선 제거된 '움직임'이라 정지 시 0 근처 → 낮게 잡아 민감하게)
private const val AccelScale = 8f // m/s^2 (움직임 세기)
private const val GyroScale = 6f // rad/s (회전 세기)

private val AccelColor = Color(0xFF38BDF8) // 하늘색 = 움직임(가속도)
private val GyroColor = Color(0xFFFB923C) // 주황색 = 회전(자이로)
private val GraphBackground = Color(0xFF0B1220)
private val GraphGrid = Color(0xFF334155)

private val StillColor = Color(0xFF64748B) // 정지/자세전환
private val WalkColor = Color(0xFF22C55E) // 보행
private val TurnColor = Color(0xFFF97316) // 회전

private fun TugPhase.color(): Color = when (this) {
    TugPhase.Still -> StillColor
    TugPhase.Walk -> WalkColor
    TugPhase.Turn -> TurnColor
}

private fun TugPhase.label(): String = when (this) {
    TugPhase.Still -> "정지·자세전환"
    TugPhase.Walk -> "보행"
    TugPhase.Turn -> "회전"
}

/**
 * 실시간 센서 파형 그래프.
 * - 움직임(가속도)과 회전(자이로)을 서로 다른 색 선으로 그린다.
 * - 배경은 자동 분류된 TUG 구간(정지·보행·회전) 색으로 띠를 칠해 구간을 눈으로 구분한다.
 */
@Composable
private fun LiveSensorGraph(
    accel: List<Float>,
    gyro: List<Float>,
    phases: List<TugPhase>,
    currentPhase: TugPhase?,
    isRecording: Boolean,
    hasData: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (isRecording) "실시간 센서 파형 (측정 중)" else "센서 파형",
                style = MaterialTheme.typography.titleSmall,
            )
            if (isRecording && currentPhase != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(currentPhase.color()),
                    )
                    Text(
                        text = "현재: ${currentPhase.label()}",
                        style = MaterialTheme.typography.labelLarge,
                        color = currentPhase.color(),
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(GraphBackground),
            contentAlignment = Alignment.Center,
        ) {
            if (!hasData) {
                Text(
                    text = "측정을 시작하면 여기에 실시간 그래프가 표시됩니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF94A3B8),
                )
            } else {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    // 1) 구간 색 배경 띠
                    drawPhaseBands(phases)
                    // 2) 파형 영역은 세로 여백을 주고 그린다.
                    val inset = size.height * 0.08f
                    val plotHeight = size.height - inset * 2f
                    drawLine(
                        color = GraphGrid,
                        start = Offset(0f, size.height / 2f),
                        end = Offset(size.width, size.height / 2f),
                        strokeWidth = 1f,
                    )
                    drawWave(accel, AccelScale, AccelColor, inset, plotHeight)
                    drawWave(gyro, GyroScale, GyroColor, inset, plotHeight)
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            LegendItem(color = AccelColor, label = "움직임")
            LegendItem(color = GyroColor, label = "회전")
            Spacer(modifier = Modifier.size(4.dp))
            LegendItem(color = WalkColor, label = "보행구간")
            LegendItem(color = TurnColor, label = "회전구간")
        }
    }
}

@Composable
private fun LegendItem(color: Color, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(color),
        )
        Text(text = label, style = MaterialTheme.typography.bodySmall)
    }
}

/** 구간(phase)별로 세로 띠를 칠한다. Still 은 배경과 비슷해 생략(어둡게 유지). */
private fun DrawScope.drawPhaseBands(phases: List<TugPhase>) {
    if (phases.isEmpty()) return
    val bandWidth = size.width / phases.size
    phases.forEachIndexed { index, phase ->
        if (phase == TugPhase.Still) return@forEachIndexed
        drawRect(
            color = phase.color().copy(alpha = 0.28f),
            topLeft = Offset(index * bandWidth, 0f),
            size = androidx.compose.ui.geometry.Size(bandWidth + 1f, size.height),
        )
    }
}

/** 값 리스트를 0~scale 로 정규화해 plot 영역(inset~inset+height) 안에 선을 그린다. */
private fun DrawScope.drawWave(
    values: List<Float>,
    scale: Float,
    color: Color,
    inset: Float,
    plotHeight: Float,
) {
    if (values.size < 2) return
    val stepX = size.width / (values.size - 1)
    val bottom = inset + plotHeight
    val path = Path()
    values.forEachIndexed { index, value ->
        val norm = (value / scale).coerceIn(0f, 1f)
        val x = index * stepX
        val y = bottom - norm * plotHeight
        if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    drawPath(path = path, color = color, style = Stroke(width = 3f))
}

private fun statusLabel(status: TugMeasureStatus): String {
    return when (status) {
        TugMeasureStatus.Idle -> "대기"
        TugMeasureStatus.Recording -> "측정 중"
        TugMeasureStatus.Analyzing -> "분석 중"
        TugMeasureStatus.Complete -> "완료"
    }
}
