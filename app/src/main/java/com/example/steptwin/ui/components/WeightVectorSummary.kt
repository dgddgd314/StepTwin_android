package com.example.steptwin.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.steptwin.domain.gait.TugWeights
import kotlin.math.roundToInt

@Composable
fun WeightVectorSummary(
    weights: TugWeights,
    modifier: Modifier = Modifier,
    tested: Boolean = true,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        WeightRow(label = "속도지수", value = weights.speedWeight, tested = tested)
        WeightRow(label = "회전지수", value = weights.turnWeight, tested = tested)
        WeightRow(label = "근력지수", value = weights.strengthWeight, tested = tested)
    }
}

@Composable
private fun WeightRow(label: String, value: Float, tested: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label)
        Text(text = if (tested) formatIndex(value) else "측정 전")
    }
}

/** 취약도(0~1)를 지수(0~100, 높을수록 양호)로 변환: 100 - 취약도. */
fun formatIndex(value: Float): String {
    return "${100 - (value.coerceIn(0f, 1f) * 100).roundToInt()}점"
}

fun formatPercent(value: Float): String {
    return "${(value.coerceIn(0f, 1f) * 100).roundToInt()}%"
}
