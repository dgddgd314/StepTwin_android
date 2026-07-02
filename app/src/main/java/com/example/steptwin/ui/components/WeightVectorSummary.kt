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
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        WeightRow(label = "속도 취약도", value = weights.speedWeight)
        WeightRow(label = "회전 취약도", value = weights.turnWeight)
        WeightRow(label = "근력 취약도", value = weights.strengthWeight)
    }
}

@Composable
private fun WeightRow(label: String, value: Float) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label)
        Text(text = formatPercent(value))
    }
}

fun formatPercent(value: Float): String {
    return "${(value.coerceIn(0f, 1f) * 100).roundToInt()}%"
}
