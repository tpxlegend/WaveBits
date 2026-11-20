/*
 * WaveBits Immersive TopBar and collapsing page heading.
 */
package com.music.spotui.ui.immersive.shell

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.wavebits.engine.R
import com.music.spotui.ui.immersive.Immersive
import com.music.spotui.ui.immersive.motion.ImmersiveMotion
import com.music.spotui.ui.look.rememberReduceMotion
import dev.chrisbanes.haze.HazeState

/**
 * The bar's own height, above whatever inset it is sitting under. The single source of truth for
 * it: the bar lays itself out to this, and everything that has to clear the bar measures from
 * [immersiveTopBarHeight] rather than from a copy of the number.
 */
val ImmersiveTopBarContentHeight = 52.dp

/** The breathing room between the bar's bottom edge and the first thing under it. */
val ImmersiveTopBarContentGap = 12.dp

/** How much of each end of the bar is spoken for, so a long title truncates instead of running under it. */
private val TitleInset = 64.dp

/** The back and action visual size: a 44 dp circle with an accessible 48 dp touch target. */
private val BarControlSize = 44.dp

/** Scale factor while the chrome is minimized ([LocalImmersiveChrome]): 34 / 44 (~0.773). */
private const val BarControlMinimizedScale = 34f / 44f

/**
 * Fixed layout size for top bar controls so folding/unfolding never causes relayouts;
 * visual shrinking uses [LocalBarControlScale] inside `graphicsLayer`.
 */
val LocalBarControlSize = compositionLocalOf { BarControlSize }

/** GPU-composited scale reader for top bar controls during scroll fold. */
val LocalBarControlScale = compositionLocalOf<() -> Float> { { 1f } }

@Composable
private fun rememberBarControlScale(): () -> Float {
    val minimized = LocalImmersiveChrome.current?.minimized == true
    val reduce = rememberReduceMotion()
    val scale: State<Float> = animateFloatAsState(
        targetValue = if (minimized) BarControlMinimizedScale else 1f,
        animationSpec = ImmersiveMotion.chromeSpring(reduce),
        label = "immersiveBarControlScale",
    )
    return remember(scale) { { scale.value } }
}

/**
 * How far down the window the bar actually ends: the status bar inset it is pinned under, plus
 * its own height. Read at composition because the inset is a property of the device, not the app.
 */
@Composable
fun immersiveTopBarHeight(): Dp =
    WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + ImmersiveTopBarContentHeight

/**
 * Where page content should start: clear of the bar, plus [ImmersiveTopBarContentGap]. Use it as
 * the top `contentPadding` of the page's list so its large title rests just under the bar.
 */
@Composable
fun immersiveTopContentPadding(): Dp = immersiveTopBarHeight() + ImmersiveTopBarContentGap

/**
 * The top bar of an Immersive page: a back chevron and actions on glass circles, and a small
 * centred [title] over a frosted strip — both fade in as [collapse] goes from 0 to 1.
 */
@Composable
fun ImmersiveTopBar(
    title: String,
    collapse: () -> Float,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    hazeState: HazeState? = LocalImmersiveHaze.current,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    val page = LocalImmersiveBackdrop.current
    val controlScale = rememberBarControlScale()
    val reduce = rememberReduceMotion()
    val backdropActive by remember(collapse) { derivedStateOf { collapse() > 0.01f } }
    Box(modifier = modifier.fillMaxWidth()) {
        // Skip blur/RenderEffect entirely while invisible at scroll top (collapse <= 0.01f).
        if (backdropActive) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer { alpha = collapse() }
                    .then(
                        when {
                            page != null && hazeState != null && hazeState === LocalImmersiveHaze.current ->
                                Modifier.immersiveLiquidGlass(RectangleShape, page, tint = Immersive.Page.copy(alpha = 0.55f), lens = false, rim = false)
                            hazeState != null -> Modifier.immersiveFrost(hazeState, tint = Immersive.Page.copy(alpha = 0.55f))
                            else -> Modifier.background(Immersive.Page.copy(alpha = 0.94f))
                        },
                    ),
            )
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(0.5.dp)
                    .graphicsLayer { alpha = collapse() }
                    .background(Immersive.Separator),
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(ImmersiveTopBarContentHeight),
        ) {
            Text(
                text = title,
                style = Immersive.Headline.copy(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = TitleInset)
                    .fillMaxWidth()
                    .graphicsLayer { alpha = collapse() },
            )
            if (onBack != null) {
                val backSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = Immersive.Gutter)
                        .minimumInteractiveComponentSize()
                        .size(BarControlSize)
                        .graphicsLayer {
                            val s = controlScale()
                            scaleX = s
                            scaleY = s
                            transformOrigin = TransformOrigin(0f, 0.5f)
                        }
                        .immersiveGlassSurface(CircleShape, hazeState, solid = com.music.spotui.data.preferences.SolidNavBar.on, interactionSource = backSource)
                        .clickable(interactionSource = backSource, indication = null, role = Role.Button, onClick = onBack),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
                        contentDescription = stringResource(R.string.immersive_shell_back),
                        tint = Immersive.Text,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }
            if (actions != null) {
                Row(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = Immersive.Gutter)
                        .graphicsLayer {
                            val s = controlScale()
                            scaleX = s
                            scaleY = s
                            transformOrigin = TransformOrigin(1f, 0.5f)
                        }
                        .immersiveGlassSurface(CircleShape, hazeState, solid = com.music.spotui.data.preferences.SolidNavBar.on)
                        .then(
                            if (reduce) Modifier
                            else Modifier.animateContentSize(animationSpec = ImmersiveMotion.sizeSpring()),
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CompositionLocalProvider(
                        LocalBarControlSize provides BarControlSize,
                        LocalBarControlScale provides controlScale,
                    ) { actions() }
                }
            }
        }
    }
}

/** [ImmersiveTopBar] collapsing as a [LazyListState] scrolls: [collapseDistance] of scroll takes it from 0 to 1. */
@Composable
fun ImmersiveTopBar(
    title: String,
    listState: LazyListState,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    hazeState: HazeState? = LocalImmersiveHaze.current,
    collapseDistance: Dp = 40.dp,
    actions: (@Composable RowScope.() -> Unit)? = null,
) = ImmersiveTopBar(
    title = title,
    collapse = rememberTopBarCollapse(listState, collapseDistance),
    modifier = modifier,
    onBack = onBack,
    hazeState = hazeState,
    actions = actions,
)

/** [ImmersiveTopBar] collapsing as a [ScrollState] scrolls: [collapseDistance] of scroll takes it from 0 to 1. */
@Composable
fun ImmersiveTopBar(
    title: String,
    scrollState: ScrollState,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    hazeState: HazeState? = LocalImmersiveHaze.current,
    collapseDistance: Dp = 40.dp,
    actions: (@Composable RowScope.() -> Unit)? = null,
) = ImmersiveTopBar(
    title = title,
    collapse = rememberTopBarCollapse(scrollState, collapseDistance),
    modifier = modifier,
    onBack = onBack,
    hazeState = hazeState,
    actions = actions,
)

/** The collapse fraction (0..1) of a [LazyListState] page: 1 once past the first item. */
@Composable
fun rememberTopBarCollapse(state: LazyListState, distance: Dp = 40.dp): () -> Float {
    val distancePx = with(LocalDensity.current) { distance.toPx() }
    return remember(state, distancePx) {
        val fraction: () -> Float = {
            if (state.firstVisibleItemIndex > 0) 1f
            else (state.firstVisibleItemScrollOffset / distancePx).coerceIn(0f, 1f)
        }
        fraction
    }
}

/** The collapse fraction (0..1) of a [ScrollState] page. */
@Composable
fun rememberTopBarCollapse(state: ScrollState, distance: Dp = 40.dp): () -> Float {
    val distancePx = with(LocalDensity.current) { distance.toPx() }
    return remember(state, distancePx) {
        val fraction: () -> Float = { (state.value / distancePx).coerceIn(0f, 1f) }
        fraction
    }
}

/**
 * One 44 dp icon button (with 48 dp minimum accessible hit target) for [ImmersiveTopBar]'s `actions`.
 *
 * @param tint the glyph colour; [Immersive.Accent] for an active toggle.
 */
@Composable
fun ImmersiveBarAction(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Immersive.Text,
) {
    val size = LocalBarControlSize.current
    Box(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .size(size)
            .clip(CircleShape)
            .then(com.music.spotui.ui.immersive.components.PressScalePointerElement(0.88f, 1500f))
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(22.dp))
    }
}

/**
 * A page's large heading (the first thing in its list), over the bar's small title that takes
 * over when it has scrolled away. [trailing] sits at the end of the line (a count, a sort button).
 */
@Composable
fun ImmersiveLargeTitle(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Immersive.Gutter, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = Immersive.LargeTitle,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() },
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = Immersive.Subhead,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End, content = trailing)
        }
    }
}

/** [ImmersiveLargeTitle] as the next item of a lazy list. */
fun LazyListScope.immersiveLargeTitle(
    title: String,
    subtitle: String? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    item(key = "immersive:large-title", contentType = "immersive:large-title") {
        ImmersiveLargeTitle(title = title, subtitle = subtitle, trailing = trailing)
    }
}

/** The row of [ImmersiveFloatingHeader] under the status bar. */
val ImmersiveHeaderContentHeight = 56.dp

/** The rounded bottom corners of [ImmersiveFloatingHeader]. */
private val HeaderCorner = 26.dp

/**
 * Where a page under [ImmersiveFloatingHeader] should start: just under the glass, with the gap
 * the content needs to read as below it rather than cut by it.
 */
@Composable
fun immersiveHeaderContentPadding(): Dp =
    WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + ImmersiveHeaderContentHeight + 10.dp

/**
 * A tab's heading on floating glass: the title at the start and the
 * [trailing] controls at the end of one row, on a glass panel pinned to the top of the screen
 * (behind the status bar) with rounded bottom corners.
 */
@Composable
fun ImmersiveFloatingHeader(
    title: String,
    modifier: Modifier = Modifier,
    hazeState: HazeState? = LocalImmersiveHaze.current,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val controlScale = rememberBarControlScale()
    val shape = remember { RoundedCornerShape(bottomStart = HeaderCorner, bottomEnd = HeaderCorner) }
    Box(modifier.fillMaxWidth().immersiveGlassSurface(shape, hazeState, solid = com.music.spotui.data.preferences.SolidNavBar.on)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(ImmersiveHeaderContentHeight)
                .padding(start = Immersive.Gutter, end = Immersive.Gutter - 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = Immersive.Title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            if (trailing != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.graphicsLayer {
                        val s = controlScale()
                        scaleX = s
                        scaleY = s
                        transformOrigin = TransformOrigin(1f, 0.5f)
                    },
                ) {
                    CompositionLocalProvider(
                        LocalBarControlSize provides BarControlSize,
                        LocalBarControlScale provides controlScale,
                    ) { trailing() }
                }
            }
        }
    }
}

