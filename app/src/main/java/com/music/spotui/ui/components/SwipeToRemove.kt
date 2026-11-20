package com.music.spotui.ui.components

import com.music.spotui.ui.theme.AppText

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.wavebits.engine.R
import kotlinx.coroutines.launch

/**
 * Swipe a row from right to left to remove it: the row follows the finger over a red
 * strip with a bin, ticks past the threshold, and slides out before [onRemove].
 */
@Composable
fun SwipeToRemove(key: Any, onRemove: () -> Unit, content: @Composable () -> Unit) {
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val threshold = with(LocalDensity.current) { 120.dp.toPx() }
    val animOffset = remember(key) { Animatable(0f) }
    var dragX by remember(key) { mutableFloatStateOf(0f) }
    var dragging by remember(key) { mutableStateOf(false) }
    var settleJob by remember(key) { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var armed by remember(key) { mutableStateOf(false) }
    var swiping by remember(key) { mutableStateOf(false) }
    var width by remember { mutableFloatStateOf(0f) }

    Box(modifier = Modifier.fillMaxWidth().onSizeChanged { width = it.width.toFloat() }) {
        if (swiping || (!dragging && animOffset.value < 0f)) {
            Box(
                contentAlignment = Alignment.CenterEnd,
                modifier = Modifier.matchParentSize()
                    .background(if (armed) Color(0xFFE22134) else Color(0xFFE22134).copy(alpha = 0.6f)),
            ) {
                Icon(Icons.Outlined.DeleteOutline, stringResource(R.string.context_menu_undownload), tint = AppText,
                    modifier = Modifier.padding(horizontal = 24.dp).size(26.dp))
            }
        }
        Box(
            modifier = Modifier
                .graphicsLayer { translationX = if (dragging) dragX else animOffset.value }
                .background(com.music.spotui.ui.theme.AppBackground)
                .pointerInput(key) {
                    detectHorizontalDragGestures(
                        onDragStart = {
                            settleJob?.cancel()
                            dragX = animOffset.value
                            dragging = true
                        },
                        onDragEnd = {
                            val from = dragX
                            val wasArmed = armed
                            dragging = false
                            armed = false
                            settleJob = scope.launch {
                                animOffset.snapTo(from)
                                if (wasArmed) {
                                    animOffset.animateTo(-width.coerceAtLeast(threshold), tween(160))
                                    swiping = false
                                    onRemove()
                                } else {
                                    animOffset.animateTo(0f, spring(dampingRatio = 0.85f, stiffness = androidx.compose.animation.core.Spring.StiffnessMedium))
                                    swiping = false
                                }
                            }
                        },
                        onDragCancel = {
                            val from = dragX
                            dragging = false
                            armed = false
                            settleJob = scope.launch {
                                animOffset.snapTo(from)
                                animOffset.animateTo(0f, spring(dampingRatio = 0.85f, stiffness = androidx.compose.animation.core.Spring.StiffnessMedium))
                                swiping = false
                            }
                        },
                    ) { change, dx ->
                        change.consume()
                        // Leftwards only (right to left).
                        val next = (dragX + dx).coerceIn(-(width.coerceAtLeast(threshold)), 0f)
                        dragX = next
                        if (!swiping && next < 0f) swiping = true
                        val nowArmed = -next >= threshold
                        if (nowArmed && !armed) haptic.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                        armed = nowArmed
                    }
                },
        ) { content() }
    }
}
