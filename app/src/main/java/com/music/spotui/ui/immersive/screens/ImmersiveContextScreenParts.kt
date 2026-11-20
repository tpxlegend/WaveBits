/*
 * WaveBits Immersive Context Screen shared parts (action pills, circle buttons, About block, section card).
 */
package com.music.spotui.ui.immersive.screens

import android.widget.Toast
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import kotlinx.coroutines.CoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.wavebits.engine.R
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.di.SongPlayer
import com.music.spotui.ui.immersive.Immersive
import com.music.spotui.ui.look.rememberReduceMotion
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/** How long a greyed track is given to start before the app says why it did not. */
private const val UNAVAILABLE_CHECK_MS = 4_000L

/**
 * Taps on song rows. A greyed (unavailable) row is still tried, as on the Classic pages: the
 * catalogue's playability flag is per market and can be wrong. If the player has not moved to it
 * a few seconds later, the reason is said — a tap that appears to do nothing makes the app look broken.
 */
internal class TrackTapper(private val scope: CoroutineScope, private val context: android.content.Context) {
    fun tap(song: SongsModel, play: () -> Unit) {
        play()
        if (song.playable) return
        scope.launch {
            delay(UNAVAILABLE_CHECK_MS)
            val playing = SongPlayer.nowPlaying.value.uri
            if (song.spotifyTrackId.isNotBlank() && !playing.endsWith(song.spotifyTrackId)) {
                Toast.makeText(context, R.string.track_unavailable_region, Toast.LENGTH_LONG).show()
            }
        }
    }
}

@Composable
internal fun rememberTrackTapper(): TrackTapper {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current.applicationContext
    return remember(scope, context) { TrackTapper(scope, context) }
}

/**
 * Swipe a song row sideways to add it to the queue: the row follows the finger over an accent
 * strip, ticks once past the threshold, and adds the song once on release before springing back.
 * The Classic pages' gesture, drawn in the Immersive look's colours.
 */
@Composable
internal fun ImmersiveSwipeToQueue(song: SongsModel, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val reduceMotion = rememberReduceMotion()
    val threshold = with(LocalDensity.current) { 96.dp.toPx() }
    val maxDrag = threshold * 1.4f
    val offset = remember { Animatable(0f) }
    var armed by remember { mutableStateOf(false) }
    var swiping by remember { mutableStateOf(false) }
    var swipingRight by remember { mutableStateOf(true) }

    Box(modifier = Modifier.fillMaxWidth()) {
        if (swiping) {
            Box(
                contentAlignment = if (swipingRight) Alignment.CenterStart else Alignment.CenterEnd,
                modifier = Modifier
                    .matchParentSize()
                    .background(if (armed) Immersive.Accent else Immersive.Accent.copy(alpha = 0.6f)),
            ) {
                Icon(
                    Icons.AutoMirrored.Rounded.PlaylistAdd,
                    contentDescription = stringResource(R.string.add_to_queue_action_content_description),
                    tint = Immersive.OnAccent,
                    modifier = Modifier.padding(horizontal = 24.dp).size(26.dp),
                )
            }
        }
        Box(
            modifier = Modifier
                .graphicsLayer { translationX = offset.value }
                .background(Immersive.Page)
                .pointerInput(song.id) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            if (armed && song.spotifyTrackId.isNotBlank()) {
                                SongPlayer.addToQueue(song.spotifyTrackId)
                                Toast.makeText(context, context.getString(R.string.snackbar_added_to_queue), Toast.LENGTH_SHORT).show()
                            }
                            armed = false
                            scope.launch {
                                offset.animateTo(0f, if (reduceMotion) snap() else spring())
                                swiping = false
                            }
                        },
                        onDragCancel = {
                            armed = false
                            scope.launch {
                                offset.animateTo(0f, if (reduceMotion) snap() else spring())
                                swiping = false
                            }
                        },
                    ) { change, dx ->
                        change.consume()
                        val next = (offset.value + dx).coerceIn(-maxDrag, maxDrag)
                        if (!swiping && next != 0f) swiping = true
                        if (next != 0f) swipingRight = next > 0f
                        scope.launch { offset.snapTo(next) }
                        val nowArmed = abs(next) >= threshold
                        if (nowArmed && !armed) haptic.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                        armed = nowArmed
                    }
                },
        ) { content() }
    }
}

/**
 * "About" under an artist page: a title and a few lines of [text] that open on a tap, with a
 * More / Less link in the accent once the text has been seen to overflow. Whether there is
 * anything to expand is only known after layout at the collapsed line count, so the link waits
 * for that measurement — a two-line bio gets no toggle with nothing behind it.
 */
@Composable
internal fun ImmersiveAboutText(
    text: String,
    modifier: Modifier = Modifier,
    maxLines: Int = 3,
) {
    val reduceMotion = rememberReduceMotion()
    var expanded by remember(text) { mutableStateOf(false) }
    var clipped by remember(text) { mutableStateOf(false) }
    Column(modifier) {
        Text(
            text = text,
            style = Immersive.Body.copy(color = Immersive.TextSecondary),
            maxLines = if (expanded) Int.MAX_VALUE else maxLines,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { result -> if (!expanded) clipped = result.hasVisualOverflow },
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize(if (reduceMotion) snap() else spring())
                .let { m -> if (clipped || expanded) m.clickable { expanded = !expanded } else m },
        )
        if (clipped || expanded) {
            Text(
                text = stringResource(if (expanded) R.string.immersive_detail_less else R.string.immersive_detail_more),
                style = Immersive.Subhead.copy(color = Immersive.Accent),
                modifier = Modifier
                    .clickable { expanded = !expanded }
                    .padding(vertical = 6.dp),
            )
        }
    }
}

/** A hairline between two rows, starting [inset] from the edge. */
@Composable
internal fun ImmersiveRowDivider(inset: Dp) {
    Box(
        Modifier
            .padding(start = inset)
            .fillMaxWidth()
            .height(0.5.dp)
            .background(Immersive.Separator),
    )
}
