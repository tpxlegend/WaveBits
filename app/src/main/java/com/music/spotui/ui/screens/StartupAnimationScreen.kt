package com.music.spotui.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.wavebits.engine.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private val PureBlack = Color(0xFF000000)
private val Paper = Color(0xFFF3F4EF)
private val Muted = Color(0xFFA8B0AC)
private val SpotifyGreen = Color(0xFF1ED760)
private val YoutubeRed = Color(0xFFFF0033)

/**
 * Full Motion-Graphics Video Starting Page:
 *
 * Act I   (0.0s - 1.1s): 6 translucent 3D liquid-silk ribbons stream in from every edge of the
 *                        screen (top-left, top-right, mid-left, mid-right, bottom-left, bottom-right),
 *                        spiraling and converging toward the hero emblem center.
 * Act II  (0.6s - 1.9s): The converging silk streams weave and trace the WB monogram strokes in real
 *                        time with glowing silver leading tips and a volumetric silk shockwave flare,
 *                        crystallizing into the crisp off-white WB emblem while the background silk
 *                        ribbons unfurl into their final positions.
 * Act III (1.4s - 2.8s): A silver light-slit reveals "WaveBits", "Music for every mood", the editorial
 *                        tagline, and the bottom frosted dark-glass equalizer capsule.
 * Act IV  (2.8s - 3.2s): Seamless dissolve into the Welcome Page.
 */
@Composable
fun StartupAnimationScreen(
    onFinished: () -> Unit,
) {
    // Master choreographed timeline animatables
    val ribbonInflow = remember { Animatable(0f) }       // 0 -> 1: Ribbons rush in from all 6 sides
    val ribbonAbsorb = remember { Animatable(0f) }       // 0 -> 1: Ribbons condense into the logo after tracing
    val logoTrace = remember { Animatable(0f) }          // 0 -> 1: Silk filaments trace the WB vector path
    val logoSolidify = remember { Animatable(0f) }       // 0 -> 1: Solid WB emblem crystallizes from the silk trace
    val impactBloom = remember { Animatable(0f) }        // 0 -> 1: Luminous silver silk shockwave on fusion
    val ambientSilkReveal = remember { Animatable(0f) }  // 0 -> 1: Shared WelcomeScreen silk waves unfurl
    val textReveal = remember { Animatable(0f) }         // 0 -> 1: "WaveBits" & subtitle motion-graphics reveal
    val exitProgress = remember { Animatable(0f) }       // 0 -> 1: Final seamless handoff

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

    val infiniteTransition = rememberInfiniteTransition(label = "motionGraphicsWave")
    val continuousWave by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "continuousWave",
    )

    LaunchedEffect(Unit) {
        // ACT I: Liquid-silk ribbons stream in from every side of the screen toward logo center
        launch {
            ribbonInflow.animateTo(
                targetValue = 1f,
                animationSpec = tween(1050, easing = FastOutSlowInEasing),
            )
        }

        // Start tracing the WB monogram as the ribbons reach the center
        launch {
            delay(480)
            logoTrace.animateTo(
                targetValue = 1f,
                animationSpec = tween(1050, easing = FastOutSlowInEasing),
            )
        }

        // Shockwave bloom + crystallizing the solid WB emblem + unfurling the shared silk waves
        launch {
            delay(1080)
            launch {
                impactBloom.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(820, easing = LinearOutSlowInEasing),
                )
            }
            launch {
                logoSolidify.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(620, easing = FastOutSlowInEasing),
                )
            }
            launch {
                ribbonAbsorb.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(680, easing = FastOutSlowInEasing),
                )
            }
            launch {
                ambientSilkReveal.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(900, easing = FastOutSlowInEasing),
                )
            }
        }

        // ACT III: Reveal "WaveBits", subtitle, and tagline
        launch {
            delay(1320)
            textReveal.animateTo(
                targetValue = 1f,
                animationSpec = tween(680, easing = FastOutSlowInEasing),
            )
        }

        // Hold for a beat so the composition breathes, then dissolve cleanly
        delay(2650)
        exitProgress.animateTo(
            targetValue = 1f,
            animationSpec = tween(260, easing = FastOutSlowInEasing),
        )
        onFinished()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(PureBlack)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onFinished,
            )
            .testTag("startup_animation_screen")
            .graphicsLayer {
                alpha = (1f - exitProgress.value).coerceIn(0f, 1f)
            },
    ) {
        // 1. Shared background liquid-silk ribbons that unfurl as the converging streams settle
        LiquidSilkWavesBackground(
            modifier = Modifier.fillMaxSize(),
            intensity = ambientSilkReveal.value,
        )

        // 2. Full-screen Motion-Graphics Canvas:
        //    Draws the 6 converging 3D liquid-silk ribbons from every side + shockwave rings
        MotionGraphicsSilkConvergenceCanvas(
            inflow = ribbonInflow.value,
            absorb = ribbonAbsorb.value,
            bloom = impactBloom.value,
            wavePhase = continuousWave,
            modifier = Modifier.fillMaxSize(),
        )

        // 3. Foreground Layout aligned 1:1 with WelcomeScreen
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 28.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(modifier = Modifier.weight(0.95f))

            // Hero WB Monogram formed by the converging liquid-silk ribbons
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(138.dp),
                ) {
                    // Live vector silk filaments weaving the WB monogram
                    WBMonogramSilkWeaveCanvas(
                        traceProgress = logoTrace.value,
                        solidifyProgress = logoSolidify.value,
                        modifier = Modifier.fillMaxSize(),
                    )

                    // High-res WB emblem crystallizing smoothly out of the silk weave
                    Image(
                        painter = painterResource(id = R.drawable.wavebits_logo),
                        contentDescription = "WaveBits logo",
                        colorFilter = ColorFilter.colorMatrix(logoAlphaMatrix),
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                val s = logoSolidify.value
                                alpha = s
                                val scale = 0.90f + 0.10f * s
                                scaleX = scale
                                scaleY = scale
                            },
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Motion-graphics typography reveal with custom WaveBits wordmark
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.graphicsLayer {
                        val t = textReveal.value
                        alpha = t
                        translationY = (1f - t) * 22f
                        val scale = 0.94f + 0.06f * t
                        scaleX = scale
                        scaleY = scale
                    },
                ) {
                    WaveBitsWordmark(
                        tint = Paper,
                        modifier = Modifier
                            .width(244.dp)
                            .height(54.dp)
                            .testTag("startup_wavebits_wordmark"),
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = "Music for every mood",
                        color = Muted,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Normal,
                        letterSpacing = 3.1.sp,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            Spacer(modifier = Modifier.weight(0.75f))

            // Center Editorial Copy matching WelcomeScreen
            Text(
                text = "Listen, discover, and feel\nthe rhythm of your world.",
                color = Color(0xFFABB3AF),
                fontSize = 15.sp,
                lineHeight = 23.sp,
                fontWeight = FontWeight.Normal,
                letterSpacing = 0.25.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.graphicsLayer {
                    val t = textReveal.value
                    alpha = (t * 1.15f - 0.15f).coerceIn(0f, 1f)
                    translationY = (1f - t) * 14f
                },
            )

            Spacer(modifier = Modifier.weight(0.90f))

            // Preserve vertical alignment with WelcomeScreen's bottom action area
            Spacer(modifier = Modifier.height(252.dp))
        }
    }
}

/**
 * Full-screen Motion-Graphics Canvas:
 * 6 translucent 3D liquid-silk ribbons enter from every side of the screen
 * (Top-Left, Top-Right, Mid-Left, Mid-Right, Bottom-Left, Bottom-Right),
 * curving in sweeping S-curves and converging into the WB logo center.
 */
@Composable
private fun MotionGraphicsSilkConvergenceCanvas(
    inflow: Float,
    absorb: Float,
    bloom: Float,
    wavePhase: Float,
    modifier: Modifier = Modifier,
) {
    val pathMeasure = remember { PathMeasure() }
    val segmentPath = remember { Path() }
    val ribbonPath = remember { Path() }

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas

        // Exact center of the WB monogram on screen
        val target = Offset(w * 0.50f, h * 0.255f)
        val wave = sin(wavePhase) * w * 0.025f
        val waveCos = cos(wavePhase) * h * 0.018f

        // 6 Silk Ribbon trajectories arriving from all 6 edges/corners of the screen
        val streams = listOf(
            // 1. Top-Left sweeping silk ribbon
            SilkStreamSpec(
                start = Offset(-w * 0.12f, -h * 0.04f),
                ctrl1 = Offset(w * 0.08f, h * 0.22f + waveCos),
                ctrl2 = Offset(w * 0.28f + wave, h * 0.06f),
                end = Offset(target.x - w * 0.06f, target.y - h * 0.01f),
                ribbonWidthDp = 38f,
                accentColor = Color.White,
            ),
            // 2. Top-Right sweeping silk ribbon
            SilkStreamSpec(
                start = Offset(w * 1.12f, -h * 0.04f),
                ctrl1 = Offset(w * 0.92f, h * 0.20f - waveCos),
                ctrl2 = Offset(w * 0.72f - wave, h * 0.07f),
                end = Offset(target.x + w * 0.06f, target.y - h * 0.01f),
                ribbonWidthDp = 36f,
                accentColor = Color.White,
            ),
            // 3. Mid-Left dramatic S-curve silk ribbon
            SilkStreamSpec(
                start = Offset(-w * 0.15f, h * 0.48f),
                ctrl1 = Offset(w * 0.12f, h * 0.34f - waveCos),
                ctrl2 = Offset(w * 0.22f + wave, h * 0.42f),
                end = Offset(target.x - w * 0.09f, target.y + h * 0.015f),
                ribbonWidthDp = 44f,
                accentColor = Color(0xFFE2F5EA),
            ),
            // 4. Mid-Right dramatic S-curve silk ribbon
            SilkStreamSpec(
                start = Offset(w * 1.15f, h * 0.48f),
                ctrl1 = Offset(w * 0.88f, h * 0.34f + waveCos),
                ctrl2 = Offset(w * 0.78f - wave, h * 0.42f),
                end = Offset(target.x + w * 0.09f, target.y + h * 0.015f),
                ribbonWidthDp = 44f,
                accentColor = Color(0xFFF7E4E8),
            ),
            // 5. Bottom-Left rising silk ribbon
            SilkStreamSpec(
                start = Offset(-w * 0.10f, h * 0.92f),
                ctrl1 = Offset(w * 0.16f + wave, h * 0.68f),
                ctrl2 = Offset(w * 0.28f, h * 0.44f + waveCos),
                end = Offset(target.x - w * 0.03f, target.y + h * 0.03f),
                ribbonWidthDp = 34f,
                accentColor = Color.White,
            ),
            // 6. Bottom-Right rising silk ribbon
            SilkStreamSpec(
                start = Offset(w * 1.10f, h * 0.92f),
                ctrl1 = Offset(w * 0.84f - wave, h * 0.68f),
                ctrl2 = Offset(w * 0.72f, h * 0.44f - waveCos),
                end = Offset(target.x + w * 0.03f, target.y + h * 0.03f),
                ribbonWidthDp = 34f,
                accentColor = Color.White,
            ),
        )

        val visibilityAlpha = (1f - absorb * 0.88f).coerceIn(0f, 1f)

        if (inflow > 0.01f && visibilityAlpha > 0.01f) {
            streams.forEachIndexed { index, spec ->
                // Stagger each ribbon slightly for organic cinema feel
                val stagger = index * 0.045f
                val localInflow = ((inflow - stagger) / (1f - stagger)).coerceIn(0f, 1f)
                if (localInflow > 0f) {
                    ribbonPath.reset()
                    ribbonPath.moveTo(spec.start.x, spec.start.y)
                    ribbonPath.cubicTo(
                        spec.ctrl1.x, spec.ctrl1.y,
                        spec.ctrl2.x, spec.ctrl2.y,
                        spec.end.x, spec.end.y,
                    )

                    pathMeasure.setPath(ribbonPath, false)
                    val length = pathMeasure.length
                    // Tail pulls in toward the center as absorb progresses
                    val startDist = length * (absorb * 0.78f).coerceIn(0f, 0.95f)
                    val stopDist = length * localInflow

                    if (stopDist > startDist + 1f) {
                        segmentPath.reset()
                        pathMeasure.getSegment(startDist, stopDist, segmentPath, true)

                        val headPos = pathMeasure.getPosition(stopDist)
                        val tailPos = pathMeasure.getPosition(startDist)

                        if (headPos.isSpecified && tailPos.isSpecified && !headPos.x.isNaN() && !headPos.y.isNaN() && !tailPos.x.isNaN() && !tailPos.y.isNaN()) {
                            drawLiquidSilkRibbonSegment(
                                segmentPath = segmentPath,
                                tailPos = tailPos,
                                headPos = headPos,
                                ribbonWidthPx = spec.ribbonWidthDp.dp.toPx(),
                                accentColor = spec.accentColor,
                                alphaMultiplier = visibilityAlpha,
                            )
                        }
                    }
                }
            }
        }

        // Central silk vortex & shockwave bloom when the ribbons fuse into the WB logo
        if (inflow > 0.35f && target.isSpecified && !target.x.isNaN() && !target.y.isNaN()) {
            val coreGlowProgress = ((inflow - 0.35f) / 0.65f).coerceIn(0f, 1f)
            val coreAlpha = coreGlowProgress * (1f - bloom * 0.65f)
            val coreRadius = (size.minDimension * (0.14f + 0.24f * bloom) * 1.6f).coerceAtLeast(1f)

            if (coreRadius > 0.5f) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.28f * coreAlpha),
                            Color.White.copy(alpha = 0.09f * coreAlpha),
                            Color.Transparent,
                        ),
                        center = target,
                        radius = coreRadius,
                    ),
                    radius = coreRadius,
                    center = target,
                )
            }
        }

        // Expanding 3D silk shockwave rings on logo crystallization
        if (bloom > 0.01f && bloom < 0.99f && target.isSpecified && !target.x.isNaN() && !target.y.isNaN()) {
            val ringAlpha = (1f - bloom) * 0.55f
            val ringRadius1 = size.minDimension * (0.10f + bloom * 0.42f)
            val ringRadius2 = size.minDimension * (0.06f + bloom * 0.28f)

            if (ringRadius1 > 1f) {
                drawCircle(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color.White.copy(alpha = ringAlpha),
                            Color.White.copy(alpha = ringAlpha * 0.2f),
                            Color.White.copy(alpha = ringAlpha * 0.8f),
                        ),
                        start = Offset(target.x - ringRadius1, target.y - ringRadius1),
                        end = Offset(target.x + ringRadius1, target.y + ringRadius1),
                    ),
                    radius = ringRadius1,
                    center = target,
                    style = Stroke(width = (6f * (1f - bloom) + 1f).dp.toPx()),
                )
            }

            if (ringRadius2 > 1f) {
                drawCircle(
                    color = Color.White.copy(alpha = ringAlpha * 0.65f),
                    radius = ringRadius2,
                    center = target,
                    style = Stroke(width = 1.4.dp.toPx()),
                )
            }
        }
    }
}

private data class SilkStreamSpec(
    val start: Offset,
    val ctrl1: Offset,
    val ctrl2: Offset,
    val end: Offset,
    val ribbonWidthDp: Float,
    val accentColor: Color,
)

/**
 * Draws a single 3D translucent liquid-silk ribbon segment with:
 * 1. Wide smoky-silver silk veil body
 * 2. Mid-width satin fold highlight
 * 3. Crisp specular silver-white rim wire
 * 4. Luminous leading tip flare where the silk is actively weaving
 */
private fun DrawScope.drawLiquidSilkRibbonSegment(
    segmentPath: Path,
    tailPos: Offset,
    headPos: Offset,
    ribbonWidthPx: Float,
    accentColor: Color,
    alphaMultiplier: Float,
) {
    // 1. Wide translucent silk veil
    drawPath(
        path = segmentPath,
        brush = Brush.linearGradient(
            colors = listOf(
                Color.Transparent,
                accentColor.copy(alpha = 0.10f * alphaMultiplier),
                accentColor.copy(alpha = 0.22f * alphaMultiplier),
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

    // 2. Inner satin fold sheen
    drawPath(
        path = segmentPath,
        brush = Brush.linearGradient(
            colors = listOf(
                Color.Transparent,
                Color.White.copy(alpha = 0.24f * alphaMultiplier),
                Color.White.copy(alpha = 0.48f * alphaMultiplier),
            ),
            start = tailPos,
            end = headPos,
        ),
        style = Stroke(
            width = ribbonWidthPx * 0.34f,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
        ),
    )

    // 3. Razor-sharp specular silver rim edge
    drawPath(
        path = segmentPath,
        brush = Brush.linearGradient(
            colors = listOf(
                Color.Transparent,
                Color.White.copy(alpha = 0.55f * alphaMultiplier),
                Color.White.copy(alpha = 0.95f * alphaMultiplier),
            ),
            start = tailPos,
            end = headPos,
        ),
        style = Stroke(
            width = 1.8.dp.toPx(),
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
        ),
    )

    // 4. Leading light-head flare at the tip of the incoming silk ribbon
    val tipRadius = ribbonWidthPx * 0.55f
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.85f * alphaMultiplier),
                accentColor.copy(alpha = 0.30f * alphaMultiplier),
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
 * Real-time vector silk weaving of the "WB" monogram:
 * As the 6 silk ribbons converge at the center, glowing silver silk filaments
 * trace the exact "W" and "B" contours before the solid off-white WB emblem locks in.
 */
@Composable
private fun WBMonogramSilkWeaveCanvas(
    traceProgress: Float,
    solidifyProgress: Float,
    modifier: Modifier = Modifier,
) {
    val pathMeasure = remember { PathMeasure() }
    val wSegment = remember { Path() }
    val bSegment = remember { Path() }
    val wFullPath = remember { Path() }
    val bFullPath = remember { Path() }

    Canvas(modifier = modifier) {
        if (traceProgress <= 0.01f) return@Canvas
        val w = size.width
        val h = size.height

        // Fade out the glowing construction filaments as the solid WB PNG finishes crystallizing
        val weaveAlpha = (1f - solidifyProgress * 0.85f).coerceIn(0f, 1f)
        if (weaveAlpha <= 0.01f) return@Canvas

        // "W" stroke geometry matching the WB monogram
        wFullPath.reset()
        wFullPath.moveTo(w * 0.12f, h * 0.32f)
        wFullPath.lineTo(w * 0.30f, h * 0.72f)
        wFullPath.lineTo(w * 0.43f, h * 0.52f)
        wFullPath.lineTo(w * 0.56f, h * 0.72f)
        wFullPath.lineTo(w * 0.75f, h * 0.37f)

        // "B" top-arch and double-loop geometry matching the WB monogram
        bFullPath.reset()
        bFullPath.moveTo(w * 0.47f, h * 0.24f)
        bFullPath.lineTo(w * 0.74f, h * 0.24f)
        bFullPath.cubicTo(
            w * 0.90f, h * 0.24f,
            w * 0.90f, h * 0.49f,
            w * 0.73f, h * 0.49f,
        )
        bFullPath.cubicTo(
            w * 0.92f, h * 0.49f,
            w * 0.92f, h * 0.75f,
            w * 0.74f, h * 0.75f,
        )
        bFullPath.lineTo(w * 0.65f, h * 0.75f)

        // Trace "W" path
        pathMeasure.setPath(wFullPath, false)
        val wLen = pathMeasure.length
        val wStop = wLen * traceProgress.coerceIn(0f, 1f)
        wSegment.reset()
        pathMeasure.getSegment(0f, wStop, wSegment, true)
        val wTip = pathMeasure.getPosition(wStop)

        // Trace "B" path
        pathMeasure.setPath(bFullPath, false)
        val bLen = pathMeasure.length
        val bStop = bLen * traceProgress.coerceIn(0f, 1f)
        bSegment.reset()
        pathMeasure.getSegment(0f, bStop, bSegment, true)
        val bTip = pathMeasure.getPosition(bStop)

        val mainStroke = w * 0.095f

        // Outer silk halo along traced W & B paths
        drawPath(
            path = wSegment,
            color = Color.White.copy(alpha = 0.26f * weaveAlpha),
            style = Stroke(width = mainStroke * 1.55f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
        drawPath(
            path = bSegment,
            color = Color.White.copy(alpha = 0.26f * weaveAlpha),
            style = Stroke(width = mainStroke * 1.55f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )

        // Core off-white silk ribbon forming the W & B strokes
        drawPath(
            path = wSegment,
            color = Paper.copy(alpha = 0.92f * weaveAlpha),
            style = Stroke(width = mainStroke, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
        drawPath(
            path = bSegment,
            color = Paper.copy(alpha = 0.92f * weaveAlpha),
            style = Stroke(width = mainStroke, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )

        // Glowing silver-white spark tips at the active drawing heads
        if (traceProgress < 0.98f) {
            val sparkR = (mainStroke * 1.4f).coerceAtLeast(0f)
            if (sparkR > 0.5f && wTip.isSpecified && !wTip.x.isNaN() && !wTip.y.isNaN()) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.95f * weaveAlpha),
                            Color.White.copy(alpha = 0.35f * weaveAlpha),
                            Color.Transparent,
                        ),
                        center = wTip,
                        radius = sparkR,
                    ),
                    radius = sparkR,
                    center = wTip,
                )
            }
            if (sparkR > 0.5f && bTip.isSpecified && !bTip.x.isNaN() && !bTip.y.isNaN()) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.95f * weaveAlpha),
                            Color.White.copy(alpha = 0.35f * weaveAlpha),
                            Color.Transparent,
                        ),
                        center = bTip,
                        radius = sparkR,
                    ),
                    radius = sparkR,
                    center = bTip,
                )
            }
        }
    }
}
