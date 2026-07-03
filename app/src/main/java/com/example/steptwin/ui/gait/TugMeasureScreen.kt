package com.example.steptwin.ui.gait

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.speech.tts.TextToSpeech
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import java.util.Locale
import kotlin.math.roundToInt
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
        isSensing = uiState.isSensing,
        onSample = viewModel::addSensorData,
    )

    // 허리 고정 시 화면을 못 보므로 한국어 음성으로 안내한다.
    val speaker = rememberTugSpeaker()
    LaunchedEffect(uiState.countdownValue, uiState.status) {
        if (uiState.status == TugMeasureStatus.Countdown) {
            when (uiState.countdownValue) {
                3 -> speaker.speak("셋")
                2 -> speaker.speak("둘")
                1 -> speaker.speak("하나")
            }
        }
    }
    LaunchedEffect(uiState.status) {
        when (uiState.status) {
            TugMeasureStatus.Baseline ->
                speaker.speak("잠시 가만히 계세요. 기준을 측정합니다.")
            TugMeasureStatus.Recording ->
                speaker.speak(
                    "시작하세요. 먼저 천천히 일어서세요. 일어선 다음 한두 걸음 걷지 말고 " +
                        "그 자리에 잠시 멈추세요. 그리고 삼 미터 걷고, 돌아서 제자리로 돌아와 앉으세요.",
                )
            TugMeasureStatus.Complete -> {
                val m = uiState.metrics
                if (m != null) {
                    val follow = if (m.assessment.needsFollowUp) {
                        "이동기능 추가평가를 권장합니다."
                    } else {
                        "특이 신호는 낮은 편입니다."
                    }
                    speaker.speak("측정 완료. 임상 소요 시간 ${m.clinicalTugSec.roundToInt()}초. $follow")
                } else {
                    speaker.speak("측정 완료.")
                }
            }
            else -> Unit
        }
    }
    LaunchedEffect(uiState.walkDone) { if (uiState.walkDone) speaker.speak("보행 감지") }
    LaunchedEffect(uiState.turnDone) { if (uiState.turnDone) speaker.speak("회전 감지") }

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
            TugMeasureStatus.Idle -> IdleIntro(
                uiState = uiState,
                onStart = viewModel::startRecording,
                onManualTugChange = viewModel::updateManualTug,
                onManualGaitChange = viewModel::updateManualGait,
                onManualTurnChange = viewModel::updateManualTurn,
                onSubmitManual = viewModel::submitManual,
            )
            TugMeasureStatus.Countdown -> CountdownView(value = uiState.countdownValue)
            TugMeasureStatus.Baseline -> BaselineView(message = uiState.message)
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
private fun IdleIntro(
    uiState: TugMeasureUiState,
    onStart: () -> Unit,
    onManualTugChange: (String) -> Unit,
    onManualGaitChange: (String) -> Unit,
    onManualTurnChange: (String) -> Unit,
    onSubmitManual: () -> Unit,
) {
    Text(text = "TUG(Timed Up and Go) 검사는 다음 순서로 진행합니다.")
    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TugStepText("① 의자에 앉은 상태로 시작")
            TugStepText("② 천천히 일어서기")
            TugStepText("③ 일어선 채로 잠시 멈추기 (1~2초)")
            TugStepText("④ 3m 앞으로 걷기")
            TugStepText("⑤ 돌아서기(180° 회전)")
            TugStepText("⑥ 3m 되돌아오기")
            TugStepText("⑦ 다시 앉기")
        }
    }
    Text(
        text = "폰을 몸(주머니·허리)에 지닌 채 앉아서 시작 버튼을 누르고, 안내대로 수행하세요. " +
            "특히 일어선 직후에는 바로 걷지 말고 1~2초 그 자리에 멈춘 뒤 걸으세요 — " +
            "일어서는 동작(몸이 앞으로 쏠림)이 보행으로 잘못 잡히지 않게 하기 위함입니다. " +
            "앉으면 자동으로 종료되고 총 소요 시간과 AI 분석 결과가 표시됩니다.",
        style = MaterialTheme.typography.bodySmall,
    )
    Button(onClick = onStart) {
        Text(text = "TUG 검사 시작")
    }

    HorizontalDivider()

    // 의료진 직접 입력(측정 대체)
    Text(text = "의료진 직접 입력", style = MaterialTheme.typography.titleMedium)
    Text(
        text = "다른 곳에서 이미 TUG를 측정했다면 값을 입력해 결과를 받을 수 있습니다.",
        style = MaterialTheme.typography.bodySmall,
    )
    OutlinedTextField(
        value = uiState.manualTug,
        onValueChange = onManualTugChange,
        label = { Text(text = "TUG 총시간 (초, 필수)") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = uiState.manualGait,
        onValueChange = onManualGaitChange,
        label = { Text(text = "보행속도 (m/s, 선택)") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = uiState.manualTurn,
        onValueChange = onManualTurnChange,
        label = { Text(text = "180° 회전시간 (초, 선택)") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth(),
    )
    if (uiState.manualError != null) {
        Text(
            text = uiState.manualError,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
    OutlinedButton(onClick = onSubmitManual, modifier = Modifier.fillMaxWidth()) {
        Text(text = "입력값으로 결과 보기")
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
private fun BaselineView(message: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(text = "기준신호 측정 중", style = MaterialTheme.typography.titleMedium)
            CircularProgressIndicator()
            Text(text = message, style = MaterialTheme.typography.bodyMedium)
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
                val assess = metrics.assessment
                Text(text = "TUG 결과", style = MaterialTheme.typography.titleMedium)
                if (uiState.manualEntry) {
                    Text(
                        text = "※ 의료진이 입력한 TUG 값 기반 결과입니다(세부 구간은 추정).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom,
                ) {
                    Text(
                        text = "${"%.1f".format(metrics.clinicalTugSec)}초",
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = if (assess.needsFollowUp) "이동기능 추가평가 권장" else "추가 신호 낮음",
                        style = MaterialTheme.typography.titleSmall,
                        color = if (assess.needsFollowUp) TurnColor else WalkColor,
                    )
                }
                // 시간 3종 분리(서로 다른 조건의 임상 컷오프 오적용 방지)
                Text(
                    text = "임상 ${"%.1f".format(metrics.clinicalTugSec)}초 · " +
                        "동작 ${"%.1f".format(metrics.movementSec)}초 · " +
                        "반응 ${"%.1f".format(metrics.reactionSec)}초",
                    style = MaterialTheme.typography.bodySmall,
                )
                HorizontalDivider()
                MetricRow("기립(sit-to-stand)", "${"%.1f".format(metrics.standSec)}초")
                MetricRow("보행(왕복)", "${"%.1f".format(metrics.walkSec)}초")
                MetricRow("180° 회전", "${"%.1f".format(metrics.turn180Sec)}초")
                MetricRow("의자앞 회전", "${"%.1f".format(metrics.turnToSitSec)}초")
                MetricRow("착석(stand-to-sit)", "${"%.1f".format(metrics.sitSec)}초")
                MetricRow(
                    "추정 보행속도",
                    "${"%.2f".format(metrics.gaitSpeedMps)} m/s (${assess.gaitBand.label})",
                )

                if (metrics.unstableMount) {
                    Text(
                        text = "⚠ 정지 기준신호에서 폰 고정이 불안정했습니다. 결과 신뢰도가 낮을 수 있어요.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TurnColor,
                    )
                }

                if (assess.tags.isNotEmpty()) {
                    HorizontalDivider()
                    assess.tags.forEach { tag ->
                        Text(text = "• $tag", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Text(
                    text = "본 결과는 낙상·허약을 진단하지 않으며, 낙상이력·근력·균형·인지·약물·보행환경과 " +
                        "함께 해석해야 합니다. 정확한 보행속도는 별도 4m 보행검사를 권장합니다.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }

    if (weights != null) {
        HorizontalDivider()
        Text(text = "AI 보행 분석 결과", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "온디바이스 AI가 TUG 구간 지표를 분석해 속도지수·회전지수·근력지수를 산출했습니다(높을수록 양호).",
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
    isSensing: Boolean,
    onSample: (SensorSampleType, Long, Float, Float, Float) -> Unit,
) {
    val context = LocalContext.current
    val latestOnSample by rememberUpdatedState(onSample)

    DisposableEffect(isSensing, context) {
        if (!isSensing) {
            onDispose {}
        } else {
            val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
            val accelerationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
                ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            val gyroscopeSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
            val gravitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)

            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    val type = when (event.sensor.type) {
                        Sensor.TYPE_GYROSCOPE -> SensorSampleType.Gyroscope
                        Sensor.TYPE_GRAVITY -> SensorSampleType.Gravity
                        else -> SensorSampleType.LinearAcceleration
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
            gravitySensor?.let {
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

// ---------------- 음성 안내(TTS) ----------------

@Composable
private fun rememberTugSpeaker(): TugSpeaker {
    val context = LocalContext.current
    val speaker = remember { TugSpeaker(context) }
    DisposableEffect(speaker) {
        onDispose { speaker.shutdown() }
    }
    return speaker
}

/** 한국어 TTS 래퍼. 안드로이드 내장 엔진 사용(추가 의존성/권한 없음). */
private class TugSpeaker(context: Context) {
    private var ready = false
    private var engine: TextToSpeech? = null

    init {
        val app = context.applicationContext
        engine = TextToSpeech(app) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = engine?.setLanguage(Locale.KOREAN)
                    ?: TextToSpeech.LANG_NOT_SUPPORTED
                ready = result >= TextToSpeech.LANG_AVAILABLE
            }
        }
    }

    fun speak(text: String) {
        val e = engine ?: return
        if (!ready) return
        e.speak(text, TextToSpeech.QUEUE_FLUSH, null, text.hashCode().toString())
    }

    fun shutdown() {
        engine?.stop()
        engine?.shutdown()
        engine = null
    }
}

// 지수(높을수록 양호) 값을 AI 코멘트로 러프하게 변환. 입력 v 는 취약도(0~1)라 지수는 그 반대.
private fun aiInsightLines(weights: TugWeights): List<String> {
    fun level(v: Float) = when {
        v <= 0.34f -> "높음"
        v <= 0.67f -> "보통"
        else -> "낮음"
    }
    return listOf(
        "속도지수 ${level(weights.speedWeight)} — 보행속도·TUG 총시간을 반영했습니다.",
        "회전지수 ${level(weights.turnWeight)} — 회전 시간·각속도를 반영했습니다.",
        "근력지수 ${level(weights.strengthWeight)} — 일어서기 시간·수직 가속을 반영했습니다.",
    )
}
