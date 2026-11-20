package com.music.spotui.ui.components

import com.music.spotui.ui.theme.AppText

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * A thin draggable thumb on the right edge of a long [LazyListState] list: shown while the
 * list scrolls or the thumb is dragged, hidden otherwise. Place it in a Box over the list.
 */
@Composable
fun FastScrollbar(state: LazyListState, modifier: Modifier = Modifier, minItems: Int = 30) {
    val total by remember(state) { derivedStateOf { state.layoutInfo.totalItemsCount } }
    if (total <= minItems) return
    var dragging by remember { mutableStateOf(false) }
    var visible by remember { mutableStateOf(false) }
    val scrolling by remember(state) { derivedStateOf { state.isScrollInProgress } }
    LaunchedEffect(scrolling, dragging) {
        if (scrolling || dragging) visible = true else { delay(1200); visible = false }
    }
    val alphaState = androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        label = "scrollbar",
    )
    val shouldShow by remember { derivedStateOf { alphaState.value > 0f } }
    if (!shouldShow) return
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val thumbPx = with(density) { 56.dp.toPx() }
    val thumbColor = remember(dragging) { AppText.copy(alpha = if (dragging) 0.85f else 0.5f) }
    var scrollJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    Box(
        modifier
            .fillMaxHeight()
            .width(28.dp)
            .clearAndSetSemantics {}
            .graphicsLayer { alpha = alphaState.value },
    ) {
        Box(
            Modifier
                .align(androidx.compose.ui.Alignment.TopEnd)
                .graphicsLayer {
                    val info = state.layoutInfo
                    val itemCount = info.totalItemsCount
                    val trackPx = info.viewportSize.height.toFloat()
                    val firstVisible = info.visibleItemsInfo.firstOrNull()
                    val subItemOffset = if (firstVisible != null && firstVisible.size > 0) {
                        state.firstVisibleItemScrollOffset.toFloat() / firstVisible.size
                    } else 0f
                    val fraction = if (itemCount <= 1) 0f else {
                        ((state.firstVisibleItemIndex + subItemOffset) / (itemCount - 1)).coerceIn(0f, 1f)
                    }
                    val travel = (trackPx - thumbPx).coerceAtLeast(0f)
                    translationY = fraction * travel
                }
                .padding(end = 3.dp)
                .size(width = 5.dp, height = 56.dp)
                .background(thumbColor, RoundedCornerShape(50)),
        )
        // A wider invisible strip so the thin thumb is easy to grab.
        Box(
            Modifier
                .fillMaxHeight()
                .width(28.dp)
                .pointerInput(state) {
                    detectVerticalDragGestures(
                        onDragStart = { dragging = true },
                        onDragEnd = { dragging = false },
                        onDragCancel = { dragging = false },
                    ) { change, _ ->
                        change.consume()
                        val itemCount = state.layoutInfo.totalItemsCount
                        if (itemCount <= 1) return@detectVerticalDragGestures
                        val h = (size.height - thumbPx).coerceAtLeast(1f)
                        val f = ((change.position.y - thumbPx / 2) / h).coerceIn(0f, 1f)
                        val targetIndex = (f * (itemCount - 1)).roundToInt()
                        scrollJob?.cancel()
                        scrollJob = scope.launch { state.scrollToItem(targetIndex) }
                    }
                },
        )
    }
}
