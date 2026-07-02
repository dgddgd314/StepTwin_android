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
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.steptwin.domain.gait.FallRisk
import com.example.steptwin.domain.gait.SensorSampleType
import com.example.steptwin.domain.gait.TugMetrics
import com.example.steptwin.domain.gait.TugPhase
import com.example.steptwin.domain.gait.TugWeights
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
            text = "3축 센서 기반 TUG 보행 검사",
            style = MaterialTheme.typography.headlineSmall,
        )

        when (uiState.status) {
            TugMeasureStatus.Idle -> IdleIntro(onStart = viewModel::startRecording)
            TugMeasureStatus.Countdown -> CountdownView(value = uiState.countdownValue)
            TugMeasureStatus.Recording -> RecordingView(
                uiState = uiState,
                onFinish = viewModel::finishRecording,
            )
            TugMeasureStatus.Analyzing -> AnalyzingView(message = uiState.message)
            TugMeasureStatus.Complete -> ResultView(
                uiState = uiState,
                onRestart = viewModel::reset,
            )
        }
    }
}

// ---------------- 상태별 화면 ----------------

@Composable
private fun IdleIntro(onStart: () -> Unit) {
    Text(text = "TUG(Timed Up and Go) 검사는 다음 순서로 진행합니다.")
    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TugStepText("① 의자에 앉은 상태로 시작")
            TugStepText("② 일어서기")
            TugStepText("③ 3m 앞으로 걷기")
            TugStepText("④ 돌아서기(180° 회전)")
            TugStepText("⑤ 3m 되돌아오기")
            TugStepText("⑥ 다시 앉기")
        }
    }
    Text(
        text = "폰을 몸(주머니·허리)에 지닌 채 앉아서 시작 버튼을 누르고, 안내대로 수행하세요. " +
            "앉으면 자동으로 종료되고 총 소요 시간과 AI 분석 결과가 표시됩니다.",
        style = MaterialTheme.typography.bodySmall,
    )
    Button(onClick = onStart) {
        Text(text = "TUG 검사 시작")
    }
}

@Composable
private fun CountdownView(value: Int) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = "준비", style = MaterialTheme.typography.titleMedium)
            Text(
                text = if (value > 0) "$value" else "시작!",
                style = MaterialTheme.typography.displayLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "의자에 앉은 채로 기다리세요.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun RecordingView(
    uiState: TugMeasureUiState,
    onFinish: () -> Unit,
) {
    Text(text = uiState.message, style = MaterialTheme.typography.bodyMedium)

    // 큰 카운트업 타이머
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "${uiState.elapsedTenths}초",
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.Bold,
            )
            uiState.currentPhase?.let { phase ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(phase.color()),
                    )
                    Text(
                        text = "현재 동작: ${phase.label()}",
                        style = MaterialTheme.typography.titleMedium,
                        color = phase.color(),
                    )
                }
            }
        }
    }

    StepChecklist(
        standDone = uiState.standDone,
        walkDone = uiState.walkDone,
        turnDone = uiState.turnDone,
    )

    LiveSensorGraph(
        accel = uiState.accelWave,
        gyro = uiState.gyroWave,
        phases = uiState.phaseWave,
        currentPhase = uiState.currentPhase,
        isRecording = true,
        hasData = uiState.hasWaveform,
    )

    Button(
        onClick = onFinish,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(text = "완료 (앉았어요)")
    }
    Text(
        text = "수집 샘플 ${uiState.sampleCount}개 · 앉은 자세가 유지되면 자동 종료됩니다.",
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun AnalyzingView(message: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircularProgressIndicator()
            Text(text = message, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ResultView(
    uiState: TugMeasureUiState,
    onRestart: () -> Unit,
) {
    val metrics = uiState.metrics
    val weights = uiState.weights

    Text(text = uiState.message, style = MaterialTheme.typography.bodyMedium)

    if (metrics != null) {
        Card {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(text = "TUG 결과", style = MaterialTheme.typography.titleMedium)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom,
                ) {
                    Text(
                        text = "${"%.1f".format(metrics.tugTimeSec)}초",
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "낙상위험 ${metrics.fallRisk.label}",
                        style = MaterialTheme.typography.titleMedium,
                        color = metrics.fallRisk.color(),
                    )
                }
                HorizontalDivider()
                MetricRow("일어서기", "${"%.1f".format(metrics.standSec)}초")
                MetricRow("보행(왕복)", "${"%.1f".format(metrics.walkSec)}초")
                MetricRow("회전", "${"%.1f".format(metrics.turnSec)}초")
                MetricRow("추정 보행속도", "${"%.2f".format(metrics.gaitSpeedMps)} m/s")
            }
        }
    }

    if (weights != null) {
        HorizontalDivider()
        Text(text = "AI 보행 분석 결과", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "온디바이스 AI가 TUG 구간 지표를 분석해 보행 속도·회전·근력 취약도를 산출했습니다.",
            style = MaterialTheme.typography.bodySmall,
        )
        WeightVectorSummary(weights = weights)
        aiInsightLines(weights).forEach { line ->
            Text(text = "• $line", style = MaterialTheme.typography.bodySmall)
        }
    }

    uiState.syncMessage?.let { message ->
        Text(
            text = "서버 응답: $message",
            style = MaterialTheme.typography.bodySmall,
        )
    }

    OutlinedButton(onClick = onRestart) {
        Text(text = "다시 측정")
    }
}

// ---------------- 단계 체크리스트 ----------------

@Composable
private fun StepChecklist(
    standDone: Boolean,
    walkDone: Boolean,
    turnDone: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        StepItem("일어서기", standDone)
        StepItem("보행 감지", walkDone)
        StepItem("회전 감지", turnDone)
        StepItem("복귀·착석", false, pending = true)
    }
}

@Composable
private fun StepItem(label: String, done: Boolean, pending: Boolean = false) {
    val mark = when {
        done -> "✅"
        pending -> "⏳"
        else -> "⬜"
    }
    Text(
        text = "$mark $label",
        style = MaterialTheme.typography.bodyMedium,
        color = if (done) WalkColor else MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun TugStepText(text: String) {
    Text(text = text, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun MetricRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
        Text(text = value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
    }
}

// ---------------- 센서 브리지 ----------------

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

            onDispose { sensorManager.unregisterListener(listener) }
        }
    }
}

// ---------------- 실시간 그래프 ----------------

private const val AccelScale = 8f
private const val GyroScale = 6f

private val AccelColor = Color(0xFF38BDF8)
private val GyroColor = Color(0xFFFB923C)
private val GraphBackground = Color(0xFF0B1220)
private val GraphGrid = Color(0xFF334155)

private val StillColor = Color(0xFF64748B)
private val WalkColor = Color(0xFF22C55E)
private val TurnColor = Color(0xFFF97316)

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

private fun FallRisk.color(): Color = when (this) {
    FallRisk.Low -> WalkColor
    FallRisk.Moderate -> TurnColor
    FallRisk.High -> Color(0xFFDC2626)
    FallRisk.Unknown -> StillColor
}

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
        Text(
            text = if (isRecording) "실시간 센서 파형 (측정 중)" else "센서 파형",
            style = MaterialTheme.typography.titleSmall,
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(GraphBackground),
            contentAlignment = Alignment.Center,
        ) {
            if (!hasData) {
                Text(
                    text = "측정이 시작되면 실시간 그래프가 표시됩니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF94A3B8),
                )
            } else {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawPhaseBands(phases)
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

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LegendItem(color = AccelColor, label = "움직임")
            LegendItem(color = GyroColor, label = "회전")
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

// 취약도 값을 AI 코멘트로 러프하게 변환.
private fun aiInsightLines(weights: TugWeights): List<String> {
    fun level(v: Float) = when {
        v >= 0.66f -> "높음"
        v >= 0.33f -> "보통"
        else -> "낮음"
    }
    return listOf(
        "보행 속도 취약도 ${level(weights.speedWeight)} — 보행속도·TUG 총시간을 반영했습니다.",
        "회전 취약도 ${level(weights.turnWeight)} — 회전 시간·각속도를 반영했습니다.",
        "근력 취약도 ${level(weights.strengthWeight)} — 일어서기 시간·수직 가속을 반영했습니다.",
    )
}
