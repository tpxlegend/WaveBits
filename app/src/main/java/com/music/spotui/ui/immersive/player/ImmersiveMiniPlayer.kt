/*
 * WaveBits Immersive MiniPlayer: 56dp glass capsule with cover, track credits, play/pause,
 * next button, horizontal track-swipe gesture, and pull-up to expand.
 */
package com.music.spotui.ui.immersive.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import app.wavebits.engine.R
import com.bumptech.glide.integration.compose.CrossFade
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import com.bumptech.glide.integration.compose.placeholder
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.music.spotui.di.Haptics
import com.music.spotui.di.SongPlayer
import com.music.spotui.ui.immersive.Immersive
import com.music.spotui.ui.immersive.shell.immersiveGlassSurface
import com.music.spotui.ui.navigation.PlayerSheet
import com.music.spotui.ui.viewmodel.PlayerViewModel
import kotlin.math.abs

private val GLYPH_SLOT = 48.dp
private val GLYPH_SIZE = 30.dp
private val ART_CORNER = 8.dp
private val MINI_HEIGHT = 56.dp

/** How far the capsule is swiped along to change track. */
private val TRACK_SWIPE_THRESHOLD = 72.dp

/**
 * The floating capsule over the pages: cover, title and artist, play/pause and next. A tap (or a
 * pull up) opens the player ([PlayerSheet.show]); a swipe along it changes track. Nothing is drawn
 * while nothing is playing. Give it the horizontal margin and the room above the tab bar through
 * [modifier]; it fills the width it is given.
 */
@OptIn(ExperimentalGlideComposeApi::class)
@Composable
fun ImmersiveMiniPlayer(navController: NavController, modifier: Modifier = Modifier) {
    val vm: PlayerViewModel = viewModel()
    val title = vm.currentSongTitle.value
    if (title.isBlank()) return
    val singer = vm.currentSongSinger.value
    val cover = vm.currentSongCoverUri.value
    val playing = vm.currentSongPlayingState.value
    val adPlaying = SongPlayer.adPlaying
    val buffering = SongPlayer.isBuffering
    val shape = remember { RoundedCornerShape(MINI_HEIGHT / 2) }
    val capsuleSource = remember { MutableInteractionSource() }
    val playSource = remember { MutableInteractionSource() }
    val nextSource = remember { MutableInteractionSource() }
    val lowEnd = app.wavebits.power.EnergySettings.isLowEndOrSaver
    val capsuleDesc = remember(title, singer, adPlaying) {
        if (adPlaying) title else if (singer.isNotBlank()) "$title, $singer" else title
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(MINI_HEIGHT)
            // Where the Classic sheet rises from; kept up to date for the pages that read it.
            .onGloballyPositioned { PlayerSheet.miniTop = it.boundsInWindow().top }
            .miniPlayerGestures()
            .immersiveGlassSurface(
                shape,
                solid = com.music.spotui.data.preferences.SolidNavBar.on,
                interactionSource = capsuleSource,
            )
            .clip(shape)
            .semantics { contentDescription = capsuleDesc }
            .clickable(
                interactionSource = capsuleSource,
                indication = null,
                role = Role.Button,
            ) { PlayerSheet.show() }
            .padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlideImage(
            model = cover.takeIf { it.isNotBlank() },
            contentDescription = null,
            contentScale = ContentScale.Crop,
            transition = if (lowEnd) null else CrossFade,
            loading = placeholder(R.drawable.placeholder),
            failure = placeholder(R.drawable.placeholder),
            modifier = Modifier.size(40.dp).clip(RoundedCornerShape(ART_CORNER)).background(Color.Black.copy(alpha = 0.18f)),
        ) { builder ->
            builder.override(144).diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = if (adPlaying) stringResource(R.string.immersive_player_ad) else title,
                style = Immersive.BodyStrong.copy(fontSize = 15.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!adPlaying) {
                Text(text = singer, style = Immersive.Subhead, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (buffering) {
            Box(Modifier.size(GLYPH_SLOT), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Immersive.Text, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
            }
        } else {
            Box(
                Modifier
                    .size(GLYPH_SLOT)
                    .clickable(interactionSource = playSource, indication = null, role = Role.Button) {
                        Haptics.click()
                        // The same optimistic update as the Classic mini player: the glyph flips at once.
                        if (playing) SongPlayer.pause() else SongPlayer.play()
                        vm.updateSongState(cover, title, singer, !playing, vm.currentSongId.value, vm.currentSongIndex.value, vm.currentSongAlbum.value)
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = stringResource(if (playing) R.string.immersive_player_pause else R.string.immersive_player_play),
                    tint = Immersive.Text,
                    modifier = Modifier.size(GLYPH_SIZE),
                )
            }
        }
        Box(
            Modifier
                .size(GLYPH_SLOT)
                .clickable(interactionSource = nextSource, indication = null, role = Role.Button) {
                    Haptics.click()
                    SongPlayer.swipeNext()
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.SkipNext,
                contentDescription = stringResource(R.string.immersive_player_next),
                tint = Immersive.Text,
                modifier = Modifier.size(GLYPH_SIZE),
            )
        }
    }
}


/**
 * A swipe along the capsule changes track (past [TRACK_SWIPE_THRESHOLD]); a pull up opens the
 * player. A tap is left to the capsule's own click.
 */
private fun Modifier.miniPlayerGestures(): Modifier = pointerInput(Unit) {
    val threshold = TRACK_SWIPE_THRESHOLD.toPx()
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val slopped = awaitTouchSlopOrCancellation(down.id) { change, _ -> change.consume() } ?: return@awaitEachGesture
        val moved = slopped.position - down.position
        if (abs(moved.y) > abs(moved.x)) {
            if (moved.y < 0f) PlayerSheet.show()
            drag(slopped.id) { it.consume() }
            return@awaitEachGesture
        }
        var dragged = moved.x
        val finished = drag(slopped.id) { change ->
            dragged += change.positionChange().x
            change.consume()
        }
        if (!finished) return@awaitEachGesture
        when {
            dragged <= -threshold -> SongPlayer.swipeNext()
            dragged >= threshold -> SongPlayer.previous()
        }
    }
}
