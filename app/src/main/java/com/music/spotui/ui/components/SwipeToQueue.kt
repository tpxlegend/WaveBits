package com.music.spotui.ui.components

import app.wavebits.engine.R

import androidx.compose.ui.res.stringResource

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.ui.look.rememberReduceMotion
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Spotify's gesture: swipe a song row sideways to add it to the queue. The row
 * follows the finger over a green strip, ticks once with a vibration when it
 * passes the threshold, adds the song ONCE on release, then springs back.
 */
@Composable
fun SwipeToQueue(song: SongsModel, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val reduceMotion = rememberReduceMotion()
    val threshold = with(LocalDensity.current) { 96.dp.toPx() }
    val maxDrag = threshold * 1.4f
    val animOffset = remember { Animatable(0f) }
    var dragX by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    var settleJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var armed by remember { mutableStateOf(false) }
    var swiping by remember { mutableStateOf(false) }
    var swipingRight by remember { mutableStateOf(true) }

    Box(modifier = Modifier.fillMaxWidth()) {
        if (swiping) {
            Box(
                contentAlignment = if (swipingRight) Alignment.CenterStart else Alignment.CenterEnd,
                modifier = Modifier
                    .matchParentSize()
                    .background(if (armed) com.music.spotui.ui.theme.AppAccent else com.music.spotui.ui.theme.AppAccent.copy(alpha = 0.6f)),
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.PlaylistAdd,
                    contentDescription = stringResource(R.string.add_to_queue_action_content_description),
                    tint = Color.Black,
                    modifier = Modifier.padding(horizontal = 24.dp).size(26.dp),
                )
            }
        }
        Box(
            modifier = Modifier
                .graphicsLayer { translationX = if (dragging) dragX else animOffset.value }
                .background(com.music.spotui.ui.theme.AppBackground)
                .pointerInput(song.id) {
                    detectHorizontalDragGestures(
                        onDragStart = {
                            settleJob?.cancel()
                            dragX = animOffset.value
                            dragging = true
                        },
                        onDragEnd = {
                            val target = song.spotifyTrackId.ifBlank {
                                if (song.url.isNotBlank() && !song.url.startsWith("http")) song.url else ""
                            }
                            if (armed && target.isNotBlank()) {
                                com.music.spotui.di.SongPlayer.addToQueue(target, song)
                                android.widget.Toast.makeText(context, context.getString(R.string.snackbar_added_to_queue), android.widget.Toast.LENGTH_SHORT).show()
                            }
                            armed = false
                            val from = dragX
                            dragging = false
                            settleJob = scope.launch {
                                animOffset.snapTo(from)
                                animOffset.animateTo(
                                    0f,
                                    if (reduceMotion) snap()
                                    else spring(dampingRatio = 0.85f, stiffness = androidx.compose.animation.core.Spring.StiffnessMedium),
                                )
                                swiping = false
                            }
                        },
                        onDragCancel = {
                            armed = false
                            val from = dragX
                            dragging = false
                            settleJob = scope.launch {
                                animOffset.snapTo(from)
                                animOffset.animateTo(
                                    0f,
                                    if (reduceMotion) snap()
                                    else spring(dampingRatio = 0.85f, stiffness = androidx.compose.animation.core.Spring.StiffnessMedium),
                                )
                                swiping = false
                            }
                        },
                    ) { change, dx ->
                        change.consume()
                        val next = (dragX + dx).coerceIn(-maxDrag, maxDrag)
                        dragX = next
                        if (!swiping && next != 0f) swiping = true
                        if (next != 0f) swipingRight = next > 0f
                        val nowArmed = abs(next) >= threshold
                        if (nowArmed && !armed) haptic.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                        armed = nowArmed
                    }
                },
        ) { content() }
    }
}
