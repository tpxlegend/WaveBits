// WaveBits Immersive Home Screen.
package com.music.spotui.ui.immersive.screens

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.music.spotui.ui.components.WaveBitsHomeFeedScreen
import com.music.spotui.ui.immersive.Immersive
import com.music.spotui.ui.immersive.shell.immersiveChromeScroll
import com.music.spotui.ui.immersive.shell.immersiveHazeSource
import com.music.spotui.ui.viewmodel.HomeViewModel

@Composable
fun ImmersiveHomeScreen(navController: NavController) {
    val homeViewModel: HomeViewModel = viewModel()
    WaveBitsHomeFeedScreen(
        navController = navController,
        homeViewModel = homeViewModel,
        bottomPadding = Immersive.BottomChrome,
        modifier = Modifier.fillMaxSize(),
    )
}
