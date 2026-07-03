package com.example.steptwin.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 홈 화면 — 어르신이 가장 먼저 만나는 큰 두 버튼: 지도 보기 / 전화 걸기. */
@Composable
fun HomeScreen(
    onOpenMap: () -> Unit,
    onCall: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "무엇을 도와드릴까요?",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(32.dp))

        BigHomeButton(
            emoji = "🗺️",
            label = "지도 보기",
            onClick = onOpenMap,
            container = MaterialTheme.colorScheme.primary,
            content = MaterialTheme.colorScheme.onPrimary,
        )
        Spacer(Modifier.height(20.dp))
        BigHomeButton(
            emoji = "📞",
            label = "전화 걸기",
            onClick = onCall,
            container = MaterialTheme.colorScheme.secondary,
            content = MaterialTheme.colorScheme.onSecondary,
        )
    }
}

@Composable
private fun BigHomeButton(
    emoji: String,
    label: String,
    onClick: () -> Unit,
    container: androidx.compose.ui.graphics.Color,
    content: androidx.compose.ui.graphics.Color,
) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(24.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = container,
            contentColor = content,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .height(140.dp),
    ) {
        Text(text = emoji, fontSize = 48.sp)
        Spacer(Modifier.width(16.dp))
        Text(
            text = label,
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}
