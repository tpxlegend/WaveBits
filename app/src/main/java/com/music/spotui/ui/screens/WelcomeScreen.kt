package com.music.spotui.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.wavebits.engine.R
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import com.bumptech.glide.integration.compose.placeholder
import com.music.spotui.ui.components.pressable
import com.music.spotui.ui.look.ImmersiveDisplay
import com.music.spotui.ui.look.ImmersiveSans
import com.music.spotui.ui.theme.AppAccent
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private val PureBlack = Color(0xFF000000)
private val Panel = Color(0xFF121516)
private val Muted = Color(0xFFA7B0AA)
private val Paper = Color(0xFFF3F4EF)
private val SpotifyGreen = Color(0xFF1ED760)
private val YoutubeRed = Color(0xFFFF0033)
private val JioSaavnTeal = Color(0xFF00D2A0)

@Composable
fun WelcomeScreen(
    onLogin: () -> Unit,
    onYouTubeLogin: () -> Unit = {},
    onYouTubeGuestLogin: () -> Unit = {},
    onJioSaavnLogin: () -> Unit = {},
) {
    val context = androidx.compose.ui.platform.LocalContext.current

    // Transparent-black color matrix so the off-white WB emblem floats cleanly over the silk waves
    val logoAlphaMatrix = remember {
        ColorMatrix(
            floatArrayOf(
                1f, 0f, 0f, 0f, 0f,
                0f, 1f, 0f, 0f, 0f,
                0f, 0f, 1f, 0f, 0f,
                0.38f, 0.38f, 0.38f, 0f, -14f,
            ),
        )
    }

    var showJioSaavnSetup by remember { mutableStateOf(false) }
    if (showJioSaavnSetup) {
        com.music.spotui.ui.components.JioSaavnProfileDialog(
            onDismiss = { showJioSaavnSetup = false },
            onSaved = {
                showJioSaavnSetup = false
                onJioSaavnLogin()
            },
        )
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            background = PureBlack,
            surface = Panel,
            primary = Paper,
            onPrimary = PureBlack,
            onBackground = Paper,
            onSurface = Paper,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(PureBlack),
        ) {
            // Monochrome liquid-silk ribbon waves matching the mockup
            LiquidSilkWavesBackground(modifier = Modifier.fillMaxSize())

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(horizontal = 28.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(modifier = Modifier.weight(0.95f))

                // Hero WB Monogram + WaveBits Title + Subtitle
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.wavebits_logo),
                        contentDescription = "WaveBits logo",
                        colorFilter = ColorFilter.colorMatrix(logoAlphaMatrix),
                        modifier = Modifier.size(138.dp),
                        contentScale = ContentScale.Fit,
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    WaveBitsWordmark(
                        tint = Paper,
                        modifier = Modifier
                            .width(244.dp)
                            .height(54.dp)
                            .testTag("wavebits_wordmark"),
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = "Music for every mood",
                        color = Color(0xFFA8B0AC),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Normal,
                        letterSpacing = 3.1.sp,
                        textAlign = TextAlign.Center,
                    )
                }

                Spacer(modifier = Modifier.weight(0.75f))

                // Center Tagline
                Text(
                    text = "Listen, discover, and feel\nthe rhythm of your world.",
                    color = Color(0xFFABB3AF),
                    fontSize = 15.sp,
                    lineHeight = 23.sp,
                    fontWeight = FontWeight.Normal,
                    letterSpacing = 0.25.sp,
                    textAlign = TextAlign.Center,
                )

                Spacer(modifier = Modifier.weight(0.90f))

                // Three Pill-Shaped Frosted Glass Action Capsules
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 400.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    GlassAuthPillButton(
                        label = "Login with Spotify",
                        iconGlowColor = SpotifyGreen.copy(alpha = 0.30f),
                        leadingIcon = {
                            SpotifyLogoIcon(size = 36.dp)
                        },
                        onClick = onLogin,
                        modifier = Modifier.testTag("spotify_login_button"),
                    )

                    GlassAuthPillButton(
                        label = "Listen now with YT Music",
                        iconGlowColor = YoutubeRed.copy(alpha = 0.30f),
                        leadingIcon = {
                            YouTubeMusicLogoIcon(size = 36.dp)
                        },
                        onClick = {
                            com.music.spotui.ui.platform.PlatformManager.saveYouTubeGuestSession(context, "Google Guest")
                            onYouTubeGuestLogin()
                        },
                        modifier = Modifier.testTag("youtube_login_button"),
                    )

                    GlassAuthPillButton(
                        label = "Jiosavan | No Login",
                        iconGlowColor = JioSaavnTeal.copy(alpha = 0.35f),
                        leadingIcon = {
                            JioSaavnLogoIcon(size = 36.dp)
                        },
                        onClick = { showJioSaavnSetup = true },
                        modifier = Modifier.testTag("welcome_jiosavan_button"),
                    )
                }

                Spacer(modifier = Modifier.height(28.dp))
            }
        }
    }
}

/**
 * Monochrome 3D liquid-silk wave ribbons sweeping across the top-left
 * and from the middle-right down across the lower-left behind the buttons.
 */
@Composable
internal fun LiquidSilkWavesBackground(
    modifier: Modifier = Modifier,
    intensity: Float = 1f,
) {
    val drift = if (com.music.spotui.ui.look.rememberReduceMotion()) {
        0f
    } else {
        val transition = rememberInfiniteTransition(label = "silkWaves")
        val animated by transition.animateFloat(
            initialValue = 0f,
            targetValue = (2f * PI).toFloat(),
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 14000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "silkDrift",
        )
        animated
    }

    Canvas(modifier = modifier.graphicsLayer { alpha = intensity.coerceIn(0f, 1f) }) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas
        val dy = sin(drift) * h * 0.008f
        val dx = cos(drift) * w * 0.008f

        // -----------------------------------------------------------------
        // 1. TOP-LEFT LIQUID SILK RIBBON
        // -----------------------------------------------------------------
        val topSilkFill = Path().apply {
            moveTo(0f, 0f)
            lineTo(w * 0.42f + dx, 0f)
            cubicTo(
                w * 0.39f + dx, h * 0.10f + dy,
                w * 0.24f - dx, h * 0.18f + dy,
                -w * 0.05f, h * 0.28f + dy,
            )
            close()
        }
        drawPath(
            path = topSilkFill,
            brush = Brush.linearGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.04f),
                    Color.White.copy(alpha = 0.14f),
                    Color.White.copy(alpha = 0.22f),
                ),
                start = Offset(w * 0.05f, 0f),
                end = Offset(w * 0.30f, h * 0.22f),
            ),
        )

        // Secondary folded silk crease at top-left
        val topInnerCrease = Path().apply {
            moveTo(-w * 0.04f, h * 0.17f + dy)
            cubicTo(
                w * 0.14f, h * 0.19f + dy,
                w * 0.31f + dx, h * 0.10f,
                w * 0.40f + dx, -h * 0.02f,
            )
        }
        drawPath(
            path = topInnerCrease,
            brush = Brush.linearGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.35f),
                    Color.White.copy(alpha = 0.65f),
                    Color.White.copy(alpha = 0.20f),
                ),
                start = Offset(0f, h * 0.18f),
                end = Offset(w * 0.40f, 0f),
            ),
            style = Stroke(width = 7.dp.toPx(), cap = StrokeCap.Round),
        )
        drawPath(
            path = topInnerCrease,
            brush = Brush.linearGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.55f),
                    Color.White.copy(alpha = 0.90f),
                    Color.White.copy(alpha = 0.35f),
                ),
                start = Offset(0f, h * 0.18f),
                end = Offset(w * 0.40f, 0f),
            ),
            style = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round),
        )

        // Lower sweeping veil from top-left edge
        val topLowerSweep = Path().apply {
            moveTo(-w * 0.05f, h * 0.28f + dy)
            cubicTo(
                w * 0.12f, h * 0.24f + dy,
                w * 0.27f + dx, h * 0.14f,
                w * 0.43f + dx, -h * 0.01f,
            )
        }
        drawPath(
            path = topLowerSweep,
            brush = Brush.linearGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.22f),
                    Color.White.copy(alpha = 0.38f),
                    Color.White.copy(alpha = 0.08f),
                ),
                start = Offset(0f, h * 0.28f),
                end = Offset(w * 0.42f, 0f),
            ),
            style = Stroke(width = 14.dp.toPx(), cap = StrokeCap.Round),
        )
        drawPath(
            path = topLowerSweep,
            brush = Brush.linearGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.45f),
                    Color.White.copy(alpha = 0.72f),
                    Color.White.copy(alpha = 0.15f),
                ),
                start = Offset(0f, h * 0.28f),
                end = Offset(w * 0.42f, 0f),
            ),
            style = Stroke(width = 1.4.dp.toPx(), cap = StrokeCap.Round),
        )

        // -----------------------------------------------------------------
        // 2. RIGHT-TO-BOTTOM-LEFT SWEEPING LIQUID SILK WAVE
        // -----------------------------------------------------------------
        val mainWaveBody = Path().apply {
            moveTo(w * 1.05f, h * 0.28f - dy)
            cubicTo(
                w * 0.80f - dx, h * 0.36f,
                w * 0.75f + dx, h * 0.56f + dy,
                w * 0.48f, h * 0.62f + dy,
            )
            cubicTo(
                w * 0.26f, h * 0.66f + dy,
                w * 0.12f - dx, h * 0.59f,
                -w * 0.08f, h * 0.60f - dy,
            )
            lineTo(-w * 0.08f, h * 0.74f)
            cubicTo(
                w * 0.22f, h * 0.70f,
                w * 0.68f, h * 0.78f + dy,
                w * 0.94f, h * 0.70f,
            )
            cubicTo(
                w * 1.05f, h * 0.64f,
                w * 1.08f, h * 0.48f,
                w * 1.05f, h * 0.28f - dy,
            )
            close()
        }
        drawPath(
            path = mainWaveBody,
            brush = Brush.linearGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.16f),
                    Color.White.copy(alpha = 0.07f),
                    Color.White.copy(alpha = 0.02f),
                ),
                start = Offset(w * 0.88f, h * 0.34f),
                end = Offset(w * 0.20f, h * 0.72f),
            ),
        )

        // Secondary sculptural silk fold on the right side (looping from x=1.0, y=0.43)
        val rightFoldFill = Path().apply {
            moveTo(w * 1.04f, h * 0.43f + dy)
            cubicTo(
                w * 0.84f, h * 0.41f + dy,
                w * 0.70f - dx, h * 0.53f,
                w * 0.56f, h * 0.60f + dy,
            )
            cubicTo(
                w * 0.78f, h * 0.62f,
                w * 0.96f, h * 0.56f,
                w * 1.04f, h * 0.43f + dy,
            )
            close()
        }
        drawPath(
            path = rightFoldFill,
            brush = Brush.linearGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.22f),
                    Color.White.copy(alpha = 0.05f),
                ),
                start = Offset(w * 0.95f, h * 0.42f),
                end = Offset(w * 0.60f, h * 0.60f),
            ),
        )

        // Primary upper ridge from right (y=0.28) sweeping down to left (y=0.60)
        val mainUpperRidge = Path().apply {
            moveTo(w * 1.05f, h * 0.28f - dy)
            cubicTo(
                w * 0.80f - dx, h * 0.36f,
                w * 0.75f + dx, h * 0.56f + dy,
                w * 0.48f, h * 0.62f + dy,
            )
            cubicTo(
                w * 0.26f, h * 0.66f + dy,
                w * 0.12f - dx, h * 0.59f,
                -w * 0.08f, h * 0.60f - dy,
            )
        }
        // Soft glow halo on main ridge
        drawPath(
            path = mainUpperRidge,
            brush = Brush.linearGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.30f),
                    Color.White.copy(alpha = 0.42f),
                    Color.White.copy(alpha = 0.35f),
                ),
                start = Offset(w, h * 0.30f),
                end = Offset(0f, h * 0.61f),
            ),
            style = Stroke(width = 10.dp.toPx(), cap = StrokeCap.Round),
        )
        // Sharp specular silver line on main ridge
        drawPath(
            path = mainUpperRidge,
            brush = Brush.linearGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.78f),
                    Color.White.copy(alpha = 0.92f),
                    Color.White.copy(alpha = 0.70f),
                ),
                start = Offset(w, h * 0.30f),
                end = Offset(0f, h * 0.61f),
            ),
            style = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round),
        )

        // Secondary arch ridge on middle-right (y=0.43 -> y=0.62)
        val rightArchRidge = Path().apply {
            moveTo(w * 1.04f, h * 0.43f + dy)
            cubicTo(
                w * 0.84f, h * 0.41f + dy,
                w * 0.72f - dx, h * 0.53f,
                w * 0.42f, h * 0.63f + dy,
            )
        }
        drawPath(
            path = rightArchRidge,
            brush = Brush.linearGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.35f),
                    Color.White.copy(alpha = 0.25f),
                    Color.Transparent,
                ),
                start = Offset(w, h * 0.43f),
                end = Offset(w * 0.42f, h * 0.63f),
            ),
            style = Stroke(width = 7.dp.toPx(), cap = StrokeCap.Round),
        )
        drawPath(
            path = rightArchRidge,
            brush = Brush.linearGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.85f),
                    Color.White.copy(alpha = 0.55f),
                    Color.Transparent,
                ),
                start = Offset(w, h * 0.43f),
                end = Offset(w * 0.42f, h * 0.63f),
            ),
            style = Stroke(width = 1.4.dp.toPx(), cap = StrokeCap.Round),
        )

        // Subtle lower silk contour behind the Spotify button
        val lowerSilkContour = Path().apply {
            moveTo(-w * 0.05f, h * 0.61f - dy)
            cubicTo(
                w * 0.14f, h * 0.62f,
                w * 0.25f, h * 0.71f + dy,
                w * 0.55f, h * 0.72f + dy,
            )
            cubicTo(
                w * 0.78f, h * 0.73f,
                w * 0.92f, h * 0.73f,
                w * 1.05f, h * 0.66f,
            )
        }
        drawPath(
            path = lowerSilkContour,
            brush = Brush.linearGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.40f),
                    Color.White.copy(alpha = 0.14f),
                    Color.White.copy(alpha = 0.22f),
                ),
                start = Offset(0f, h * 0.61f),
                end = Offset(w, h * 0.68f),
            ),
            style = Stroke(width = 1.2.dp.toPx(), cap = StrokeCap.Round),
        )
    }
}

/**
 * Pill-shaped dark frosted-glass capsule button matching the mockup:
 * - Full-width rounded pill (`RoundedCornerShape(50)`), height `64.dp`
 * - Dark translucent glass gradient fill with subtle left icon radial glow
 * - Specular metallic silver/white border brighter at top-left and bottom-right
 * - Left icon, label text, and right chevron (`>`)
 */
@Composable
private fun GlassAuthPillButton(
    label: String,
    iconGlowColor: Color,
    leadingIcon: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.975f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "pillScale",
    )

    val pillShape = RoundedCornerShape(50)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp)
            .scale(scale)
            .clip(pillShape)
            .drawBehind {
                val w = size.width
                val h = size.height
                val corner = CornerRadius(h / 2f, h / 2f)

                // Base smoky dark glass fill
                drawRoundRect(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color(0xFF2B3033).copy(alpha = 0.76f),
                            Color(0xFF0E1012).copy(alpha = 0.88f),
                            Color(0xFF08090B).copy(alpha = 0.92f),
                            Color(0xFF1D2124).copy(alpha = 0.78f),
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
                            Color.White.copy(alpha = 0.16f),
                            Color.Transparent,
                        ),
                        center = Offset(w * 0.10f, h * 0.15f),
                        radius = w * 0.42f,
                    ),
                    radius = w * 0.42f,
                    center = Offset(w * 0.10f, h * 0.15f),
                )

                // Colored ambient radial glow behind the left icon
                val iconCenter = Offset(44.dp.toPx(), h / 2f)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            iconGlowColor,
                            iconGlowColor.copy(alpha = iconGlowColor.alpha * 0.35f),
                            Color.Transparent,
                        ),
                        center = iconCenter,
                        radius = h * 1.15f,
                    ),
                    radius = h * 1.15f,
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
                width = 1.2.dp,
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.72f),
                        Color.White.copy(alpha = 0.22f),
                        Color.White.copy(alpha = 0.14f),
                        Color.White.copy(alpha = 0.58f),
                    ),
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
            .padding(horizontal = 24.dp),
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

            Spacer(modifier = Modifier.width(22.dp))

            Text(
                text = label,
                color = Color(0xFFF3F5F4),
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.2.sp,
            )

            Spacer(modifier = Modifier.weight(1f))

            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = Color(0xFFD0D6D3),
                modifier = Modifier.size(22.dp),
            )
        }
    }
}



/**
 * Official Spotify vector logo rendered on a Compose Canvas:
 * Vibrant #1ED760 circle with three curved acoustic wave arcs.
 */
@Composable
fun SpotifyLogoIcon(
    size: Dp = 28.dp,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        if (w <= 0f || h <= 0f) return@Canvas
        val radius = w / 2f

        drawCircle(
            brush = Brush.linearGradient(
                colors = listOf(
                    Color(0xFF24E063),
                    Color(0xFF1DB954),
                ),
                start = Offset(0f, 0f),
                end = Offset(w, h),
            ),
            radius = radius,
            center = Offset(radius, radius),
        )

        val waveColor = Color(0xFF07090E)

        val topWave = Path().apply {
            moveTo(w * 0.22f, h * 0.36f)
            quadraticTo(w * 0.50f, h * 0.26f, w * 0.78f, h * 0.40f)
        }
        drawPath(
            path = topWave,
            color = waveColor,
            style = Stroke(width = w * 0.088f, cap = StrokeCap.Round),
        )

        val midWave = Path().apply {
            moveTo(w * 0.26f, h * 0.51f)
            quadraticTo(w * 0.50f, h * 0.43f, w * 0.74f, h * 0.55f)
        }
        drawPath(
            path = midWave,
            color = waveColor,
            style = Stroke(width = w * 0.076f, cap = StrokeCap.Round),
        )

        val botWave = Path().apply {
            moveTo(w * 0.30f, h * 0.65f)
            quadraticTo(w * 0.50f, h * 0.59f, w * 0.69f, h * 0.69f)
        }
        drawPath(
            path = botWave,
            color = waveColor,
            style = Stroke(width = w * 0.066f, cap = StrokeCap.Round),
        )
    }
}

/**
 * Official YouTube Music vector logo rendered on a Compose Canvas:
 * Vibrant #FF0033 circle, inner white ring, and centered outlined/filled white play triangle.
 */
@Composable
fun YouTubeMusicLogoIcon(
    size: Dp = 28.dp,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        if (w <= 0f || h <= 0f) return@Canvas
        val center = Offset(w / 2f, h / 2f)
        val radius = w / 2f

        drawCircle(
            brush = Brush.linearGradient(
                colors = listOf(
                    Color(0xFFFF1F4B),
                    Color(0xFFE6002B),
                ),
                start = Offset(0f, 0f),
                end = Offset(w, h),
            ),
            radius = radius,
            center = center,
        )

        drawCircle(
            color = Color.White,
            radius = radius * 0.58f,
            center = center,
            style = Stroke(width = w * 0.055f),
        )

        val playPath = Path().apply {
            moveTo(w * 0.42f, h * 0.36f)
            lineTo(w * 0.64f, h * 0.50f)
            lineTo(w * 0.42f, h * 0.64f)
            close()
        }
        drawPath(
            path = playPath,
            color = Color.White,
            style = Stroke(
                width = w * 0.052f,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            ),
        )
    }
}

/**
 * Official JioSaavn logo from https://share.google/Q59r9jLcxm08TzhUW bundled as R.drawable.jiosaavn_logo.
 */
@Composable
fun JioSaavnLogoIcon(
    size: Dp = 28.dp,
    modifier: Modifier = Modifier,
) {
    Image(
        painter = painterResource(id = R.drawable.jiosaavn_logo),
        contentDescription = "JioSaavn Logo",
        contentScale = ContentScale.Crop,
        modifier = modifier
            .size(size)
            .clip(CircleShape),
    )
}



@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun AvatarItem(
    url: String,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val animatedScale by animateFloatAsState(
        targetValue = if (isSelected) 1.12f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow,
        ),
        label = "avatarScale",
    )
    val animatedBorderWidth by animateDpAsState(
        targetValue = if (isSelected) 2.5.dp else 1.dp,
        animationSpec = tween(200),
        label = "avatarBorder",
    )
    val animatedBorderColor by animateColorAsState(
        targetValue = if (isSelected) Paper else Color(0xFF454D48),
        animationSpec = tween(200),
        label = "avatarColor",
    )

    Box(
        modifier = Modifier
            .size(52.dp)
            .graphicsLayer {
                scaleX = animatedScale
                scaleY = animatedScale
            }
            .clip(CircleShape)
            .border(
                width = animatedBorderWidth,
                color = animatedBorderColor,
                shape = CircleShape,
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        GlideImage(
            model = url,
            contentDescription = "Avatar Option",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        AnimatedVisibility(
            visible = isSelected,
            enter = scaleIn() + fadeIn(),
            exit = scaleOut() + fadeOut(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.35f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = "Selected",
                    tint = Paper,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/** A soft green light behind the logo, drifting slowly. Used by SetupScreen and legacy screens. */
@Composable
internal fun Glow(modifier: Modifier = Modifier, alpha: Float = 0.30f) {
    val phase = if (com.music.spotui.ui.look.rememberReduceMotion()) {
        0f
    } else {
        val t = rememberInfiniteTransition(label = "glow")
        val animated by t.animateFloat(0f, (2 * PI).toFloat(), infiniteRepeatable(tween(16_000, easing = LinearEasing)), label = "phase")
        animated
    }
    Canvas(modifier) {
        if (size.minDimension <= 0f) return@Canvas
        val c = Offset(size.width * (0.5f + 0.12f * sin(phase)), size.height * (0.30f + 0.05f * cos(phase)))
        val r = (size.width * 1.05f).coerceAtLeast(1f)
        drawCircle(Brush.radialGradient(listOf(AppAccent.copy(alpha = alpha), Color.Transparent), c, r), r, c)
    }
}

/**
 * Custom geometric "WaveBits" typography wordmark matching the uploaded brand typography:
 * - Bold rounded-corner geometric 'W' and 'v'
 * - Rotational-twin futuristic rounded 'a' and 'e' with inward horizontal crossbars
 * - Wide geometric uppercase 'B' with pill counters
 * - Bold 'i' with circular dot, hooked 't' with crossbar, and geometric 's'
 */
@Composable
fun WaveBitsWordmark(
    modifier: Modifier = Modifier,
    tint: Color = Color(0xFFF3F4EF),
) {
    val fillPaint = remember {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.FILL
        }
    }
    val roundedPolygonPaint = remember {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.FILL
        }
    }
    val strokePaint = remember {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
            strokeCap = android.graphics.Paint.Cap.ROUND
            strokeJoin = android.graphics.Paint.Join.ROUND
        }
    }
    val workPath = remember { android.graphics.Path() }

    Canvas(
        modifier = modifier.semantics {
            contentDescription = "WaveBits"
        },
    ) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas

        // Design coordinate space: 1000 x 220
        val sx = w / 1000f
        val sy = h / 220f
        val s = minOf(sx, sy)
        val offsetX = (w - 1000f * s) * 0.5f
        val offsetY = (h - 220f * s) * 0.5f

        fun px(x: Float): Float = offsetX + x * s
        fun py(y: Float): Float = offsetY + y * s

        val argb = tint.toArgb()
        fillPaint.color = argb
        roundedPolygonPaint.color = argb
        roundedPolygonPaint.pathEffect = android.graphics.CornerPathEffect(9.5f * s)
        strokePaint.color = argb

        val native = drawContext.canvas.nativeCanvas

        // 1. 'W' (x: 8..244, y: 34..196) — wide geometric W with rounded vertices & flat-cut top ends
        workPath.reset()
        workPath.moveTo(px(8f), py(34f))
        workPath.lineTo(px(48f), py(34f))
        workPath.lineTo(px(83f), py(132f))
        workPath.lineTo(px(114f), py(74f))
        workPath.lineTo(px(140f), py(74f))
        workPath.lineTo(px(171f), py(132f))
        workPath.lineTo(px(206f), py(34f))
        workPath.lineTo(px(246f), py(34f))
        workPath.lineTo(px(190f), py(196f))
        workPath.lineTo(px(158f), py(196f))
        workPath.lineTo(px(127f), py(138f))
        workPath.lineTo(px(96f), py(196f))
        workPath.lineTo(px(64f), py(196f))
        workPath.close()
        native.drawPath(workPath, roundedPolygonPaint)

        // 2. 'a' (x: 226..330, y: 84..196) — futuristic geometric 'a'
        strokePaint.strokeWidth = 30f * s
        strokePaint.pathEffect = android.graphics.CornerPathEffect(26f * s)
        workPath.reset()
        workPath.moveTo(px(250f), py(99f))
        workPath.lineTo(px(314f), py(99f))
        workPath.lineTo(px(314f), py(181f))
        workPath.lineTo(px(240f), py(181f))
        workPath.lineTo(px(240f), py(141f))
        workPath.lineTo(px(292f), py(141f))
        native.drawPath(workPath, strokePaint)

        // 3. 'v' (x: 334..468, y: 84..196) — wide geometric 'v' matching 'W'
        workPath.reset()
        workPath.moveTo(px(334f), py(84f))
        workPath.lineTo(px(372f), py(84f))
        workPath.lineTo(px(401f), py(148f))
        workPath.lineTo(px(430f), py(84f))
        workPath.lineTo(px(468f), py(84f))
        workPath.lineTo(px(417f), py(196f))
        workPath.lineTo(px(385f), py(196f))
        workPath.close()
        native.drawPath(workPath, roundedPolygonPaint)

        // 4. 'e' (x: 466..570, y: 84..196) — exact 180-degree rotational sibling of 'a'
        workPath.reset()
        workPath.moveTo(px(554f), py(181f))
        workPath.lineTo(px(480f), py(181f))
        workPath.lineTo(px(480f), py(99f))
        workPath.lineTo(px(554f), py(99f))
        workPath.lineTo(px(554f), py(139f))
        workPath.lineTo(px(502f), py(139f))
        native.drawPath(workPath, strokePaint)

        // 5. 'B' (x: 586..732, y: 34..196) — wide geometric uppercase 'B' with pill counters
        workPath.reset()
        workPath.fillType = android.graphics.Path.FillType.EVEN_ODD
        // Outer contour of B
        workPath.addRoundRect(
            px(586f), py(34f), px(724f), py(122f),
            floatArrayOf(
                10f * s, 10f * s,
                44f * s, 44f * s,
                44f * s, 44f * s,
                10f * s, 10f * s,
            ),
            android.graphics.Path.Direction.CW,
        )
        workPath.op(
            android.graphics.Path().apply {
                addRoundRect(
                    px(586f), py(104f), px(732f), py(196f),
                    floatArrayOf(
                        10f * s, 10f * s,
                        46f * s, 46f * s,
                        46f * s, 46f * s,
                        10f * s, 10f * s,
                    ),
                    android.graphics.Path.Direction.CW,
                )
            },
            android.graphics.Path.Op.UNION,
        )
        // Subtract upper and lower inner pill counters
        val countersPath = android.graphics.Path().apply {
            addRoundRect(
                px(622f), py(63f), px(688f), py(99f),
                18f * s, 18f * s,
                android.graphics.Path.Direction.CW,
            )
            addRoundRect(
                px(622f), py(129f), px(694f), py(167f),
                19f * s, 19f * s,
                android.graphics.Path.Direction.CW,
            )
        }
        workPath.op(countersPath, android.graphics.Path.Op.DIFFERENCE)
        native.drawPath(workPath, fillPaint)

        // 6. 'i' (x: 744..780) — circular dot + rounded vertical stem
        native.drawCircle(px(762f), py(50f), 20.5f * s, fillPaint)
        native.drawRoundRect(
            px(744f), py(84f), px(780f), py(196f),
            8.5f * s, 8.5f * s,
            fillPaint,
        )

        // 7. 't' (x: 782..868, y: 34..196) — tall hooked stem + horizontal crossbar
        workPath.reset()
        workPath.moveTo(px(792f), py(34f))
        workPath.lineTo(px(828f), py(34f))
        workPath.lineTo(px(828f), py(84f))
        workPath.lineTo(px(866f), py(84f))
        workPath.lineTo(px(866f), py(114f))
        workPath.lineTo(px(828f), py(114f))
        workPath.lineTo(px(828f), py(166f))
        workPath.lineTo(px(868f), py(166f))
        workPath.lineTo(px(868f), py(196f))
        workPath.lineTo(px(792f), py(196f))
        workPath.close()
        native.drawPath(workPath, roundedPolygonPaint)

        // 8. 's' (x: 878..988, y: 84..196) — sleek geometric rounded 's'
        strokePaint.strokeWidth = 30f * s
        strokePaint.pathEffect = android.graphics.CornerPathEffect(25f * s)
        workPath.reset()
        workPath.moveTo(px(972f), py(99f))
        workPath.lineTo(px(892f), py(99f))
        workPath.lineTo(px(892f), py(140f))
        workPath.lineTo(px(974f), py(140f))
        workPath.lineTo(px(974f), py(181f))
        workPath.lineTo(px(890f), py(181f))
        native.drawPath(workPath, strokePaint)
    }
}

