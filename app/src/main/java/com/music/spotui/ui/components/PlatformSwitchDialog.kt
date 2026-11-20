package com.music.spotui.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.wavebits.host.android.BackendMode
import app.wavebits.host.android.WaveBitsRuntime
import com.music.spotui.di.SessionActions
import com.music.spotui.ui.platform.PlatformManager
import com.music.spotui.ui.screens.JioSaavnLogoIcon
import com.music.spotui.ui.screens.SpotifyLogoIcon
import com.music.spotui.ui.screens.YouTubeMusicLogoIcon
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private val PureBlack = Color(0xFF000000)
private val Paper = Color(0xFFF3F4EF)
private val Muted = Color(0xFFA8B0AC)
private val SpotifyGreen = Color(0xFF1ED760)
private val SpotifyDeepGreen = Color(0xFF149A43)
private val SpotifyMintHighlight = Color(0xFF80FFB0)
private val YoutubeRed = Color(0xFFFF0033)
private val YoutubeDeepRed = Color(0xFFB80024)
private val YoutubeCoralHighlight = Color(0xFFFF7A95)
private val JioSaavnTeal = Color(0xFF00D2A0)
private val JioSaavnDeepTeal = Color(0xFF007A5E)
private val JioSaavnMintHighlight = Color(0xFF80FFD8)
private val InitialSilkSilver = Color(0xFFE8ECEB)

private data class PlatformSilkPalette(
    val primary: Color,
    val deep: Color,
    val highlight: Color,
)

private fun BackendMode.silkPalette(): PlatformSilkPalette = when (this) {
    BackendMode.SPOTIFY -> PlatformSilkPalette(
        primary = SpotifyGreen,
        deep = SpotifyDeepGreen,
        highlight = SpotifyMintHighlight,
    )
    BackendMode.YOUTUBE -> PlatformSilkPalette(
        primary = YoutubeRed,
        deep = YoutubeDeepRed,
        highlight = YoutubeCoralHighlight,
    )
    BackendMode.JIOSAAVN -> PlatformSilkPalette(
        primary = JioSaavnTeal,
        deep = JioSaavnDeepTeal,
        highlight = JioSaavnMintHighlight,
    )
}

private fun BackendMode.accentColor(): Color = when (this) {
    BackendMode.SPOTIFY -> SpotifyGreen
    BackendMode.YOUTUBE -> YoutubeRed
    BackendMode.JIOSAAVN -> JioSaavnTeal
}

private fun BackendMode.displayName(): String = when (this) {
    BackendMode.SPOTIFY -> "Spotify"
    BackendMode.YOUTUBE -> "YouTube Music"
    BackendMode.JIOSAAVN -> "Jiosavan"
}

/**
 * Platform Switch Pop-up styled with the Welcome Screen's signature
 * Frosted Dark Glass Pill Buttons with platform logos, ambient radial glows,
 * and specular metallic silver-white rim borders.
 */
@Composable
fun PlatformSwitchDialog(
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val currentBackend by WaveBitsRuntime.backendMode.collectAsState()

    val spotifySaved = remember(currentBackend) {
        PlatformManager.isPlatformLoggedIn(context, BackendMode.SPOTIFY)
    }
    val youtubeSaved = remember(currentBackend) {
        PlatformManager.isPlatformLoggedIn(context, BackendMode.YOUTUBE)
    }

    var showJioSaavnSetup by remember { mutableStateOf(false) }
    if (showJioSaavnSetup) {
        JioSaavnProfileDialog(
            onDismiss = { showJioSaavnSetup = false },
            onSaved = {
                showJioSaavnSetup = false
                onDismiss()
            },
        )
    }

    val spotifySubtitle = remember(currentBackend, spotifySaved) {
        val name = PlatformManager.getSavedAccountName(context, BackendMode.SPOTIFY)
        when {
            currentBackend == BackendMode.SPOTIFY -> name?.let { "Connected as $it" } ?: "Active session"
            spotifySaved -> name?.let { "Saved • $it" } ?: "Saved session"
            else -> "Tap to sign in"
        }
    }

    val youtubeIsGuest = remember(currentBackend, youtubeSaved) {
        PlatformManager.isYouTubeGuest(context)
    }

    val youtubeSubtitle = remember(currentBackend, youtubeSaved, youtubeIsGuest) {
        val name = PlatformManager.getSavedAccountName(context, BackendMode.YOUTUBE)
        when {
            currentBackend == BackendMode.YOUTUBE && youtubeIsGuest -> "Connected as Google Guest"
            currentBackend == BackendMode.YOUTUBE -> name?.let { "Connected as $it" } ?: "Active session"
            youtubeSaved && youtubeIsGuest -> "Saved • Google Guest"
            youtubeSaved -> name?.let { "Saved • $it" } ?: "Saved session"
            else -> "Tap to launch"
        }
    }

    val jiosaavnSaved = remember(currentBackend) {
        PlatformManager.isPlatformLoggedIn(context, BackendMode.JIOSAAVN)
    }

    val jiosaavnSubtitle = remember(currentBackend, jiosaavnSaved) {
        val name = PlatformManager.getSavedAccountName(context, BackendMode.JIOSAAVN)
        when {
            currentBackend == BackendMode.JIOSAAVN -> name?.let { "Connected as $it" } ?: "Active session"
            jiosaavnSaved -> name?.let { "Saved • $it" } ?: "Saved session"
            else -> "Tap to launch"
        }
    }

    val entranceProgress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        entranceProgress.animateTo(
            targetValue = 1f,
            animationSpec = spring(
                dampingRatio = 0.80f,
                stiffness = Spring.StiffnessMediumLow,
            ),
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.80f * entranceProgress.value.coerceIn(0f, 1f)))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
            contentAlignment = Alignment.Center,
        ) {
            // Smoky Dark Frosted Glass Modal Container with Liquid-Silk Sheen
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .widthIn(max = 410.dp)
                    .graphicsLayer {
                        val p = entranceProgress.value
                        alpha = p.coerceIn(0f, 1f)
                        scaleX = 0.90f + 0.10f * p
                        scaleY = 0.90f + 0.10f * p
                        translationY = (1f - p) * 32f
                    }
                    .testTag("platform_switch_dialog")
                    .clip(RoundedCornerShape(34.dp))
                    .drawBehind {
                        val w = size.width
                        val h = size.height
                        val corner = CornerRadius(34.dp.toPx(), 34.dp.toPx())

                        // Deep smoky obsidian glass backdrop
                        drawRoundRect(
                            brush = Brush.linearGradient(
                                colors = listOf(
                                    Color(0xFF1F2427).copy(alpha = 0.94f),
                                    Color(0xFF0A0C0E).copy(alpha = 0.97f),
                                    Color(0xFF050608).copy(alpha = 0.98f),
                                    Color(0xFF171B1E).copy(alpha = 0.95f),
                                ),
                                start = Offset(0f, 0f),
                                end = Offset(w, h),
                            ),
                            cornerRadius = corner,
                        )

                        // Top-left silver silk ambient sheen
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    Color.White.copy(alpha = 0.12f),
                                    Color.Transparent,
                                ),
                                center = Offset(w * 0.12f, h * 0.08f),
                                radius = w * 0.65f,
                            ),
                            radius = w * 0.65f,
                            center = Offset(w * 0.12f, h * 0.08f),
                        )

                        // Bottom-right subtle metallic reflection
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    Color.White.copy(alpha = 0.08f),
                                    Color.Transparent,
                                ),
                                center = Offset(w * 0.88f, h * 0.92f),
                                radius = w * 0.55f,
                            ),
                            radius = w * 0.55f,
                            center = Offset(w * 0.88f, h * 0.92f),
                        )
                    }
                    .border(
                        width = 1.2.dp,
                        brush = Brush.linearGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.62f),
                                Color.White.copy(alpha = 0.18f),
                                Color.White.copy(alpha = 0.10f),
                                Color.White.copy(alpha = 0.45f),
                            ),
                            start = Offset(0f, 0f),
                            end = Offset(1000f, 1000f),
                        ),
                        shape = RoundedCornerShape(34.dp),
                    )
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    ),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 22.dp, vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // Header Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            Text(
                                text = "Switch Platform",
                                color = Paper,
                                fontWeight = FontWeight.Bold,
                                fontSize = 22.sp,
                                letterSpacing = (-0.5).sp,
                            )
                            Spacer(Modifier.height(3.dp))
                            Text(
                                text = "Music for every mood • ${currentBackend.displayName()} active",
                                color = Muted,
                                fontWeight = FontWeight.Normal,
                                fontSize = 12.5.sp,
                                letterSpacing = 0.3.sp,
                            )
                        }

                        // Frosted Dark Glass Close Circle
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .testTag("platform_switch_close_btn")
                                .pressable(0.90f, onClick = onDismiss)
                                .clip(CircleShape)
                                .background(
                                    Brush.linearGradient(
                                        colors = listOf(
                                            Color(0xFF2B3033).copy(alpha = 0.80f),
                                            Color(0xFF0E1012).copy(alpha = 0.92f),
                                        ),
                                    ),
                                )
                                .border(
                                    width = 1.dp,
                                    brush = Brush.linearGradient(
                                        colors = listOf(
                                            Color.White.copy(alpha = 0.65f),
                                            Color.White.copy(alpha = 0.18f),
                                        ),
                                    ),
                                    shape = CircleShape,
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "Close",
                                tint = Paper,
                                modifier = Modifier.size(17.dp),
                            )
                        }
                    }

                    Spacer(Modifier.height(22.dp))

                    // Three Frosted Dark Glass Pill Buttons with Platform Logos
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        FrostedGlassPlatformPillButton(
                            label = "Spotify",
                            subtitle = spotifySubtitle,
                            iconGlowColor = SpotifyGreen.copy(alpha = 0.32f),
                            accentColor = SpotifyGreen,
                            isActive = currentBackend == BackendMode.SPOTIFY,
                            isSaved = spotifySaved,
                            leadingIcon = {
                                SpotifyLogoIcon(size = 36.dp)
                            },
                            onClick = {
                                onDismiss()
                                if (currentBackend != BackendMode.SPOTIFY) {
                                    PlatformManager.switchToPlatform(context, BackendMode.SPOTIFY)
                                }
                            },
                            modifier = Modifier.testTag("platform_option_spotify"),
                        )

                        FrostedGlassPlatformPillButton(
                            label = "YouTube Music",
                            subtitle = youtubeSubtitle,
                            iconGlowColor = YoutubeRed.copy(alpha = 0.32f),
                            accentColor = YoutubeRed,
                            isActive = currentBackend == BackendMode.YOUTUBE,
                            isSaved = youtubeSaved,
                            leadingIcon = {
                                YouTubeMusicLogoIcon(size = 36.dp)
                            },
                            onClick = {
                                onDismiss()
                                if (currentBackend != BackendMode.YOUTUBE) {
                                    PlatformManager.switchToPlatform(context, BackendMode.YOUTUBE)
                                }
                            },
                            modifier = Modifier.testTag("platform_option_youtube"),
                        )

                        FrostedGlassPlatformPillButton(
                            label = "Jiosavan",
                            subtitle = jiosaavnSubtitle,
                            iconGlowColor = JioSaavnTeal.copy(alpha = 0.32f),
                            accentColor = JioSaavnTeal,
                            isActive = currentBackend == BackendMode.JIOSAAVN,
                            isSaved = jiosaavnSaved,
                            leadingIcon = {
                                JioSaavnLogoIcon(size = 36.dp)
                            },
                            onClick = {
                                if (currentBackend != BackendMode.JIOSAAVN) {
                                    PlatformManager.switchToPlatform(context, BackendMode.JIOSAAVN)
                                    onDismiss()
                                } else {
                                    showJioSaavnSetup = true
                                }
                            },
                            modifier = Modifier.testTag("platform_option_jiosaavn"),
                        )
                    }

                    Spacer(Modifier.height(18.dp))

                    // Subtle silver silk divider line
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(
                                Brush.horizontalGradient(
                                    colors = listOf(
                                        Color.Transparent,
                                        Color.White.copy(alpha = 0.16f),
                                        Color.Transparent,
                                    ),
                                ),
                            ),
                    )

                    Spacer(Modifier.height(16.dp))

                    // Bottom Frosted Dark Glass Pill Button: Log out of current platform
                    FrostedGlassSecondaryPillButton(
                        label = "Log out of ${currentBackend.displayName()}",
                        onClick = {
                            onDismiss()
                            SessionActions.logout(context)
                        },
                        modifier = Modifier.testTag("platform_switch_welcome_btn"),
                    )
                }
            }
        }
    }
}

@Composable
fun YouTubeAuthModeDialog(
    onDismiss: () -> Unit,
    onGoogleLogin: () -> Unit,
    onGuestLogin: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.82f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .widthIn(max = 400.dp)
                    .clip(RoundedCornerShape(32.dp))
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                Color(0xFF1F2427).copy(alpha = 0.96f),
                                Color(0xFF0A0C0E).copy(alpha = 0.98f),
                                Color(0xFF14181B).copy(alpha = 0.96f),
                            ),
                        ),
                    )
                    .border(
                        width = 1.2.dp,
                        brush = Brush.linearGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.62f),
                                Color.White.copy(alpha = 0.16f),
                                Color.White.copy(alpha = 0.42f),
                            ),
                        ),
                        shape = RoundedCornerShape(32.dp),
                    )
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    )
                    .padding(horizontal = 22.dp, vertical = 24.dp),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            YouTubeMusicLogoIcon(size = 32.dp)
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "YouTube Music",
                                    color = Paper,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 20.sp,
                                )
                                Text(
                                    text = "Choose how to continue",
                                    color = Muted,
                                    fontSize = 12.5.sp,
                                )
                            }
                        }
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.10f))
                                .clickable(onClick = onDismiss),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "Close",
                                tint = Paper,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }

                    Spacer(Modifier.height(22.dp))

                    FrostedGlassPlatformPillButton(
                        label = "Google Account Login",
                        subtitle = "Sync your library, playlists & history",
                        iconGlowColor = YoutubeRed.copy(alpha = 0.35f),
                        accentColor = YoutubeRed,
                        isActive = false,
                        isSaved = false,
                        leadingIcon = {
                            YouTubeMusicLogoIcon(size = 34.dp)
                        },
                        onClick = onGoogleLogin,
                        modifier = Modifier.testTag("yt_auth_google_login_btn"),
                    )

                    Spacer(Modifier.height(14.dp))

                    FrostedGlassPlatformPillButton(
                        label = "Google Guest Mode",
                        subtitle = "No login required • Instant YT Music Home",
                        iconGlowColor = Color.White.copy(alpha = 0.28f),
                        accentColor = Color.White,
                        isActive = true,
                        isSaved = false,
                        leadingIcon = {
                            YouTubeMusicLogoIcon(size = 34.dp)
                        },
                        onClick = onGuestLogin,
                        modifier = Modifier.testTag("yt_auth_guest_login_btn"),
                    )
                }
            }
        }
    }
}

/**
 * Frosted Dark Glass Pill Button with Logo matching the Welcome Screen's
 * pill capsule aesthetic (`RoundedCornerShape(50)`, smoky dark glass fill,
 * radial colored glow behind the left logo, and specular silver-white border).
 */
@Composable
private fun FrostedGlassPlatformPillButton(
    label: String,
    subtitle: String,
    iconGlowColor: Color,
    accentColor: Color,
    isActive: Boolean,
    isSaved: Boolean,
    leadingIcon: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.975f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "platformPillScale",
    )

    val pillShape = RoundedCornerShape(50)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(66.dp)
            .scale(scale)
            .clip(pillShape)
            .drawBehind {
                val w = size.width
                val h = size.height
                val corner = CornerRadius(h / 2f, h / 2f)

                // Base smoky dark glass fill identical to WelcomeScreen pill buttons
                drawRoundRect(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color(0xFF2B3033).copy(alpha = 0.80f),
                            Color(0xFF0E1012).copy(alpha = 0.90f),
                            Color(0xFF08090B).copy(alpha = 0.94f),
                            Color(0xFF1D2124).copy(alpha = 0.82f),
                        ),
                        start = Offset(0f, 0f),
                        end = Offset(w, h),
                    ),
                    cornerRadius = corner,
                )

                // Top-left frosted glass sheen
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.18f),
                            Color.Transparent,
                        ),
                        center = Offset(w * 0.10f, h * 0.15f),
                        radius = w * 0.42f,
                    ),
                    radius = w * 0.42f,
                    center = Offset(w * 0.10f, h * 0.15f),
                )

                // Colored ambient radial glow behind the left platform logo
                val iconCenter = Offset(44.dp.toPx(), h / 2f)
                val effectiveGlow = if (isActive) {
                    iconGlowColor.copy(alpha = (iconGlowColor.alpha * 1.25f).coerceAtMost(0.45f))
                } else {
                    iconGlowColor
                }
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            effectiveGlow,
                            effectiveGlow.copy(alpha = effectiveGlow.alpha * 0.35f),
                            Color.Transparent,
                        ),
                        center = iconCenter,
                        radius = h * 1.20f,
                    ),
                    radius = h * 1.20f,
                    center = iconCenter,
                )

                // Bottom-right subtle glass reflection
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.12f),
                            Color.Transparent,
                        ),
                        center = Offset(w * 0.92f, h * 0.85f),
                        radius = w * 0.35f,
                    ),
                    radius = w * 0.35f,
                    center = Offset(w * 0.92f, h * 0.85f),
                )
            }
            .border(
                width = if (isActive) 1.4.dp else 1.2.dp,
                brush = Brush.linearGradient(
                    colors = if (isActive) {
                        listOf(
                            Color.White.copy(alpha = 0.85f),
                            accentColor.copy(alpha = 0.55f),
                            Color.White.copy(alpha = 0.25f),
                            Color.White.copy(alpha = 0.70f),
                        )
                    } else {
                        listOf(
                            Color.White.copy(alpha = 0.72f),
                            Color.White.copy(alpha = 0.22f),
                            Color.White.copy(alpha = 0.14f),
                            Color.White.copy(alpha = 0.58f),
                        )
                    },
                    start = Offset(0f, 0f),
                    end = Offset(1000f, 300f),
                ),
                shape = pillShape,
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 22.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(38.dp),
                contentAlignment = Alignment.Center,
            ) {
                leadingIcon()
            }

            Spacer(modifier = Modifier.width(18.dp))

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = label,
                    color = Color(0xFFF3F5F4),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.2.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    color = if (isActive) Color(0xFFD5DDD9) else Color(0xFFA2ABA6),
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(modifier = Modifier.width(10.dp))

            if (isActive) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Color.White.copy(alpha = 0.14f))
                        .border(1.dp, Color.White.copy(alpha = 0.38f), RoundedCornerShape(50))
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = "Active",
                        tint = Paper,
                        modifier = Modifier.size(13.dp),
                    )
                    Text(
                        text = "Active",
                        color = Paper,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 11.sp,
                    )
                }
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = if (isSaved) "Switch" else "Login",
                        color = Color(0xFFD0D6D3),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = Color(0xFFD0D6D3),
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun FrostedGlassSecondaryPillButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.975f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "secondaryPillScale",
    )

    val pillShape = RoundedCornerShape(50)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
            .scale(scale)
            .clip(pillShape)
            .drawBehind {
                val w = size.width
                val h = size.height
                val corner = CornerRadius(h / 2f, h / 2f)
                drawRoundRect(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color(0xFF252A2D).copy(alpha = 0.72f),
                            Color(0xFF0D0F11).copy(alpha = 0.88f),
                            Color(0xFF1A1E21).copy(alpha = 0.75f),
                        ),
                        start = Offset(0f, 0f),
                        end = Offset(w, h),
                    ),
                    cornerRadius = corner,
                )
            }
            .border(
                width = 1.dp,
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.55f),
                        Color.White.copy(alpha = 0.16f),
                        Color.White.copy(alpha = 0.42f),
                    ),
                    start = Offset(0f, 0f),
                    end = Offset(800f, 200f),
                ),
                shape = pillShape,
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Logout,
                contentDescription = null,
                tint = Color(0xFFD0D6D3),
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = label,
                color = Color(0xFFE5EAE7),
                fontWeight = FontWeight.Medium,
                fontSize = 13.5.sp,
                letterSpacing = 0.2.sp,
            )
        }
    }
}

/**
 * Full-Screen Motion-Graphics Platform Switch & Silk Loading Screen Overlay:
 * - Reduced to 2 sleek, counter-flowing liquid-silk ribbons entering from opposite sides
 *   toward the center so the logo area stays clean and uncluttered.
 * - The 2 ribbons wrap into a single minimal silk ring around the platform logo while
 *   shifting color from silver-white into the target platform's signature color.
 * - From the logo's orbit, the silk ribbons unspool downward and weave horizontally to
 *   create a glowing Liquid-Silk Loading Screen with a live wave progress bar and status readout.
 */
@Composable
fun PlatformSwitchAnimationOverlay() {
    val switchingTarget by PlatformManager.switchingToPlatform.collectAsState()
    var activeTarget by remember { mutableStateOf<BackendMode?>(null) }
    var logoCenterInRoot by remember { mutableStateOf(Offset.Zero) }
    var loadingTrackRectInRoot by remember { mutableStateOf(Rect.Zero) }

    // 0f -> 1f as the 2 sleek ribbons travel from opposite screen edges into a single clean orbit
    val ribbonFlow = remember { Animatable(0f) }
    // 0f -> 1f as the ribbons change color from silver-white into the platform's signature color
    val colorMorph = remember { Animatable(0f) }
    // 0f -> 1f gentle orbital rotation around the platform logo
    val orbitSpin = remember { Animatable(0f) }
    // 0f -> 1f vector silk trace of the target platform emblem
    val logoTrace = remember { Animatable(0f) }
    // 0f -> 1f solid platform logo crystallization
    val logoSolidify = remember { Animatable(0f) }
    // 0f -> 1f subtle single radial color pulse when the logo locks in
    val impactBloom = remember { Animatable(0f) }
    // 0f -> 1f bold platform title reveal
    val textReveal = remember { Animatable(0f) }
    // 0f -> 1f final fade out once animation completes
    val exitProgress = remember { Animatable(0f) }

    val reduceMotion = com.music.spotui.ui.look.rememberReduceMotion()
    val continuousWave = if (reduceMotion) {
        0f
    } else {
        val infiniteTransition = rememberInfiniteTransition(label = "switchSilkWave")
        val wave by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = (2f * PI).toFloat(),
            animationSpec = infiniteRepeatable(
                animation = tween(1600, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "continuousWave",
        )
        wave
    }

    LaunchedEffect(switchingTarget) {
        val target = switchingTarget
        if (target != null) {
            activeTarget = target
            ribbonFlow.snapTo(0f)
            colorMorph.snapTo(0f)
            orbitSpin.snapTo(0f)
            logoTrace.snapTo(0f)
            logoSolidify.snapTo(0f)
            impactBloom.snapTo(0f)
            textReveal.snapTo(0f)
            exitProgress.snapTo(0f)

            // 1. Two slender silk ribbons glide in from Left & Right and form a single clean ring around the logo
            launch {
                ribbonFlow.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(920, easing = FastOutSlowInEasing),
                )
            }

            // 2. Smooth orbital revolution of the single silk ring
            launch {
                orbitSpin.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(2000, easing = LinearOutSlowInEasing),
                )
            }

            // 3. Silk ribbons shift from silver-white into the platform's signature color
            launch {
                delay(160)
                colorMorph.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(720, easing = FastOutSlowInEasing),
                )
            }

            // 4. Trace the platform emblem in glowing silk filaments at the center
            launch {
                delay(140)
                logoTrace.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(560, easing = FastOutSlowInEasing),
                )
            }

            // 5. Crystallize the platform logo & release a single clean radial pulse
            launch {
                delay(400)
                launch {
                    impactBloom.animateTo(
                        targetValue = 1f,
                        animationSpec = tween(680, easing = LinearOutSlowInEasing),
                    )
                }
                launch {
                    logoSolidify.animateTo(
                        targetValue = 1f,
                        animationSpec = tween(460, easing = FastOutSlowInEasing),
                    )
                }
            }

            // 6. Reveal bold platform name
            launch {
                delay(480)
                textReveal.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(460, easing = FastOutSlowInEasing),
                )
            }

            // Hold briefly for logo crystallization, then dissolve smoothly into the switched platform UI
            delay(1250)
            exitProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(320, easing = FastOutSlowInEasing),
            )
            activeTarget = null
            PlatformManager.clearSwitchingAnimation()
        }
    }

    val mode = activeTarget ?: return
    val palette = mode.silkPalette()
    val platformName = mode.displayName()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(PureBlack)
            .testTag("platform_switch_animation_overlay")
            .graphicsLayer {
                alpha = (1f - exitProgress.value).coerceIn(0f, 1f)
            },
        contentAlignment = Alignment.Center,
    ) {
        // 1. Full-screen Motion-Graphics Canvas:
        //    2 sleek liquid-silk ribbons enter from opposite sides and form a single clean orbit around the logo.
        PlatformSwitchSilkOrbitCanvas(
            flowProgress = ribbonFlow.value,
            colorMorph = colorMorph.value,
            orbitSpin = orbitSpin.value,
            bloom = impactBloom.value,
            wavePhase = continuousWave,
            palette = palette,
            measuredLogoCenter = logoCenterInRoot,
            modifier = Modifier.fillMaxSize(),
        )

        // 2. Centered Platform Logo & Title
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // Hero Platform Logo in the center of the screen, framed by a single clean silk orbit
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(156.dp)
                    .onGloballyPositioned { coords ->
                        val pos = coords.positionInRoot()
                        logoCenterInRoot = Offset(
                            x = pos.x + coords.size.width / 2f,
                            y = pos.y + coords.size.height / 2f,
                        )
                    },
            ) {
                // Soft ambient radial glow behind the logo that shifts from silver to platform color
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val s = logoSolidify.value
                    val cMorph = colorMorph.value
                    val activeGlowColor = lerp(InitialSilkSilver, palette.primary, cMorph)
                    val activeHighlight = lerp(Color.White, palette.highlight, cMorph)
                    if (s > 0.01f || ribbonFlow.value > 0.25f) {
                        val glowStrength = maxOf(s, (ribbonFlow.value - 0.25f) * 0.85f).coerceIn(0f, 1f)
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    activeHighlight.copy(alpha = 0.28f * glowStrength),
                                    activeGlowColor.copy(alpha = 0.20f * glowStrength),
                                    palette.deep.copy(alpha = 0.08f * glowStrength * cMorph),
                                    Color.Transparent,
                                ),
                                center = Offset(size.width / 2f, size.height / 2f),
                                radius = size.minDimension * 0.80f,
                            ),
                            radius = size.minDimension * 0.80f,
                        )
                    }
                }

                // Live vector silk filaments weaving the target platform emblem
                PlatformLogoSilkWeaveCanvas(
                    mode = mode,
                    traceProgress = logoTrace.value,
                    solidifyProgress = logoSolidify.value,
                    colorMorph = colorMorph.value,
                    palette = palette,
                    modifier = Modifier.size(108.dp),
                )

                // Crystallized Platform Logo
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(108.dp)
                        .graphicsLayer {
                            val s = logoSolidify.value
                            alpha = s
                            val scale = 0.84f + 0.16f * s
                            scaleX = scale
                            scaleY = scale
                        },
                ) {
                    when (mode) {
                        BackendMode.SPOTIFY -> SpotifyLogoIcon(size = 100.dp)
                        BackendMode.YOUTUBE -> YouTubeMusicLogoIcon(size = 100.dp)
                        BackendMode.JIOSAAVN -> JioSaavnLogoIcon(size = 100.dp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            // Bold Platform Name & Subtitle
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.graphicsLayer {
                    val t = textReveal.value
                    alpha = t
                    translationY = (1f - t) * 16f
                    val scale = 0.95f + 0.05f * t
                    scaleX = scale
                    scaleY = scale
                },
            ) {
                Text(
                    text = platformName,
                    color = Paper,
                    fontSize = 36.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.8).sp,
                    textAlign = TextAlign.Center,
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = "Music for every mood",
                    color = lerp(Muted, palette.highlight, colorMorph.value * 0.45f),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 2.4.sp,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

private data class InboundSilkSpec(
    val start: Offset,
    val ctrl1: Offset,
    val entryAngleRad: Float,
    val orbitRadiusFactor: Float,
    val ribbonWidthDp: Float,
    val orbitTurns: Float,
    val waveFrequency: Float,
    val phaseOffset: Float,
)

/**
 * Renders 2 sleek, counter-flowing liquid-silk ribbons entering from opposite sides (Left & Right),
 * wrapping into a single clean orbital ring around the platform logo, and then unspooling downward
 * to weave and power the Liquid-Silk Loading Screen track below the title.
 */
@Composable
private fun PlatformSwitchSilkOrbitCanvas(
    flowProgress: Float,
    colorMorph: Float,
    orbitSpin: Float,
    bloom: Float,
    wavePhase: Float,
    palette: PlatformSilkPalette,
    measuredLogoCenter: Offset,
    modifier: Modifier = Modifier,
) {
    val pathMeasure = remember { PathMeasure() }
    val segmentPath = remember { Path() }
    val fullRibbonPath = remember { Path() }
    val orbitRingPath = remember { Path() }

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas

        // Exact center of the platform logo in the middle of the screen
        val center = if (measuredLogoCenter != Offset.Zero) {
            measuredLogoCenter
        } else {
            Offset(w * 0.50f, h * 0.38f)
        }

        // Current morphed silk colors: transitions from pearlescent silver-white to platform color
        val morphedPrimary = lerp(InitialSilkSilver, palette.primary, colorMorph)
        val morphedDeep = lerp(Color(0xFF8A9490), palette.deep, colorMorph)
        val morphedHighlight = lerp(Color.White, palette.highlight, colorMorph)

        // 1. Clean ambient background silk glow behind the center
        val ambientRadius = size.minDimension * (0.44f + 0.06f * sin(wavePhase * 0.5f))
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    morphedPrimary.copy(alpha = 0.16f * flowProgress),
                    morphedDeep.copy(alpha = 0.07f * flowProgress),
                    Color.Transparent,
                ),
                center = center,
                radius = ambientRadius,
            ),
            radius = ambientRadius,
            center = center,
        )

        val baseOrbitRadius = size.minDimension * 0.175f
        val spinAngle = orbitSpin * (0.85f * PI).toFloat()
        val waveCos = cos(wavePhase) * h * 0.015f

        // 2 slender, graceful silk ribbons entering from Left & Right
        val ribbons = listOf(
            // 1. Left-side silk ribbon sweeping into the top arc of the logo
            InboundSilkSpec(
                start = Offset(-w * 0.14f, center.y - h * 0.08f),
                ctrl1 = Offset(w * 0.18f, center.y - h * 0.16f + waveCos),
                entryAngleRad = PI.toFloat(),
                orbitRadiusFactor = 1.0f,
                ribbonWidthDp = 18f,
                orbitTurns = 0.85f,
                waveFrequency = 2f,
                phaseOffset = 0.0f,
            ),
            // 2. Right-side silk ribbon sweeping into the bottom arc of the logo
            InboundSilkSpec(
                start = Offset(w * 1.14f, center.y + h * 0.08f),
                ctrl1 = Offset(w * 0.82f, center.y + h * 0.16f - waveCos),
                entryAngleRad = 0.0f,
                orbitRadiusFactor = 1.0f,
                ribbonWidthDp = 18f,
                orbitTurns = 0.85f,
                waveFrequency = 2f,
                phaseOffset = PI.toFloat(),
            ),
        )

        // 2. Draw the 2 converging silk ribbons wrapping into the orbital ring around the logo
        if (flowProgress > 0.01f) {
            ribbons.forEachIndexed { index, spec ->
                val stagger = index * 0.05f
                val localFlow = ((flowProgress - stagger) / (1f - stagger)).coerceIn(0f, 1f)
                if (localFlow > 0f) {
                    val rOrbit = baseOrbitRadius * spec.orbitRadiusFactor
                    val startOrbitAngle = spec.entryAngleRad + spinAngle * 0.45f
                    val orbitEntryPoint = Offset(
                        x = center.x + cos(startOrbitAngle) * rOrbit,
                        y = center.y + sin(startOrbitAngle) * rOrbit,
                    )
                    val tangentAngle = startOrbitAngle - (PI / 2.0).toFloat()
                    val tangentDist = rOrbit * 0.80f
                    val ctrl2 = Offset(
                        x = orbitEntryPoint.x + cos(tangentAngle) * tangentDist,
                        y = orbitEntryPoint.y + sin(tangentAngle) * tangentDist,
                    )

                    fullRibbonPath.reset()
                    fullRibbonPath.moveTo(spec.start.x, spec.start.y)
                    fullRibbonPath.cubicTo(
                        spec.ctrl1.x, spec.ctrl1.y,
                        ctrl2.x, ctrl2.y,
                        orbitEntryPoint.x, orbitEntryPoint.y,
                    )

                    val totalSweep = spec.orbitTurns * (2f * PI).toFloat()
                    val steps = 28
                    var prevPt = orbitEntryPoint
                    for (step in 1..steps) {
                        val frac = step.toFloat() / steps.toFloat()
                        val theta = startOrbitAngle + frac * totalSweep
                        val radialWave = sin(theta * spec.waveFrequency + wavePhase + spec.phaseOffset) *
                            (baseOrbitRadius * 0.05f)
                        val currentR = rOrbit + radialWave
                        val nextPt = Offset(
                            x = center.x + cos(theta) * currentR,
                            y = center.y + sin(theta) * currentR,
                        )
                        val midPt = Offset(
                            x = (prevPt.x + nextPt.x) * 0.5f,
                            y = (prevPt.y + nextPt.y) * 0.5f,
                        )
                        fullRibbonPath.quadraticTo(prevPt.x, prevPt.y, midPt.x, midPt.y)
                        prevPt = nextPt
                    }

                    pathMeasure.setPath(fullRibbonPath, false)
                    val totalLen = pathMeasure.length
                    val headFraction = localFlow.coerceIn(0f, 1f)
                    val tailFraction = ((localFlow - 0.25f) / 0.75f).coerceIn(0f, 1f) * 0.58f
                    val startDist = totalLen * tailFraction
                    val stopDist = totalLen * headFraction

                    if (stopDist > startDist + 2f) {
                        segmentPath.reset()
                        pathMeasure.getSegment(startDist, stopDist, segmentPath, true)
                        val headPos = pathMeasure.getPosition(stopDist)
                        val tailPos = pathMeasure.getPosition(startDist)

                        drawPlatformSilkRibbonSegment(
                            segmentPath = segmentPath,
                            tailPos = tailPos,
                            headPos = headPos,
                            ribbonWidthPx = spec.ribbonWidthDp.dp.toPx(),
                            primaryColor = morphedPrimary,
                            deepColor = morphedDeep,
                            highlightColor = morphedHighlight,
                            colorMorph = colorMorph,
                        )
                    }
                }
            }
        }

        // 3. Single minimal, calm silk loading ring around the platform logo
        if (flowProgress > 0.35f) {
            val haloStrength = ((flowProgress - 0.35f) / 0.65f).coerceIn(0f, 1f)
            orbitRingPath.reset()
            val points = 48
            for (i in 0..points) {
                val angle = (i.toFloat() / points.toFloat()) * (2f * PI).toFloat()
                val waveOffset = sin(angle * 2f + wavePhase + spinAngle) * (baseOrbitRadius * 0.035f)
                val r = baseOrbitRadius + waveOffset
                val px = center.x + cos(angle) * r
                val py = center.y + sin(angle) * r
                if (i == 0) orbitRingPath.moveTo(px, py) else orbitRingPath.lineTo(px, py)
            }
            orbitRingPath.close()

            val sweepBrush = Brush.sweepGradient(
                0.0f to morphedDeep.copy(alpha = 0.14f * haloStrength),
                0.35f to morphedPrimary.copy(alpha = 0.45f * haloStrength),
                0.70f to morphedHighlight.copy(alpha = 0.72f * haloStrength),
                1.0f to morphedDeep.copy(alpha = 0.14f * haloStrength),
                center = center,
            )

            drawPath(
                path = orbitRingPath,
                brush = sweepBrush,
                style = Stroke(
                    width = 6.dp.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                ),
            )
        }

        // 4. Single subtle radial pulse when the logo crystallizes
        if (bloom > 0.01f && bloom < 0.99f) {
            val ringAlpha = (1f - bloom) * 0.45f
            val ringRadius = baseOrbitRadius * (0.85f + bloom * 1.15f)
            drawCircle(
                color = morphedHighlight.copy(alpha = ringAlpha),
                radius = ringRadius,
                center = center,
                style = Stroke(width = (3.5f * (1f - bloom) + 1f).dp.toPx()),
            )
        }
    }
}

private fun DrawScope.drawPlatformSilkRibbonSegment(
    segmentPath: Path,
    tailPos: Offset,
    headPos: Offset,
    ribbonWidthPx: Float,
    primaryColor: Color,
    deepColor: Color,
    highlightColor: Color,
    colorMorph: Float,
) {
    // 1. Sleek outer liquid-silk veil that morphs into the platform color
    drawPath(
        path = segmentPath,
        brush = Brush.linearGradient(
            colors = listOf(
                Color.Transparent,
                deepColor.copy(alpha = 0.16f + 0.08f * colorMorph),
                primaryColor.copy(alpha = 0.34f + 0.14f * colorMorph),
            ),
            start = tailPos,
            end = headPos,
        ),
        style = Stroke(
            width = ribbonWidthPx,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
        ),
    )

    // 2. Mid-width satin ribbon core in the platform's signature color
    drawPath(
        path = segmentPath,
        brush = Brush.linearGradient(
            colors = listOf(
                Color.Transparent,
                primaryColor.copy(alpha = 0.32f + 0.20f * colorMorph),
                highlightColor.copy(alpha = 0.64f),
            ),
            start = tailPos,
            end = headPos,
        ),
        style = Stroke(
            width = ribbonWidthPx * 0.40f,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
        ),
    )

    // 3. Crisp specular silk filament edge
    drawPath(
        path = segmentPath,
        brush = Brush.linearGradient(
            colors = listOf(
                Color.Transparent,
                highlightColor.copy(alpha = 0.62f),
                Color.White.copy(alpha = 0.92f),
            ),
            start = tailPos,
            end = headPos,
        ),
        style = Stroke(
            width = 1.6.dp.toPx(),
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
        ),
    )

    // 4. Subtle luminous silk tip orb
    val tipRadius = ribbonWidthPx * 0.52f
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.88f),
                highlightColor.copy(alpha = 0.58f),
                primaryColor.copy(alpha = 0.35f),
                Color.Transparent,
            ),
            center = headPos,
            radius = tipRadius,
        ),
        radius = tipRadius,
        center = headPos,
    )
}

/**
 * Live vector silk filament tracing of the target platform's emblem
 * (Spotify waves or YouTube Music play emblem)
 * that shifts color into the platform color before the solid emblem locks in.
 */
@Composable
private fun PlatformLogoSilkWeaveCanvas(
    mode: BackendMode,
    traceProgress: Float,
    solidifyProgress: Float,
    colorMorph: Float,
    palette: PlatformSilkPalette,
    modifier: Modifier = Modifier,
) {
    val pathMeasure = remember { PathMeasure() }
    val ringSegment = remember { Path() }
    val innerSegment = remember { Path() }
    val ringPath = remember { Path() }
    val innerPath = remember { Path() }

    Canvas(modifier = modifier) {
        if (traceProgress <= 0.01f) return@Canvas
        val w = size.width
        val h = size.height
        val weaveAlpha = (1f - solidifyProgress * 0.85f).coerceIn(0f, 1f)
        if (weaveAlpha <= 0.01f) return@Canvas

        val activeColor = lerp(InitialSilkSilver, palette.primary, colorMorph)
        val activeHighlight = lerp(Color.White, palette.highlight, colorMorph)

        ringPath.reset()
        ringPath.addOval(Rect(w * 0.06f, h * 0.06f, w * 0.94f, h * 0.94f))

        innerPath.reset()
        when (mode) {
            BackendMode.SPOTIFY -> {
                innerPath.moveTo(w * 0.24f, h * 0.36f)
                innerPath.quadraticTo(w * 0.50f, h * 0.26f, w * 0.76f, h * 0.40f)
                innerPath.moveTo(w * 0.28f, h * 0.51f)
                innerPath.quadraticTo(w * 0.50f, h * 0.43f, w * 0.72f, h * 0.55f)
                innerPath.moveTo(w * 0.32f, h * 0.65f)
                innerPath.quadraticTo(w * 0.50f, h * 0.59f, w * 0.68f, h * 0.69f)
            }
            BackendMode.YOUTUBE -> {
                innerPath.addOval(Rect(w * 0.24f, h * 0.24f, w * 0.76f, h * 0.76f))
                innerPath.moveTo(w * 0.42f, h * 0.36f)
                innerPath.lineTo(w * 0.64f, h * 0.50f)
                innerPath.lineTo(w * 0.42f, h * 0.64f)
                innerPath.close()
            }
            BackendMode.JIOSAAVN -> {
                innerPath.moveTo(w * 0.28f, h * 0.50f)
                innerPath.cubicTo(w * 0.36f, h * 0.30f, w * 0.64f, h * 0.30f, w * 0.72f, h * 0.50f)
                innerPath.moveTo(w * 0.35f, h * 0.62f)
                innerPath.cubicTo(w * 0.42f, h * 0.48f, w * 0.58f, h * 0.48f, w * 0.65f, h * 0.62f)
                innerPath.addOval(Rect(w * 0.45f, h * 0.68f, w * 0.55f, h * 0.78f))
            }
        }

        pathMeasure.setPath(ringPath, false)
        val rLen = pathMeasure.length
        val rStop = rLen * traceProgress.coerceIn(0f, 1f)
        ringSegment.reset()
        pathMeasure.getSegment(0f, rStop, ringSegment, true)
        val rTip = pathMeasure.getPosition(rStop)

        pathMeasure.setPath(innerPath, false)
        val iLen = pathMeasure.length
        val iStop = iLen * traceProgress.coerceIn(0f, 1f)
        innerSegment.reset()
        pathMeasure.getSegment(0f, iStop, innerSegment, true)
        val iTip = pathMeasure.getPosition(iStop)

        val mainStroke = w * 0.065f

        drawPath(
            path = ringSegment,
            color = activeColor.copy(alpha = 0.52f * weaveAlpha),
            style = Stroke(width = mainStroke * 1.55f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
        drawPath(
            path = ringSegment,
            color = activeHighlight.copy(alpha = 0.94f * weaveAlpha),
            style = Stroke(width = mainStroke * 0.75f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )

        drawPath(
            path = innerSegment,
            color = activeHighlight.copy(alpha = 0.94f * weaveAlpha),
            style = Stroke(width = mainStroke * 0.85f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )

        if (traceProgress < 0.98f) {
            val sparkR = mainStroke * 1.6f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.95f * weaveAlpha),
                        activeColor.copy(alpha = 0.55f * weaveAlpha),
                        Color.Transparent,
                    ),
                    center = rTip,
                    radius = sparkR,
                ),
                radius = sparkR,
                center = rTip,
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.95f * weaveAlpha),
                        activeColor.copy(alpha = 0.55f * weaveAlpha),
                        Color.Transparent,
                    ),
                    center = iTip,
                    radius = sparkR,
                ),
                radius = sparkR,
                center = iTip,
            )
        }
    }
}
