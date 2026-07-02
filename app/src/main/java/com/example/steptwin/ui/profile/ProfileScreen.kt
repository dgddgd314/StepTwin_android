package com.example.steptwin.ui.profile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.steptwin.domain.favorites.FavoriteRoute
import com.example.steptwin.domain.gait.TugWeights
import com.example.steptwin.ui.components.WeightVectorSummary

@Composable
fun ProfileScreen(
    viewModel: ProfileViewModel,
    onOpenRoute: () -> Unit,
    onWithdrawConsent: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()
    var showClearDialog by remember { mutableStateOf(false) }
    var showWithdrawDialog by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(text = "내 보행정보", style = MaterialTheme.typography.headlineSmall)

        // ---- 보행 프로필 ----
        val weights = uiState.weights
        val untested = weights == null ||
            (weights.speedWeight == 0f && weights.turnWeight == 0f && weights.strengthWeight == 0f)
        if (untested) {
            Text(text = "아직 보행 검사를 하지 않았습니다. 세 취약도(속도·회전·근력)는 모두 0입니다.")
            WeightVectorSummary(weights = weights ?: TugWeights.zero())
        } else {
            Text(text = "최근 검사에서 계산된 개인 보행 프로필입니다.")
            WeightVectorSummary(weights = weights)
            HorizontalDivider()
            Text(text = "추천 근거", style = MaterialTheme.typography.titleMedium)
            Text(text = profileAdvice(weights.speedWeight, weights.turnWeight, weights.strengthWeight))
        }

        HorizontalDivider()

        // ---- 즐겨찾기 ----
        Text(text = "저장한 경로 (즐겨찾기)", style = MaterialTheme.typography.titleMedium)
        if (uiState.favorites.isEmpty()) {
            Text(text = "맞춤 길찾기에서 경로를 찾은 뒤 '즐겨찾기'를 누르면 여기에 저장됩니다.")
        } else {
            uiState.favorites.forEach { favorite ->
                FavoriteRow(
                    favorite = favorite,
                    onOpen = {
                        viewModel.openFavorite(favorite)
                        onOpenRoute()
                    },
                    onRemove = { viewModel.removeFavorite(favorite.id) },
                )
            }
        }

        HorizontalDivider()

        // ---- 개인정보 보호 ----
        Text(text = "🔒 개인정보 보호", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "• 보행 검사 결과와 즐겨찾기는 이 기기(온디바이스)에만 저장됩니다.\n" +
                "• 길찾기는 출발·도착 좌표만 경로 계산 서버로 전송하며, 이름·연락처 등 식별정보는 보내지 않습니다.\n" +
                "• 아래에서 언제든 저장된 데이터를 삭제하거나 동의를 철회할 수 있습니다.",
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedButton(
            onClick = { showClearDialog = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = "저장된 데이터 전체 삭제")
        }
        OutlinedButton(
            onClick = { showWithdrawDialog = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = "개인정보 동의 철회 (앱 초기화)")
        }
    }

    if (showClearDialog) {
        ConfirmDialog(
            title = "저장된 데이터 삭제",
            message = "이 기기에 저장된 즐겨찾기와 보행 프로필을 모두 삭제합니다. 되돌릴 수 없습니다.",
            confirmLabel = "삭제",
            onConfirm = {
                viewModel.clearAllData()
                showClearDialog = false
            },
            onDismiss = { showClearDialog = false },
        )
    }
    if (showWithdrawDialog) {
        ConfirmDialog(
            title = "동의 철회 및 초기화",
            message = "개인정보 동의를 철회하고 저장된 데이터를 모두 삭제합니다. 앱은 첫 화면(동의)으로 돌아갑니다.",
            confirmLabel = "철회",
            onConfirm = {
                viewModel.clearAllData()
                showWithdrawDialog = false
                onWithdrawConsent()
            },
            onDismiss = { showWithdrawDialog = false },
        )
    }
}

@Composable
private fun FavoriteRow(
    favorite: FavoriteRoute,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
) {
    Card {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClick = onOpen)
                    .padding(vertical = 10.dp),
            ) {
                Text(text = favorite.label, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = "탭하면 이 경로로 길찾기",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            TextButton(onClick = onRemove) { Text(text = "삭제") }
        }
    }
}

@Composable
private fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title) },
        text = { Text(text = message) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(text = confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(text = "취소") } },
    )
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
