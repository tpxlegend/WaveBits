/*
 * WaveBits Immersive Cover and Placeholder components.
 */
package com.music.spotui.ui.immersive.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlin.math.PI
import kotlin.math.sin
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.wavebits.power.EnergySettings
import com.bumptech.glide.integration.compose.CrossFade
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.bitmap.DownsampleStrategy
import com.music.spotui.ui.components.spotifyImageUrl
import com.music.spotui.ui.immersive.Immersive
import com.music.spotui.ui.look.LocalEnergyPolicy

/** The hairline every cover carries, so a cover with light edges does not dissolve into the page. */
fun Modifier.immersiveThumbnailBorder(shape: Shape): Modifier =
    this.border(width = 1.dp, color = Color.White.copy(alpha = 0.12f), shape = shape)

/**
 * A cover (album, playlist, artist, show) from [url], clipped to [shape] with the thumbnail
 * hairline and a [Immersive.Raised] fill that shows while it loads. [url] may be a
 * `spotify:image:` uri (see `spotifyImageUrl`); null or blank draws [placeholderIcon] instead.
 *
 * The size comes from [modifier]: `ImmersiveCover(url, Modifier.size(52.dp))`,
 * `Modifier.fillMaxWidth().aspectRatio(1f)`. Use `CircleShape` for artists.
 */
@OptIn(ExperimentalGlideComposeApi::class)
@Composable
fun ImmersiveCover(
    url: String?,
    modifier: Modifier = Modifier,
    shape: Shape = Immersive.CoverMedium,
    contentDescription: String? = null,
    border: Boolean = true,
    placeholderIcon: ImageVector = Icons.Rounded.MusicNote,
    decodeSize: Dp = 180.dp,
) {
    val model = spotifyImageUrl(url)
    Box(
        modifier = modifier
            .clip(shape)
            .background(Immersive.Raised)
            .then(if (border) Modifier.immersiveThumbnailBorder(shape) else Modifier),
    ) {
        if (model == null) {
            ImmersiveCoverPlaceholder(icon = placeholderIcon, modifier = Modifier.fillMaxSize())
        } else {
            val energyPolicy = LocalEnergyPolicy.current
            val lowEnd = energyPolicy.disableHeavyAnimations || EnergySettings.isLowEndDevice
            val decodePx = with(LocalDensity.current) { decodeSize.roundToPx() }.coerceAtLeast(96)
            GlideImage(
                model = model,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                transition = if (lowEnd) null else CrossFade,
                modifier = Modifier.fillMaxSize(),
            ) { builder ->
                val configured = builder
                    .override(decodePx)
                    .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                if (lowEnd) {
                    configured
                        .format(DecodeFormat.PREFER_RGB_565)
                        .downsample(DownsampleStrategy.CENTER_INSIDE)
                        .dontAnimate()
                } else {
                    configured
                }
            }
        }
    }
}

/**
 * What stands in for a cover that is not there: a playlist with nothing on it yet, an album whose
 * files carry no picture. A soft diagonal gradient off [base] with a glyph for what the thing is.
 * Callers draw it behind the image, so a cover that loads late simply covers it.
 */
@Composable
fun ImmersiveCoverPlaceholder(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    base: Color = Immersive.Raised,
    tint: Color = Immersive.Text,
    /** The glyph's share of the tile. */
    iconFraction: Float = 0.36f,
) {
    Box(
        modifier = modifier.background(Brush.linearGradient(listOf(lerp(base, tint, 0.16f), base))),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint.copy(alpha = 0.6f),
            modifier = Modifier.fillMaxSize(iconFraction),
        )
    }
}

/**
 * Three bars dancing on a dark plate, laid over a cover to mark the song that is playing.
 * The plate is a fixed square so only the bars move.
 */
@Composable
fun ImmersivePlayingBars(modifier: Modifier = Modifier) {
    val reduceMotion = com.music.spotui.ui.look.rememberReduceMotion()
    val phase = if (reduceMotion) {
        0.8f
    } else {
        val transition = rememberInfiniteTransition(label = "immersive playing bars")
        val animated by transition.animateFloat(
            initialValue = 0f,
            targetValue = (2f * PI).toFloat(),
            animationSpec = infiniteRepeatable(
                animation = tween(1100, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "barsPhase",
        )
        animated
    }
    Canvas(
        modifier = modifier
            .size(24.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Color.Black.copy(alpha = 0.52f)),
    ) {
        val barCount = 3
        val barWidth = 3.dp.toPx()
        val spacing = 2.dp.toPx()
        val maxHeight = 14.dp.toPx()
        val totalWidth = barCount * barWidth + (barCount - 1) * spacing
        val startX = (size.width - totalWidth) / 2f
        val bottomY = (size.height + maxHeight) / 2f
        val cornerRadius = CornerRadius(1.5.dp.toPx(), 1.5.dp.toPx())

        for (i in 0 until barCount) {
            val wave = (sin(phase * 2.4f + i * 1.8f) * 0.5f + 0.5f)
            val h = (3.5.dp.toPx() + wave * (maxHeight - 3.5.dp.toPx())).coerceIn(3.5.dp.toPx(), maxHeight)
            val x = startX + i * (barWidth + spacing)
            val y = bottomY - h
            drawRoundRect(
                color = Color.White,
                topLeft = Offset(x, y),
                size = Size(barWidth, h),
                cornerRadius = cornerRadius,
            )
        }
    }
}
