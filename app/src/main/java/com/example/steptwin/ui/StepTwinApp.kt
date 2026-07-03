package com.example.steptwin.ui

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.steptwin.R
import com.example.steptwin.ui.consent.ConsentScreen
import com.example.steptwin.ui.gait.TugMeasureScreen
import com.example.steptwin.ui.gait.TugMeasureViewModel
import com.example.steptwin.ui.home.HomeScreen
import com.example.steptwin.ui.map.MapRouteScreen
import com.example.steptwin.ui.map.MapRouteViewModel
import com.example.steptwin.ui.profile.ProfileScreen
import com.example.steptwin.ui.profile.ProfileViewModel

/** 큰글씨 모드에서 적용할 글꼴 배율. */
private const val LargeFontScale = 1.3f

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StepTwinApp() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("steptwin_prefs", Context.MODE_PRIVATE) }
    var largeFont by remember { mutableStateOf(prefs.getBoolean("large_font", false)) }
    var consented by remember { mutableStateOf(prefs.getBoolean("consent_given", false)) }

    val onToggleLargeFont = {
        largeFont = !largeFont
        prefs.edit().putBoolean("large_font", largeFont).apply()
    }

    // 큰글씨 모드: 글꼴 배율만 키운다(레이아웃 dp 는 유지, 텍스트만 확대).
    val baseDensity = LocalDensity.current
    val density = if (largeFont) {
        Density(baseDensity.density, baseDensity.fontScale * LargeFontScale)
    } else {
        baseDensity
    }

    CompositionLocalProvider(LocalDensity provides density) {
        if (!consented) {
            ConsentScreen(
                largeFont = largeFont,
                onToggleLargeFont = onToggleLargeFont,
                onAgree = {
                    prefs.edit().putBoolean("consent_given", true).apply()
                    consented = true
                },
            )
            return@CompositionLocalProvider
        }

        var currentDestination by rememberSaveable { mutableStateOf(AppDestination.Home) }
        // 맵/길찾기 VM 은 상위에서 한 번만 만들어(액티비티 스코프) 홈의 '전화 걸기'가 음성 목적지
        // 입력을 트리거할 수 있게 공유한다.
        val mapViewModel: MapRouteViewModel = hiltViewModel()

        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Image(
                                painter = painterResource(R.drawable.ic_launcher_logo),
                                contentDescription = "마중벗 로고",
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(text = "마중벗")
                        }
                    },
                    actions = {
                        LargeFontToggle(enabled = largeFont, onToggle = onToggleLargeFont)
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        actionIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
                )
            },
            bottomBar = {
                NavigationBar {
                    AppDestination.entries.forEach { destination ->
                        NavigationBarItem(
                            selected = destination == currentDestination,
                            onClick = { currentDestination = destination },
                            icon = {
                                Icon(
                                    painter = painterResource(destination.icon),
                                    contentDescription = destination.label,
                                )
                            },
                            label = { Text(destination.label) },
                        )
                    }
                }
            },
        ) { innerPadding ->
            val contentModifier = Modifier.padding(innerPadding)
            when (currentDestination) {
                AppDestination.Home -> {
                    HomeScreen(
                        onOpenMap = { currentDestination = AppDestination.Route },
                        onCall = {
                            // 전화 대신 '말벗 대화'로 이동해 목적지를 말로 받아 길찾기한다.
                            mapViewModel.startVoiceDestination()
                            currentDestination = AppDestination.Route
                        },
                        modifier = contentModifier,
                    )
                }

                AppDestination.GaitTest -> {
                    val viewModel: TugMeasureViewModel = hiltViewModel()
                    TugMeasureScreen(viewModel = viewModel, modifier = contentModifier)
                }

                AppDestination.Route -> {
                    MapRouteScreen(viewModel = mapViewModel, modifier = contentModifier)
                }

                AppDestination.Profile -> {
                    val viewModel: ProfileViewModel = hiltViewModel()
                    ProfileScreen(
                        viewModel = viewModel,
                        onOpenRoute = { currentDestination = AppDestination.Route },
                        onWithdrawConsent = {
                            prefs.edit().putBoolean("consent_given", false).apply()
                            consented = false
                        },
                        modifier = contentModifier,
                    )
                }
            }
        }
    }
}

/** 큰글씨 켜기/끄기 토글. 노인·저시력 사용자를 위한 접근성 버튼. */
@Composable
fun LargeFontToggle(
    enabled: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FilterChip(
        selected = enabled,
        onClick = onToggle,
        label = { Text(text = if (enabled) "큰글씨 끄기" else "큰글씨 켜기") },
        leadingIcon = { Text(text = "가", style = MaterialTheme.typography.titleMedium) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primary,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
            selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimary,
        ),
        modifier = modifier.padding(end = 12.dp),
    )
}

private enum class AppDestination(
    val label: String,
    val icon: Int,
) {
    Home("홈", R.drawable.ic_home),
    Route("맞춤 길찾기", R.drawable.ic_favorite),
    Profile("내 보행정보", R.drawable.ic_account_box),
    GaitTest("보행 검사", R.drawable.ic_diagnosis),
}
