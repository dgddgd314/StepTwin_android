package com.example.steptwin.ui

import android.content.Context
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.steptwin.R
import com.example.steptwin.ui.consent.ConsentScreen
import com.example.steptwin.ui.gait.TugMeasureScreen
import com.example.steptwin.ui.gait.TugMeasureViewModel
import com.example.steptwin.ui.map.MapRouteScreen
import com.example.steptwin.ui.map.MapRouteViewModel
import com.example.steptwin.ui.profile.ProfileScreen
import com.example.steptwin.ui.profile.ProfileViewModel

@Composable
fun StepTwinApp() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("steptwin_prefs", Context.MODE_PRIVATE) }
    var consented by remember { mutableStateOf(prefs.getBoolean("consent_given", false)) }
    if (!consented) {
        ConsentScreen(onAgree = {
            prefs.edit().putBoolean("consent_given", true).apply()
            consented = true
        })
        return
    }

    var currentDestination by rememberSaveable { mutableStateOf(AppDestination.GaitTest) }

    Scaffold(
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
            AppDestination.GaitTest -> {
                val viewModel: TugMeasureViewModel = hiltViewModel()
                TugMeasureScreen(
                    viewModel = viewModel,
                    modifier = contentModifier,
                )
            }

            AppDestination.Route -> {
                val viewModel: MapRouteViewModel = hiltViewModel()
                MapRouteScreen(
                    viewModel = viewModel,
                    modifier = contentModifier,
                )
            }

            AppDestination.Profile -> {
                val viewModel: ProfileViewModel = hiltViewModel()
                ProfileScreen(
                    viewModel = viewModel,
                    modifier = contentModifier,
                )
            }
        }
    }
}

private enum class AppDestination(
    val label: String,
    val icon: Int,
) {
    GaitTest("보행 검사", R.drawable.ic_home),
    Route("맞춤 길찾기", R.drawable.ic_favorite),
    Profile("내 보행정보", R.drawable.ic_account_box),
}
