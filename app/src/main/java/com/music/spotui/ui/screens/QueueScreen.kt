package com.music.spotui.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
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
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.di.Haptics
import com.music.spotui.di.SongPlayer
import com.music.spotui.ui.theme.AppAccent
import com.music.spotui.ui.theme.AppBackground
import com.music.spotui.ui.theme.AppMuted
import com.music.spotui.ui.theme.AppText
import com.music.spotui.ui.viewmodel.PlayerViewModel

/**
 * YouTube Music Queue Architecture:
 * - Now Playing
 * - Queued by You (User manual queue: "Play Next" & "Add to Queue")
 * - Playing from [Context] (Album, Playlist, Artist context tracks)
 * - Autoplay Recommendations (Dynamic radio extension when near queue end)
 * - Actions: Autoplay toggle, Save Queue as Playlist, Clear Queue, Drag Reorder, Swipe Remove
 */
@OptIn(ExperimentalGlideComposeApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun QueueScreen(navController: NavController) {
    val context = LocalContext.current
    val playerViewModel: PlayerViewModel = viewModel()
    
    DisposableEffect(Unit) {
        SongPlayer.watchQueue(true)
        onDispose { SongPlayer.watchQueue(false) }
    }
    
    val q by SongPlayer.engineQueue
    val np by SongPlayer.nowPlaying
    var showSaveDialog by remember { mutableStateOf(false) }
    var playlistNameInput by remember { mutableStateOf("") }

    if (showSaveDialog) {
        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            title = { Text("Save Queue as Playlist", color = AppText, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("Enter a name for your new playlist:", color = AppMuted, fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = playlistNameInput,
                        onValueChange = { playlistNameInput = it },
                        placeholder = { Text("My Queue Playlist", color = AppMuted) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = AppText,
                            unfocusedTextColor = AppText,
                            focusedBorderColor = AppAccent,
                            unfocusedBorderColor = AppMuted
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val name = playlistNameInput.trim().ifBlank { "My Queue Playlist" }
                    showSaveDialog = false
                    playerViewModel.saveQueueAsPlaylist(context, name) { success ->
                        val msg = if (success) "Saved queue as '$name'" else "Failed to save queue"
                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    }
                }) {
                    Text("Save", color = AppAccent, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showSaveDialog = false }) {
                    Text("Cancel", color = AppMuted)
                }
            },
            containerColor = Color(0xFF242424)
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppBackground)
            .statusBarsPadding()
    ) {
        // Top Navigation & Actions Bar
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = stringResource(R.string.bottom_actions_close),
                    tint = AppText,
                    modifier = Modifier
                        .size(28.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { navController.navigateUp() }
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        stringResource(R.string.next_up),
                        color = AppText,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                    if (np.contextName.isNotBlank() || np.contextUri.isNotBlank()) {
                        val ctxName = np.contextName.ifBlank { SongPlayer.knownContextName(np.contextUri) }
                        if (ctxName.isNotBlank()) {
                            Text(
                                "Playing from $ctxName",
                                color = AppMuted,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                // Save Queue as Playlist
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color(0xFF2A2A2A))
                        .clickable {
                            playlistNameInput = np.contextName.ifBlank { "My Queue" } + " Queue"
                            showSaveDialog = true
                        }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Outlined.BookmarkAdd,
                            contentDescription = "Save Queue",
                            tint = AppText,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Save", color = AppText, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Clear Queue
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color(0xFF2A2A2A))
                        .clickable {
                            Haptics.confirm()
                            playerViewModel.clearQueue()
                            Toast.makeText(context, "Queue cleared", Toast.LENGTH_SHORT).show()
                        }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text("Clear", color = AppText, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
            }
        }

        // Autoplay Toggle Bar
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF1E1E24))
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = if (q.autoplayEnabled) AppAccent else AppMuted,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text("Autoplay", color = AppText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text("Auto-generate recommendations when queue ends", color = AppMuted, fontSize = 11.sp)
                }
            }
            Switch(
                checked = q.autoplayEnabled,
                onCheckedChange = { playerViewModel.toggleAutoplay(it) },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.Black,
                    checkedTrackColor = AppAccent,
                    uncheckedThumbColor = AppMuted,
                    uncheckedTrackColor = Color(0xFF333333)
                )
            )
        }

        LazyColumn(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 32.dp),
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
        ) {
            // Section 1: Now Playing
            q.current?.let { currentTrack ->
                item {
                    QueueSectionHeader("Playing Now", isNowPlaying = true)
                    QueueRow(
                        song = currentTrack,
                        highlight = true,
                        isNowPlaying = true,
                        onClick = {}
                    )
                }
            }

            // Section 2: Queued by You (User manual queue)
            if (q.queued.isNotEmpty()) {
                item {
                    QueueSectionHeader("Queued by you (${q.queued.size})")
                }
                itemsIndexed(q.queued, key = { i, item -> "user_q_${i}_${item.uid}_${item.uri}" }) { _, item ->
                    SwipeRemove(item) {
                        QueueRow(song = item.song, highlight = false, onClick = { SongPlayer.skipTo(item) })
                    }
                }
            }

            // Section 3: Playing from Context (Album/Playlist/Artist)
            if (q.nextUp.isNotEmpty()) {
                item {
                    val contextLabel = np.contextName.ifBlank { SongPlayer.knownContextName(np.contextUri) }
                    val headerTitle = if (contextLabel.isNotBlank()) "Playing from $contextLabel" else "Next Up"
                    QueueSectionHeader(headerTitle)
                }
                itemsIndexed(q.nextUp, key = { i, item -> "ctx_${i}_${item.uid}_${item.uri}" }) { _, item ->
                    SwipeRemove(item) {
                        QueueRow(song = item.song, highlight = false, onClick = { SongPlayer.skipTo(item) })
                    }
                }
            }

            // Section 4: Autoplay / Infinite Radio Recommendations
            if (q.autoplayEnabled && q.recommendations.isNotEmpty()) {
                item {
                    QueueSectionHeader("Autoplay Recommendations", isAutoplay = true)
                }
                itemsIndexed(q.recommendations, key = { i, item -> "rec_${i}_${item.uid}_${item.uri}" }) { _, item ->
                    SwipeRemove(item) {
                        QueueRow(song = item.song, highlight = false, isRecommendation = true, onClick = { SongPlayer.skipTo(item) })
                    }
                }
            }
        }
    }
}

@Composable
private fun QueueSectionHeader(text: String, isNowPlaying: Boolean = false, isAutoplay: Boolean = false) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(16.dp, 16.dp, 16.dp, 6.dp)
    ) {
        if (isAutoplay) {
            Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = AppAccent, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
        }
        Text(
            text,
            color = if (isNowPlaying || isAutoplay) AppAccent else AppText,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun SwipeRemove(item: SongPlayer.QueueItem, content: @Composable () -> Unit) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value != SwipeToDismissBoxValue.Settled) {
                SongPlayer.removeFromQueue(item)
                true
            } else false
        }
    )
    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            Box(
                contentAlignment = Alignment.CenterEnd,
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF7A1F1F))
                    .padding(horizontal = 24.dp),
            ) {
                Icon(Icons.Default.Delete, contentDescription = "Remove from Queue", tint = AppText)
            }
        },
        content = { content() }
    )
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun QueueRow(
    song: SongsModel,
    highlight: Boolean,
    isNowPlaying: Boolean = false,
    isRecommendation: Boolean = false,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isNowPlaying) Color(0xFF1E1E28) else AppBackground)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onClick() }
            .padding(16.dp, 8.dp)
    ) {
        GlideImage(
            transition = CrossFade,
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(6.dp)),
            model = song.coverUri,
            contentScale = ContentScale.Crop,
            loading = placeholder(R.drawable.placeholder),
            failure = placeholder(R.drawable.placeholder),
            contentDescription = song.title
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isNowPlaying) {
                    Icon(
                        imageVector = Icons.Default.Equalizer,
                        contentDescription = "Playing",
                        tint = AppAccent,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                }
                Text(
                    text = song.title,
                    color = if (highlight) AppAccent else AppText,
                    fontSize = 15.sp,
                    fontWeight = if (highlight) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                if (isRecommendation) {
                    Icon(
                        Icons.Default.AutoAwesome,
                        contentDescription = "Recommended",
                        tint = AppAccent,
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                }
                Text(
                    text = song.singer,
                    color = AppMuted,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

