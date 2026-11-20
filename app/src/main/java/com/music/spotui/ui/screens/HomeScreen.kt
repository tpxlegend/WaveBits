// WaveBits Home Screen, GPL-3.0.
package com.music.spotui.ui.screens

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.music.spotui.data.entity.HomeFeedModel
import com.music.spotui.ui.components.WaveBitsHomeFeedScreen
import com.music.spotui.ui.components.bottomBarsSpace
import com.music.spotui.ui.viewmodel.HomeViewModel

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun HomeScreen(navController: NavController) {
    val homeViewModel: HomeViewModel = viewModel()
    WaveBitsHomeFeedScreen(
        navController = navController,
        homeViewModel = homeViewModel,
        bottomPadding = bottomBarsSpace(),
        modifier = Modifier.fillMaxSize(),
    )
}

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun HomeFeedContent(
    navController: NavController,
    feed: HomeFeedModel,
    facet: String = "",
    onFacet: (String) -> Unit = {},
) {
    val homeViewModel: HomeViewModel = viewModel()
    WaveBitsHomeFeedScreen(
        navController = navController,
        homeViewModel = homeViewModel,
        bottomPadding = bottomBarsSpace(),
        modifier = Modifier.fillMaxSize(),
    )
}
