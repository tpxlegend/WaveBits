/*
 * WaveBits Immersive Haze surface helpers.
 */
package com.music.spotui.ui.immersive.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.spotui.ui.immersive.Immersive
import com.music.spotui.ui.immersive.shell.backdrop.backdrops.LayerBackdrop
import com.music.spotui.ui.immersive.shell.backdrop.backdrops.layerBackdrop
import com.music.spotui.ui.look.LocalEnergyPolicy
import dev.chrisbanes.haze.ExperimentalHazeApi
import dev.chrisbanes.haze.HazeInputScale
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource

/**
 * The page that the frosted bars (tab bar, top bar) blur, provided once by the host:
 *
 * ```
 * val haze = rememberImmersiveHazeState()
 * CompositionLocalProvider(LocalImmersiveHaze provides haze) {
 *     Box {
 *         NavHost(Modifier.immersiveHazeSource()) { ... }   // what scrolls under the bars
 *         ImmersiveTabBar(nav, Modifier.align(Alignment.BottomCenter))
 *     }
 * }
 * ```
 *
 * Null (the default) means no page is being sampled: the bars then fall back to a plain
 * translucent fill, which is also what they show before the first frame is recorded.
 */
val LocalImmersiveHaze = compositionLocalOf<HazeState?> { null }

/** A [HazeState] kept for the lifetime of the calling composition. */
@Composable
fun rememberImmersiveHazeState(): HazeState = remember { HazeState() }

/**
 * Marks this content as what the frosted bars blur. Put it on the NavHost (or the screen's
 * scrolling content), not on the bars themselves. No-op when no [LocalImmersiveHaze] is provided.
 *
 * While Liquid Glass is on ([LocalImmersiveBackdrop] provided) it also records the content for
 * the glass to refract, so a page that calls this gets both looks with nothing else to do.
 */
@Composable
fun Modifier.immersiveHazeSource(
    state: HazeState? = LocalImmersiveHaze.current,
    backdrop: LayerBackdrop? = LocalImmersiveBackdrop.current,
): Modifier {
    val policy = LocalEnergyPolicy.current
    if (app.wavebits.power.EnergySettings.isLowEndDevice || policy.saverEnabled) return this
    val hazed = if (state == null) this else this.hazeSource(state)
    return if (backdrop == null) hazed else hazed.layerBackdrop(backdrop)
}

/**
 * Keeps Haze's visual style while sampling at a third of the resolution: a blur this wide hides
 * the missing pixels, and the bars are redrawn on every frame of every scroll.
 */
@OptIn(ExperimentalHazeApi::class)
internal fun Modifier.immersiveFrost(
    state: HazeState,
    tint: Color = Immersive.GlassFill.copy(alpha = 0.55f),
    blur: Float = 18f,
): Modifier {
    if (app.wavebits.power.EnergySettings.isLowEndOrSaver) {
        return background(Immersive.GlassFill.copy(alpha = 0.92f))
    }
    return hazeEffect(state) {
        inputScale = HazeInputScale.Fixed(0.33f)
        backgroundColor = Immersive.Page
        tints = listOf(HazeTint(tint))
        blurRadius = blur.dp
        noiseFactor = 0f
    }
}

/**
 * The glass of the floating bars: [shape]-clipped frost over the page when one is provided,
 * the translucent [Immersive.GlassFill] when not, and the hairline edge in both cases.
 *
 * With Liquid Glass on (Settings > Display, Android 12+, see [rememberLiquidGlassActive]) and
 * [state] the app's own page ([LocalImmersiveHaze], the default), the same call draws real glass
 * instead: the page refracted through a lens, a rim of light and a soft shadow
 * ([immersiveLiquidGlass]). A [state] of your own keeps its frost; null keeps the plain fill.
 *
 * @param interactionSource the surface's own press source: it scales a little under the finger
 * and springs back, and the glass lens bulges (iOS 26). Null for a surface that is not tapped.
 * @param liquid false keeps the frost even while Liquid Glass is on (a surface that would cost
 * too much, or that must stay flat).
 */
@Composable
internal fun Modifier.immersiveGlassSurface(
    shape: Shape,
    state: HazeState? = LocalImmersiveHaze.current,
    liquid: Boolean = true,
    solid: Boolean = false,
    interactionSource: androidx.compose.foundation.interaction.InteractionSource? = null,
): Modifier {
    val policy = LocalEnergyPolicy.current
    val liteMode = app.wavebits.power.EnergySettings.isLowEndDevice || policy.saverEnabled
    val page = LocalImmersiveBackdrop.current
    val press = rememberGlassPress(interactionSource)
    if (solid) {
        return this
            .then(
                if (press == null) Modifier else Modifier.graphicsLayer {
                    val s = 1f - 0.04f * press()
                    scaleX = s
                    scaleY = s
                },
            )
            .clip(shape)
            .background(Immersive.Page)
            .border(1.dp, Color.White.copy(alpha = 0.08f), shape)
    }
    if (liteMode) {
        return this
            .clip(shape)
            .background(Immersive.GlassFill.copy(alpha = 0.92f))
            .border(1.dp, Immersive.GlassEdge, shape)
    }
    if (liquid && page != null && state != null && state === LocalImmersiveHaze.current) {
        // Not clipped here: the glass clips itself, and its shadow falls outside the shape.
        return this.immersiveLiquidGlass(shape, page, press = press)
    }
    return this
        .then(
            if (press == null) Modifier else Modifier.graphicsLayer {
                val s = 1f - 0.04f * press()
                scaleX = s
                scaleY = s
            },
        )
        .clip(shape)
        .then(if (state != null) Modifier.immersiveFrost(state) else Modifier.background(Immersive.GlassFill))
        .border(1.dp, Immersive.GlassEdge, shape)
}
