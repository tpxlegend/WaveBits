package com.music.spotui.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.wavebits.host.android.BackendMode
import app.wavebits.host.android.WaveBitsRuntime
import com.music.spotui.ui.screens.JioSaavnLogoIcon
import com.music.spotui.ui.screens.SpotifyLogoIcon
import com.music.spotui.ui.screens.YouTubeMusicLogoIcon

/**
 * A dedicated platform switch button displayed beside the profile icon on the homepage.
 * Tapping it opens the platform selector dialog to switch between Spotify, YouTube Music, and JioSaavn.
 */
@Composable
fun PlatformSwitchButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 34.dp,
) {
    val backendMode by WaveBitsRuntime.backendMode.collectAsStateWithLifecycle()
    val isJio = backendMode == BackendMode.JIOSAAVN
    val isYt = backendMode == BackendMode.YOUTUBE
    val brandColor = Color.White

    val platformName = when {
        isJio -> "JioSaavn"
        isYt -> "YouTube Music"
        else -> "Spotify"
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(brandColor.copy(alpha = 0.16f))
            .border(1.2.dp, brandColor.copy(alpha = 0.5f), CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(bounded = true, color = brandColor),
                onClick = onClick,
            )
            .semantics {
                contentDescription = "Switch music platform, current is $platformName"
                role = Role.Button
            },
    ) {
        val iconSize = (size.value * 0.65f).dp
        when {
            isJio -> JioSaavnLogoIcon(size = iconSize)
            isYt -> YouTubeMusicLogoIcon(size = iconSize)
            else -> SpotifyLogoIcon(size = iconSize)
        }
    }
}
