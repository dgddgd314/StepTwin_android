package com.example.steptwin.ui.home

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
            subtitle = "지도에서 길을 찾아요",
            onClick = onOpenMap,
            accent = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(20.dp))
        BigHomeButton(
            emoji = "📞",
            label = "전화 걸기",
            subtitle = "말로 목적지를 말해요",
            onClick = onCall,
            accent = MaterialTheme.colorScheme.secondary,
        )
    }
}

/** 흰 배경 + 브랜드 컬러 테두리/아이콘의 깔끔한 큰 버튼. */
@Composable
private fun BigHomeButton(
    emoji: String,
    label: String,
    subtitle: String,
    onClick: () -> Unit,
    accent: Color,
) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(2.5.dp, accent),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(128.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = emoji, fontSize = 38.sp)
            }
            Spacer(Modifier.width(20.dp))
            Column {
                Text(
                    text = label,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = accent,
                )
                Text(
                    text = subtitle,
                    fontSize = 15.sp,
                    color = Color(0xFF666666),
                )
            }
        }
    }
}
