package com.music.spotui.ui.immersive.motion

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import androidx.navigation.NavBackStackEntry
import com.music.spotui.ui.look.rememberReduceMotion

import androidx.compose.animation.core.FastOutSlowInEasing

/**
 * The motion constants of the Immersive look in one place, so screens share one feel instead of
 * each picking numbers. The springs are WaveBits's (medium-low stiffness for moving things,
 * high for press feedback); pages push like a navigation stack, the old page sliding a third of
 * the way as the new one covers it.
 */
object ImmersiveMotion {
    /** Placement of rows in a list (reorder, insert, filter). */
    fun <T> placementSpring() = spring<T>(stiffness = Spring.StiffnessMedium)

    /**
     * The floating chrome folding and unfolding (the tab bar minimizing to a pill, the top bar's
     * controls shrinking): a crisp spring with minimal overshoot. Snaps under "reduce motion".
     */
    fun <T> chromeSpring(reduce: Boolean = false): androidx.compose.animation.core.FiniteAnimationSpec<T> =
        if (reduce) androidx.compose.animation.core.snap() else spring(dampingRatio = 0.84f, stiffness = 440f)

    /**
     * The one set of durations and springs of the Immersive look: everything that moves on a
     * finger uses a responsive spring, everything that fades uses brisk durations.
     */
    const val FADE_MS = 120
    const val SHORT_MS = 180
    const val MEDIUM_MS = 260
    const val LONG_MS = 320
    const val TINT_MS = 450

    /** Press feedback (scale under the finger): immediate and critically damped. */
    fun <T> pressSpring() = spring<T>(stiffness = 1500f)

    /**
     * Glass objects that travel (the tab selection pill, a lens): brisk travel with a crisp settle.
     */
    fun <T> glassSpring() = spring<T>(dampingRatio = 0.78f, stiffness = 460f)

    /** Release of a glass surface after a press: responsive bounce. */
    fun <T> releaseSpring() = spring<T>(dampingRatio = 0.76f, stiffness = 700f)

    /** Content that resizes inside a floating container (controls opening in a pill). */
    fun <T> sizeSpring() = spring<T>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 460f)

    /**
     * The full player rising from the mini player and going back: critically damped and snappy.
     * Snaps under "reduce motion".
     */
    fun slideSpring(reduce: Boolean = false): androidx.compose.animation.core.AnimationSpec<Float> =
        if (reduce) androidx.compose.animation.core.snap()
        else spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 360f, visibilityThreshold = 0.5f)

    /** A page pushed or popped by the nav host. */
    private const val PAGE_MS = 200
    private val PageEasing = androidx.compose.animation.core.CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

    /**
     * Enter/exit specs for `NavHost` in the Immersive look. With "reduce motion" on they are a
     * short fade. Pass them as `enterTransition`, `exitTransition`, `popEnterTransition`,
     * `popExitTransition`.
     *
     * Top page slides full width completely opaque; underneath page shifts 20% with a subtle
     * brightness dim (0.92f) so transitions stay responsive without dipping to black.
     */
    object Pages {
        fun enter(reduce: Boolean): AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
            if (reduce) fadeIn(tween(100))
            else slideInHorizontally(tween(PAGE_MS, easing = PageEasing)) { it }
        }

        fun exit(reduce: Boolean): AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
            if (reduce) fadeOut(tween(100))
            else slideOutHorizontally(tween(PAGE_MS, easing = PageEasing)) { -it / 5 } +
                fadeOut(tween(PAGE_MS, easing = PageEasing), targetAlpha = 0.92f)
        }

        fun popEnter(reduce: Boolean): AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
            if (reduce) fadeIn(tween(100))
            else slideInHorizontally(tween(PAGE_MS, easing = PageEasing)) { -it / 5 } +
                fadeIn(tween(PAGE_MS, easing = PageEasing), initialAlpha = 0.92f)
        }

        fun popExit(reduce: Boolean): AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
            if (reduce) fadeOut(tween(100))
            else slideOutHorizontally(tween(PAGE_MS, easing = PageEasing)) { it }
        }
    }
}

/**
 * Items of a lazy list glide to their new place (no cross-fade, the list stays readable), and
 * stay put under "reduce motion". Use on every row of an Immersive list.
 */
@Composable
fun LazyItemScope.immersiveItemPlacement(): Modifier {
    if (rememberReduceMotion()) return Modifier
    return Modifier.animateItem(
        fadeInSpec = null,
        fadeOutSpec = null,
        placementSpec = ImmersiveMotion.placementSpring(),
    )
}
