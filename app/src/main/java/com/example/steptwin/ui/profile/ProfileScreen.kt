package com.example.steptwin.ui.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
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
import com.example.steptwin.ui.components.WeightVectorSummary

@Composable
fun ProfileScreen(
    viewModel: ProfileViewModel,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "내 보행정보",
            style = MaterialTheme.typography.headlineSmall,
        )

        val weights = uiState.weights
        if (weights == null) {
            Text(text = "아직 저장된 보행 검사 결과가 없습니다.")
            Text(text = "보행 검사 탭에서 TUG 테스트를 완료하면 이곳에 취약도 벡터가 표시됩니다.")
        } else {
            Text(text = "최근 검사에서 계산된 개인 보행 프로필입니다.")
            WeightVectorSummary(weights = weights)

            HorizontalDivider()

            Text(
                text = "추천 근거",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(text = profileAdvice(weights.speedWeight, weights.turnWeight, weights.strengthWeight))
        }
    }
}

private fun profileAdvice(speed: Float, turn: Float, strength: Float): String {
    return when {
        strength >= turn && strength >= speed ->
            "근력 취약도가 가장 높아 계단, 턱, 급한 경사가 있는 구간의 비용을 크게 올립니다."
        turn >= speed ->
            "회전 취약도가 가장 높아 복잡한 골목과 방향 전환이 잦은 구간의 비용을 크게 올립니다."
        else ->
            "속도 취약도가 가장 높아 긴 거리와 좁은 보도 부담을 함께 줄이는 경로를 우선합니다."
    }
}
