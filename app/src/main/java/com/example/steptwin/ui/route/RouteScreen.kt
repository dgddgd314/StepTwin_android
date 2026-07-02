package com.example.steptwin.ui.route

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.steptwin.domain.route.RouteSegment
import com.example.steptwin.ui.components.WeightVectorSummary

@Composable
fun RouteScreen(
    viewModel: RouteViewModel,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()
    val route = uiState.route

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "맞춤 길찾기",
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(text = "청량리역에서 경희의료원까지의 데모 경로입니다.")

        if (uiState.isUsingDefaultProfile) {
            Text(text = "아직 측정 결과가 없어 기본 보행 프로필로 계산합니다.")
        }

        HorizontalDivider()

        Text(
            text = "추천 경로",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(text = route.segments.joinToString(" -> ") { it.from.label } + " -> 경희의료원")
        Text(text = "총 거리: ${route.totalDistanceMeters}m")
        Text(text = "개인화 비용: ${"%.1f".format(route.totalCost)}")
        Text(text = route.reason)

        HorizontalDivider()

        Text(
            text = "현재 반영된 취약도",
            style = MaterialTheme.typography.titleMedium,
        )
        WeightVectorSummary(weights = route.weights)

        HorizontalDivider()

        Text(
            text = "구간별 비용",
            style = MaterialTheme.typography.titleMedium,
        )
        route.segments.forEach { segment ->
            RouteSegmentRow(segment = segment)
        }
    }
}

@Composable
private fun RouteSegmentRow(segment: RouteSegment) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = "${segment.from.label} -> ${segment.to.label}",
            style = MaterialTheme.typography.titleSmall,
        )
        Text(text = "${segment.edge.distanceMeters}m / 비용 ${"%.1f".format(segment.cost)}")
        Text(
            text = "좁음 ${risk(segment.edge.narrowRisk)} · 계단 ${risk(segment.edge.stairsRisk)} · " +
                "경사 ${risk(segment.edge.slopeRisk)} · 턱 ${risk(segment.edge.curbRisk)} · " +
                "회전 ${risk(segment.edge.turnRisk)}",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private fun risk(value: Float): String {
    return when {
        value >= 0.7f -> "높음"
        value >= 0.35f -> "중간"
        else -> "낮음"
    }
}
