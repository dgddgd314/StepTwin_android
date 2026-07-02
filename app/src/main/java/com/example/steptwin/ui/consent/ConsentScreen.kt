package com.example.steptwin.ui.consent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.steptwin.ui.LargeFontToggle

/** 최초 실행 시 개인정보 처리 고지 및 동의 화면(윤리 준수). */
@Composable
fun ConsentScreen(
    largeFont: Boolean,
    onToggleLargeFont: () -> Unit,
    onAgree: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            LargeFontToggle(enabled = largeFont, onToggle = onToggleLargeFont)
        }
        Text(
            text = "개인정보 처리 안내 및 동의",
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = "STEP-Twin은 보행 검사와 맞춤 길찾기 제공을 위해 아래 정보를 처리합니다.",
            style = MaterialTheme.typography.bodyMedium,
        )

        ConsentItem(
            title = "① 센서 데이터 (가속도·자이로)",
            body = "보행 취약도 계산에만 사용하며, 원신호는 기기 내(온디바이스)에서 처리됩니다.",
        )
        ConsentItem(
            title = "② 측정 결과 (취약도 벡터)",
            body = "경로 개인화를 위해 서버로 전송될 수 있습니다. 원본 센서 신호는 전송하지 않습니다.",
        )
        ConsentItem(
            title = "③ 장소 검색어 · 좌표",
            body = "지도 표시와 경로 검색(카카오 지도/로컬 API)에 사용됩니다.",
        )
        ConsentItem(
            title = "④ 의료 목적 아님",
            body = "본 앱의 결과(취약도·낙상위험 등급)는 참고용이며 의료 진단이 아닙니다.",
        )

        Text(
            text = "동의하면 위 목적 범위 내에서 데이터가 처리됩니다. 동의는 설정에서 언제든 철회 정보를 확인할 수 있습니다.",
            style = MaterialTheme.typography.bodySmall,
        )

        Button(onClick = onAgree) {
            Text(text = "동의하고 시작하기")
        }
    }
}

@Composable
private fun ConsentItem(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(text = title, style = MaterialTheme.typography.titleSmall)
        Text(text = body, style = MaterialTheme.typography.bodySmall)
    }
}
