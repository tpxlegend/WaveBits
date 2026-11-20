package com.music.spotui.ui.screens

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Login
import androidx.compose.material.icons.rounded.Logout
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import com.music.spotui.data.discord.DiscordRpcManager
import com.music.spotui.di.Graph
import com.music.spotui.di.SongPlayer
import com.music.spotui.ui.components.bottomBarsSpace
import com.music.spotui.ui.components.pressable
import com.music.spotui.ui.components.spotifyImageUrl
import com.music.spotui.ui.theme.AppAccent
import com.music.spotui.ui.theme.AppBackground
import com.music.spotui.ui.theme.AppMuted
import com.music.spotui.ui.theme.AppSans
import com.music.spotui.ui.theme.AppText
import com.music.spotui.ui.theme.tone
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit

val DiscordBlurple = Color(0xFF5865F2)
private val DiscordDarkCard = Color(0xFF111214)
private val DiscordInnerSurface = Color(0xFF1E1F22)
private val DiscordButtonSurface = Color(0xFF2B2D31)
private val DiscordGreen = Color(0xFF23A55A)
private val DiscordYellow = Color(0xFFF0B232)
private val DiscordRed = Color(0xFFF23F43)

@OptIn(ExperimentalLayoutApi::class, ExperimentalGlideComposeApi::class)
@Composable
fun DiscordStatusScreen(navController: NavController) {
    val context = LocalContext.current
    val config by DiscordRpcManager.config.collectAsStateWithLifecycle()
    val status by DiscordRpcManager.status.collectAsStateWithLifecycle()
    val statusMsg by DiscordRpcManager.statusMessage.collectAsStateWithLifecycle()
    val discordUser by DiscordRpcManager.user.collectAsStateWithLifecycle()
    val hasToken by DiscordRpcManager.hasToken.collectAsStateWithLifecycle()
    val livePresence by DiscordRpcManager.currentPresence.collectAsStateWithLifecycle()

    // Also observe current song state directly so preview is always live
    val currentTitle by Graph.currentSongState.title
    val currentArtist by Graph.currentSongState.singer
    val currentAlbum by Graph.currentSongState.album
    val currentCover by Graph.currentSongState.coverUri
    val currentPlaying by Graph.currentSongState.playingState

    var tickMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(currentPlaying, config.showTimestamps) {
        while (true) {
            tickMs = SystemClock.elapsedRealtime()
            delay(1000L)
        }
    }

    var showWebLoginDialog by remember { mutableStateOf(false) }
    var showTokenDialog by remember { mutableStateOf(false) }

    if (showWebLoginDialog) {
        DiscordWebLoginScreen(
            onDismiss = { showWebLoginDialog = false },
            onTokenCaptured = { capturedToken ->
                showWebLoginDialog = false
                DiscordRpcManager.setToken(capturedToken)
                Toast.makeText(context, "Connected to Discord Music Status!", Toast.LENGTH_SHORT).show()
            }
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppBackground)
            .statusBarsPadding()
    ) {
        // Top App Bar
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 6.dp)
        ) {
            IconButton(
                onClick = { navController.popBackStack() },
                modifier = Modifier.testTag("discord_back_button")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Navigate back",
                    tint = AppText
                )
            }
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(DiscordBlurple.copy(alpha = 0.18f))
            ) {
                DiscordLogoIcon(size = 20.dp, tint = DiscordBlurple)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = "Discord Music Status",
                    color = AppText,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Rich Presence • Live Profile Activity",
                    color = AppMuted,
                    fontSize = 12.sp
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(8.dp))

            // ── 1. LIVE DISCORD PROFILE PREVIEW CARD ──
            val previewTitle = currentTitle.ifBlank { livePresence?.title.orEmpty() }.ifBlank { "Starboy" }
            val previewArtist = currentArtist.ifBlank { livePresence?.artist.orEmpty() }.ifBlank { "The Weeknd, Daft Punk" }
            val previewAlbum = currentAlbum.ifBlank { livePresence?.album.orEmpty() }.ifBlank { "Starboy (Deluxe)" }
            val previewCover = currentCover.ifBlank { livePresence?.coverUrl.orEmpty() }
            val previewPlaying = if (currentTitle.isNotBlank()) currentPlaying else (livePresence?.isPlaying ?: true)
            val previewDuration = SongPlayer.getDuration().takeIf { it > 0L } ?: livePresence?.durationMs?.takeIf { it > 0L } ?: 230_000L
            val previewPosition = if (currentTitle.isNotBlank()) {
                SongPlayer.getCurrentPosition().coerceIn(0L, previewDuration)
            } else {
                ((tickMs / 1000L) % 230L) * 1000L
            }
            val platformName = livePresence?.platform ?: "Spotify"

            DiscordRichPresencePreviewCard(
                user = discordUser,
                config = config,
                connectionStatus = status,
                title = previewTitle,
                artist = previewArtist,
                album = previewAlbum,
                coverUrl = previewCover,
                positionMs = previewPosition,
                durationMs = previewDuration,
                isPlaying = previewPlaying,
                platform = platformName,
            )

            Spacer(Modifier.height(16.dp))

            // ── 2. CONNECTION & ACCOUNT CARD ──
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(tone(0xFF1A1B20))
                    .border(1.dp, Color.White.copy(alpha = 0.07f), RoundedCornerShape(18.dp))
                    .padding(16.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val badgeColor = when (status) {
                        DiscordRpcManager.ConnectionStatus.CONNECTED -> DiscordGreen
                        DiscordRpcManager.ConnectionStatus.CONNECTING -> DiscordYellow
                        DiscordRpcManager.ConnectionStatus.ERROR -> DiscordRed
                        DiscordRpcManager.ConnectionStatus.DISCONNECTED -> AppMuted
                    }
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(badgeColor)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = statusMsg,
                        color = AppText,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    if (hasToken || config.webhookBridgeUrl.isNotBlank()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .testTag("discord_sync_now_button")
                                .clip(RoundedCornerShape(50))
                                .background(DiscordBlurple.copy(alpha = 0.16f))
                                .clickable {
                                    if (status != DiscordRpcManager.ConnectionStatus.CONNECTED) {
                                        DiscordRpcManager.connect()
                                    } else {
                                        DiscordRpcManager.pushPresenceNow()
                                    }
                                    Toast.makeText(context, "Syncing Discord Music Status...", Toast.LENGTH_SHORT).show()
                                }
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = "Sync Discord Status",
                                tint = DiscordBlurple,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = "Sync Now",
                                color = DiscordBlurple,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // Login with Discord WebView button
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("discord_webview_login_button")
                            .pressable(0.97f) { showWebLoginDialog = true }
                            .clip(RoundedCornerShape(12.dp))
                            .background(DiscordBlurple)
                            .padding(horizontal = 12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Login,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = if (hasToken) "Relogin Discord" else "Login with Discord",
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // Paste Token button
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("discord_token_input_button")
                            .pressable(0.97f) { showTokenDialog = true }
                            .clip(RoundedCornerShape(12.dp))
                            .background(tone(0xFF282A32))
                            .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 14.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Key,
                            contentDescription = "Set Token",
                            tint = AppText,
                            modifier = Modifier.size(17.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "Token",
                            color = AppText,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    if (hasToken) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("discord_logout_button")
                                .pressable(0.96f) {
                                    DiscordRpcManager.logout()
                                    Toast.makeText(context, "Discord account disconnected", Toast.LENGTH_SHORT).show()
                                }
                                .clip(RoundedCornerShape(12.dp))
                                .background(DiscordRed.copy(alpha = 0.15f))
                                .border(1.dp, DiscordRed.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Logout,
                                contentDescription = "Disconnect Discord",
                                tint = DiscordRed,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // ── 3. MASTER SWITCH & STATUS CUSTOMIZATION ──
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(tone(0xFF1A1B20))
                    .border(1.dp, Color.White.copy(alpha = 0.07f), RoundedCornerShape(18.dp))
                    .padding(vertical = 8.dp)
            ) {
                DiscordSwitchRow(
                    title = "Enable Discord Music Status",
                    subtitle = "Automatically broadcast your current song to your Discord profile",
                    checked = config.enabled,
                    testTag = "discord_enable_switch",
                    onCheckedChange = { enabled ->
                        DiscordRpcManager.updateConfig { it.copy(enabled = enabled) }
                    }
                )

                HorizontalDivider(color = tone(0xFF282A30), modifier = Modifier.padding(horizontal = 16.dp))

                DiscordSwitchRow(
                    title = "Show Playback Progress Bar",
                    subtitle = "Send live start & end timestamps so Discord displays the song progress bar",
                    checked = config.showTimestamps,
                    testTag = "discord_timestamps_switch",
                    onCheckedChange = { v ->
                        DiscordRpcManager.updateConfig { it.copy(showTimestamps = v) }
                    }
                )

                DiscordSwitchRow(
                    title = "Keep Status When Paused",
                    subtitle = "Show '(Paused)' on your Discord status instead of clearing it when playback pauses",
                    checked = config.showWhenPaused,
                    testTag = "discord_paused_switch",
                    onCheckedChange = { v ->
                        DiscordRpcManager.updateConfig { it.copy(showWhenPaused = v) }
                    }
                )

                DiscordSwitchRow(
                    title = "Prefix Artist with \"by\"",
                    subtitle = "Format artist line as 'by Artist' (Discord-Music-Status style)",
                    checked = config.prefixArtistWithBy,
                    testTag = "discord_prefix_by_switch",
                    onCheckedChange = { v ->
                        DiscordRpcManager.updateConfig { it.copy(prefixArtistWithBy = v) }
                    }
                )

                DiscordSwitchRow(
                    title = "Show Album Name on Artwork",
                    subtitle = "Display the album title when hovering or tapping the cover image on Discord",
                    checked = config.showAlbumInLargeText,
                    testTag = "discord_album_text_switch",
                    onCheckedChange = { v ->
                        DiscordRpcManager.updateConfig { it.copy(showAlbumInLargeText = v) }
                    }
                )
            }

            Spacer(Modifier.height(bottomBarsSpace() + 24.dp))
        }
    }

    // ── DIALOGS ──
    if (showTokenDialog) {
        DiscordTokenInputDialog(
            onDismiss = { showTokenDialog = false },
            onConfirm = { newToken ->
                showTokenDialog = false
                DiscordRpcManager.setToken(newToken)
                Toast.makeText(context, "Discord token saved & connecting...", Toast.LENGTH_SHORT).show()
            }
        )
    }
}

/**
 * Authentic Discord Rich Presence Profile Activity Card previewing how the user's current
 * WaveBits track appears on their Discord profile.
 */
@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun DiscordRichPresencePreviewCard(
    user: DiscordRpcManager.DiscordUser?,
    config: DiscordRpcManager.Config,
    connectionStatus: DiscordRpcManager.ConnectionStatus,
    title: String,
    artist: String,
    album: String,
    coverUrl: String?,
    positionMs: Long,
    durationMs: Long,
    isPlaying: Boolean,
    platform: String,
) {
    val context = LocalContext.current
    val activityHeaderName = "WaveBits"

    val formattedArtist = (if (config.prefixArtistWithBy) "by $artist" else artist).let {
        if (!isPlaying && config.showWhenPaused) "$it (Paused)" else it
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        DiscordBlurple.copy(alpha = 0.28f),
                        DiscordDarkCard,
                        DiscordDarkCard
                    )
                )
            )
            .border(1.dp, DiscordBlurple.copy(alpha = 0.35f), RoundedCornerShape(20.dp))
            .padding(16.dp)
    ) {
        // User Profile Header inside the Discord Preview Card
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(modifier = Modifier.size(46.dp)) {
                if (!user?.avatarUrl.isNullOrBlank()) {
                    GlideImage(
                        model = user?.avatarUrl,
                        contentDescription = "Discord avatar",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(CircleShape)
                    )
                } else {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(CircleShape)
                            .background(DiscordBlurple)
                    ) {
                        DiscordLogoIcon(size = 24.dp, tint = Color.White)
                    }
                }
                // Online status badge
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .align(Alignment.BottomEnd)
                        .clip(CircleShape)
                        .background(DiscordDarkCard)
                        .padding(2.dp)
                        .clip(CircleShape)
                        .background(
                            if (connectionStatus == DiscordRpcManager.ConnectionStatus.CONNECTED) DiscordGreen else DiscordYellow
                        )
                )
            }

            Spacer(Modifier.width(12.dp))

            Column(Modifier.weight(1f)) {
                Text(
                    text = user?.globalName ?: "Your Discord Profile",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (user != null) "@${user.username}" else "Live Rich Presence Preview",
                    color = Color(0xFFB5BAC1),
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(DiscordBlurple.copy(alpha = 0.2f))
                    .border(1.dp, DiscordBlurple.copy(alpha = 0.4f), RoundedCornerShape(50))
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(
                    text = "LIVE PREVIEW",
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 0.6.sp
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        // Inner Discord Activity Box
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(DiscordInnerSurface)
                .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(14.dp))
                .padding(14.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "LISTENING TO ${activityHeaderName.uppercase()}",
                    color = Color(0xFFB5BAC1),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 0.6.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = Icons.Rounded.MusicNote,
                    contentDescription = null,
                    tint = DiscordGreen,
                    modifier = Modifier.size(15.dp)
                )
            }

            Spacer(Modifier.height(10.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                // Album Cover with small badge
                Box(modifier = Modifier.size(68.dp)) {
                    if (!coverUrl.isNullOrBlank()) {
                        GlideImage(
                            model = spotifyImageUrl(coverUrl),
                            contentDescription = title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(10.dp))
                                .background(tone(0xFF2B2D31))
                        )
                    } else {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    Brush.linearGradient(
                                        listOf(DiscordBlurple, Color(0xFF9B59B6))
                                    )
                                )
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.MusicNote,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(32.dp)
                            )
                        }
                    }

                    // Small WaveBits / Platform badge in bottom-right of cover art
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(22.dp)
                            .align(Alignment.BottomEnd)
                            .clip(CircleShape)
                            .background(DiscordInnerSurface)
                            .padding(2.dp)
                            .clip(CircleShape)
                            .background(if (platform == "YouTube Music") Color(0xFFFF0033) else Color(0xFF1DB954))
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.MusicNote,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(12.dp)
                        )
                    }
                }

                Spacer(Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = formattedArtist,
                        color = Color(0xFFDBDEE1),
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (config.showAlbumInLargeText && album.isNotBlank()) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "on $album",
                            color = Color(0xFF949BA4),
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // Live Timestamps Progress Bar
            if (config.showTimestamps && durationMs > 0L) {
                Spacer(Modifier.height(12.dp))
                val progress = (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = formatDiscordTimestamp(positionMs),
                        color = Color(0xFFB5BAC1),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    LinearProgressIndicator(
                        progress = { progress },
                        color = Color.White,
                        trackColor = Color(0xFF4E5058),
                        drawStopIndicator = {},
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp)
                            .height(4.dp)
                            .clip(RoundedCornerShape(50))
                    )
                    Text(
                        text = formatDiscordTimestamp(durationMs),
                        color = Color(0xFFB5BAC1),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

@Composable
private fun DiscordSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    testTag: String,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .testTag(testTag)
            .toggleable(
                value = checked,
                role = Role.Switch,
                onValueChange = onCheckedChange
            )
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                text = title,
                color = AppText,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = subtitle,
                color = AppMuted,
                fontSize = 12.sp
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = DiscordBlurple,
                uncheckedThumbColor = AppMuted,
                uncheckedTrackColor = tone(0xFF2B2D31)
            )
        )
    }
}

/**
 * Full-screen in-app WebView that opens Discord login directly in the Activity window
 * (so soft keyboard, 2FA inputs, and hCaptcha challenges have full window focus) and
 * captures the user's authentication token via:
 * 1. `@JavascriptInterface` hooks on `Storage.prototype.setItem`, `XMLHttpRequest`, and `fetch`
 * 2. `webpackChunkdiscord_app` token module lookup
 * 3. Pre-body `iframe.contentWindow.localStorage` inspection
 * 4. SPA `doUpdateVisitedHistory` (`/login` -> `/app` or `/channels/@me`) + periodic polling
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun DiscordWebLoginScreen(
    onDismiss: () -> Unit,
    onTokenCaptured: (String) -> Unit,
) {
    var captured by remember { mutableStateOf(false) }
    var pageProgress by remember { mutableStateOf(15) }
    var currentUrl by remember { mutableStateOf("https://discord.com/login") }
    val webViewHolder = remember { arrayOfNulls<WebView>(1) }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    fun deliverToken(rawToken: String?) {
        if (captured) return
        val cleaned = sanitizeCapturedDiscordToken(rawToken)
        if (cleaned.isNotBlank()) {
            mainHandler.post {
                if (!captured) {
                    captured = true
                    webViewHolder[0]?.apply {
                        stopLoading()
                        clearFocus()
                    }
                    onTokenCaptured(cleaned)
                }
            }
        }
    }

    BackHandler {
        val wv = webViewHolder[0]
        if (wv != null && wv.canGoBack()) {
            wv.goBack()
        } else {
            onDismiss()
        }
    }

    // Continuous background poll while login screen is open so SPA transitions (which don't
    // trigger onPageFinished) are detected immediately.
    LaunchedEffect(Unit) {
        while (!captured) {
            delay(650L)
            val wv = webViewHolder[0]
            if (wv != null && !captured) {
                injectDiscordTokenHooks(wv)
                extractTokenFromWebView(wv) { token ->
                    deliverToken(token)
                }
            }
        }
    }

    val currentHost = remember(currentUrl) {
        runCatching { android.net.Uri.parse(currentUrl).host }.getOrNull()?.takeIf { it.isNotBlank() } ?: "discord.com"
    }
    val isCompleting = captured || currentUrl.contains("/channels") || currentUrl.endsWith("/app")

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AppBackground)
            .systemBarsPadding(),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header matching YouTubeLoginScreen
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(tone(0xFF222222))
                        .testTag("discord_webview_close_button"),
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Close",
                        tint = AppText,
                        modifier = Modifier.size(20.dp),
                    )
                }

                Spacer(Modifier.width(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Sign in with Discord",
                        color = AppText,
                        fontFamily = AppSans,
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 2.dp),
                    ) {
                        Icon(
                            Icons.Filled.Lock,
                            contentDescription = null,
                            tint = DiscordBlurple,
                            modifier = Modifier.size(12.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = currentHost,
                            color = AppText.copy(alpha = 0.60f),
                            fontFamily = AppSans,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                IconButton(
                    onClick = {
                        val wv = webViewHolder[0]
                        if (wv != null) {
                            extractTokenFromWebView(wv) { token -> deliverToken(token) }
                            wv.reload()
                        }
                    },
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(tone(0xFF222222))
                        .testTag("discord_webview_reload_button"),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = "Reload",
                        tint = AppText,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            // Progress bar matching YouTubeLoginScreen
            val animatedProgress by animateFloatAsState(
                targetValue = pageProgress / 100f,
                label = "discordLoginProgress",
            )
            val barAlpha by animateFloatAsState(
                targetValue = if (pageProgress in 1..99) 1f else 0f,
                label = "discordLoginProgressAlpha",
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.5.dp)
                    .padding(horizontal = 16.dp)
                    .graphicsLayer { alpha = barAlpha },
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(animatedProgress.coerceIn(0f, 1f))
                        .height(2.5.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(DiscordBlurple),
                )
            }

            // WebView container card matching YouTubeLoginScreen
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color(0xFF121212)),
            ) {
                AndroidView(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("discord_login_webview"),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                            isFocusable = true
                            isFocusableInTouchMode = true
                            requestFocus()

                            val cookieManager = CookieManager.getInstance()
                            cookieManager.setAcceptCookie(true)
                            cookieManager.setAcceptThirdPartyCookies(this, true)

                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.databaseEnabled = true
                            settings.allowFileAccess = false
                            settings.allowContentAccess = false
                            @Suppress("DEPRECATION")
                            settings.allowFileAccessFromFileURLs = false
                            @Suppress("DEPRECATION")
                            settings.allowUniversalAccessFromFileURLs = false
                            settings.javaScriptCanOpenWindowsAutomatically = false
                            settings.setSupportMultipleWindows(false)
                            settings.setGeolocationEnabled(false)
                            settings.loadsImagesAutomatically = true
                            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                settings.safeBrowsingEnabled = true
                            }
                            settings.useWideViewPort = true
                            settings.loadWithOverviewMode = true
                            // Use a clean Chrome User-Agent without "Android" or "; wv" so Discord
                            // renders the direct web login card instead of the "Open in Discord Android App" blocker.
                            settings.userAgentString =
                                "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

                            val webView = this
                            addJavascriptInterface(
                                object {
                                    @JavascriptInterface
                                    fun onToken(token: String?) {
                                        webView.post {
                                            if (isVerifiedDiscordOrigin(webView.url)) {
                                                deliverToken(token)
                                            }
                                        }
                                    }
                                },
                                "WaveBitsDiscordBridge"
                            )

                            webChromeClient = object : WebChromeClient() {
                                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                    pageProgress = newProgress
                                    if (newProgress >= 30 && view != null && !captured && isVerifiedDiscordOrigin(view.url)) {
                                        injectDiscordTokenHooks(view)
                                    }
                                }
                            }

                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(
                                    view: WebView?,
                                    request: WebResourceRequest?
                                ): Boolean {
                                    val url = request?.url?.toString().orEmpty()
                                    if (url.startsWith("discord://") || url.startsWith("intent://")) {
                                        return true
                                    }
                                    if (!isAllowedDiscordLoginNavigation(url)) {
                                        return true
                                    }
                                    if ((url.endsWith("/app") || url.contains("/channels/")) && isVerifiedDiscordOrigin(url)) {
                                        view?.let { wv ->
                                            extractTokenFromWebView(wv) { token -> deliverToken(token) }
                                        }
                                    }
                                    return false
                                }

                                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                    super.onPageStarted(view, url, favicon)
                                    if (!url.isNullOrBlank()) currentUrl = url
                                    if (isVerifiedDiscordOrigin(url)) {
                                        view?.let { injectDiscordTokenHooks(it) }
                                    }
                                }

                                override fun onPageCommitVisible(view: WebView?, url: String?) {
                                    super.onPageCommitVisible(view, url)
                                    if (isVerifiedDiscordOrigin(url)) {
                                        view?.let { wv ->
                                            injectDiscordTokenHooks(wv)
                                            extractTokenFromWebView(wv) { token -> deliverToken(token) }
                                        }
                                    }
                                }

                                override fun doUpdateVisitedHistory(
                                    view: WebView?,
                                    url: String?,
                                    isReload: Boolean
                                ) {
                                    super.doUpdateVisitedHistory(view, url, isReload)
                                    if (!url.isNullOrBlank()) currentUrl = url
                                    if (isVerifiedDiscordOrigin(url)) {
                                        view?.let { wv ->
                                            injectDiscordTokenHooks(wv)
                                            extractTokenFromWebView(wv) { token -> deliverToken(token) }
                                        }
                                    }
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    super.onPageFinished(view, url)
                                    if (!url.isNullOrBlank()) currentUrl = url
                                    if (isVerifiedDiscordOrigin(url)) {
                                        view?.let { wv ->
                                            injectDiscordTokenHooks(wv)
                                            extractTokenFromWebView(wv) { token -> deliverToken(token) }
                                        }
                                    }
                                }
                            }

                            webViewHolder[0] = this
                            loadUrl("https://discord.com/login")
                        }
                    },
                    update = { webView ->
                        webViewHolder[0] = webView
                    }
                )

                // Loading overlay once signed in, matching YouTubeLoginScreen
                androidx.compose.animation.AnimatedVisibility(
                    visible = isCompleting,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0xFF121212).copy(alpha = 0.92f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(
                                color = DiscordBlurple,
                                strokeWidth = 3.dp,
                                modifier = Modifier.size(36.dp),
                            )
                            Spacer(Modifier.height(16.dp))
                            Text(
                                text = "Connecting your Discord account...",
                                color = AppText,
                                fontFamily = AppSans,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 15.sp,
                            )
                        }
                    }
                }
            }

            // Security note matching YouTubeLoginScreen
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.Shield,
                    contentDescription = null,
                    tint = AppText.copy(alpha = 0.55f),
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Your credentials go straight to Discord. WaveBits only saves your session token.",
                    color = AppText.copy(alpha = 0.55f),
                    fontFamily = AppSans,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                )
            }
        }
    }
}

private fun isVerifiedDiscordOrigin(rawUrl: String?): Boolean {
    if (rawUrl.isNullOrBlank()) return false
    val uri = runCatching { android.net.Uri.parse(rawUrl) }.getOrNull() ?: return false
    if (uri.scheme?.lowercase() != "https") return false
    val host = uri.host?.lowercase()?.trimEnd('.') ?: return false
    return host == "discord.com" ||
        host == "ptb.discord.com" ||
        host == "canary.discord.com" ||
        host == "discordapp.com"
}

private fun isAllowedDiscordLoginNavigation(rawUrl: String?): Boolean {
    if (rawUrl.isNullOrBlank()) return false
    if (rawUrl == "about:blank") return true
    val uri = runCatching { android.net.Uri.parse(rawUrl) }.getOrNull() ?: return false
    if (uri.scheme?.lowercase() != "https") return false
    val host = uri.host?.lowercase()?.trimEnd('.') ?: return false
    val allowedDomains = listOf(
        "discord.com",
        "discordapp.com",
        "discord.gg",
        "hcaptcha.com",
        "cloudflare.com",
        "challenges.cloudflare.com",
        "google.com",
        "gstatic.com",
        "recaptcha.net",
    )
    return allowedDomains.any { d -> host == d || host.endsWith(".$d") }
}

private fun sanitizeCapturedDiscordToken(raw: String?): String {
    val cleaned = raw
        ?.trim()
        ?.removeSurrounding("\"")
        ?.removeSurrounding("'")
        ?.replace("\\\"", "")
        ?.removePrefix("Bearer ")
        ?.removePrefix("bearer ")
        ?.trim()
        .orEmpty()
    // Valid Discord user tokens are base64-encoded strings (typically 50+ chars with two dots)
    if (cleaned.length < 28 || cleaned.length > 256 || cleaned == "null" || cleaned == "undefined" || cleaned.contains(" ")) {
        return ""
    }
    return cleaned
}

/**
 * Injects hooks into `Storage.prototype.setItem`, `XMLHttpRequest`, and `window.fetch`
 * so the moment Discord receives or stores the user token on login/2FA, it is sent
 * directly to `window.WaveBitsDiscordBridge.onToken(...)`.
 */
private fun injectDiscordTokenHooks(webView: WebView?) {
    webView ?: return
    val hookJs = """
        (function() {
            try {
                if (window.__wbDiscordHooksInstalled) return;
                window.__wbDiscordHooksInstalled = true;

                function reportToken(val) {
                    if (!val || typeof val !== 'string') return;
                    var clean = val.replace(/^"+|"+$/g, '').trim();
                    if (clean.length > 28 && clean !== 'null' && clean !== 'undefined' && clean.indexOf(' ') === -1) {
                        if (window.WaveBitsDiscordBridge && window.WaveBitsDiscordBridge.onToken) {
                            window.WaveBitsDiscordBridge.onToken(clean);
                        }
                    }
                }

                // 1. Hook Storage.prototype.setItem before/after Discord deletes window.localStorage
                var origSetItem = Storage.prototype.setItem;
                Storage.prototype.setItem = function(k, v) {
                    try {
                        if (k === 'token' && v) {
                            reportToken(String(v));
                        }
                    } catch (e) {}
                    return origSetItem.apply(this, arguments);
                };

                // 2. Hook XMLHttpRequest Authorization header & /auth/ JSON responses
                var origSetHeader = XMLHttpRequest.prototype.setRequestHeader;
                XMLHttpRequest.prototype.setRequestHeader = function(name, value) {
                    try {
                        if (name && name.toLowerCase() === 'authorization' && value) {
                            reportToken(String(value));
                        }
                    } catch (e) {}
                    return origSetHeader.apply(this, arguments);
                };

                var origSend = XMLHttpRequest.prototype.send;
                XMLHttpRequest.prototype.send = function() {
                    try {
                        this.addEventListener('load', function() {
                            try {
                                if (this.responseText && this.responseText.indexOf('"token"') !== -1) {
                                    var data = JSON.parse(this.responseText);
                                    if (data && typeof data.token === 'string' && !data.mfa) {
                                        reportToken(data.token);
                                    }
                                }
                            } catch (e) {}
                        });
                    } catch (e) {}
                    return origSend.apply(this, arguments);
                };

                // 3. Hook window.fetch for /auth/ login or MFA token responses
                if (window.fetch) {
                    var origFetch = window.fetch;
                    window.fetch = function(input, init) {
                        try {
                            if (init && init.headers) {
                                var auth = init.headers['Authorization'] || init.headers['authorization'];
                                if (auth) reportToken(String(auth));
                            }
                        } catch (e) {}
                        return origFetch.apply(this, arguments).then(function(response) {
                            try {
                                var url = (typeof input === 'string') ? input : (input && input.url ? input.url : '');
                                if (url.indexOf('/auth/login') !== -1 || url.indexOf('/auth/mfa') !== -1 || url.indexOf('/auth/sessions') !== -1) {
                                    response.clone().json().then(function(data) {
                                        if (data && typeof data.token === 'string' && !data.mfa) {
                                            reportToken(data.token);
                                        }
                                    }).catch(function() {});
                                }
                            } catch (e) {}
                            return response;
                        });
                    };
                }
            } catch (err) {}
        })();
    """.trimIndent()
    webView.evaluateJavascript(hookJs, null)
}

private fun extractTokenFromWebView(webView: WebView?, onFound: (String) -> Unit) {
    webView ?: return
    val js = """
        (function() {
            function valid(t) {
                if (!t || typeof t !== 'string') return '';
                var c = t.replace(/^"+|"+$/g, '').trim();
                return (c.length > 28 && c !== 'null' && c !== 'undefined' && c.indexOf(' ') === -1) ? c : '';
            }
            // 1. Check webpackChunkdiscord_app token store (works on SPA after login without reload)
            try {
                if (window.webpackChunkdiscord_app) {
                    var wpToken = '';
                    window.webpackChunkdiscord_app.push([
                        [Math.random()],
                        {},
                        function(req) {
                            if (!req || !req.c) return;
                            for (var k in req.c) {
                                try {
                                    var m = req.c[k].exports;
                                    if (!m) continue;
                                    if (m.default && typeof m.default.getToken === 'function') {
                                        var t1 = m.default.getToken();
                                        if (valid(t1)) { wpToken = valid(t1); break; }
                                    }
                                    if (typeof m.getToken === 'function') {
                                        var t2 = m.getToken();
                                        if (valid(t2)) { wpToken = valid(t2); break; }
                                    }
                                } catch (e) {}
                            }
                        }
                    ]);
                    window.webpackChunkdiscord_app.pop();
                    if (wpToken) {
                        if (window.WaveBitsDiscordBridge) window.WaveBitsDiscordBridge.onToken(wpToken);
                        return wpToken;
                    }
                }
            } catch (e) {}

            // 2. Check clean iframe contentWindow.localStorage (attached to head/documentElement)
            try {
                var iframe = document.createElement('iframe');
                iframe.style.display = 'none';
                (document.head || document.documentElement || document.body).appendChild(iframe);
                var raw = iframe.contentWindow.localStorage.getItem('token');
                iframe.remove();
                var cleanRaw = valid(raw);
                if (cleanRaw) {
                    if (window.WaveBitsDiscordBridge) window.WaveBitsDiscordBridge.onToken(cleanRaw);
                    return cleanRaw;
                }
            } catch (e) {}

            // 3. Fallback direct window.localStorage
            try {
                var direct = valid(window.localStorage.getItem('token'));
                if (direct) return direct;
            } catch (e) {}

            return '';
        })();
    """.trimIndent()
    webView.evaluateJavascript(js) { result ->
        val cleaned = sanitizeCapturedDiscordToken(result)
        if (cleaned.isNotBlank()) {
            onFound(cleaned)
        }
    }
}

@Composable
private fun DiscordTokenInputDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var tokenInput by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = tone(0xFF24262E),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                DiscordLogoIcon(size = 22.dp, tint = DiscordBlurple)
                Spacer(Modifier.width(8.dp))
                Text("Discord Auth Token", color = AppText, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
        },
        text = {
            Column {
                Text(
                    text = "Paste your Discord token to enable direct Gateway Rich Presence updates on your profile. Your token is encrypted on-device and sent only to wss://gateway.discord.gg.",
                    color = AppMuted,
                    fontSize = 13.sp
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = tokenInput,
                    onValueChange = { tokenInput = it },
                    label = { Text("Discord Token") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = AppText,
                        unfocusedTextColor = AppText,
                        focusedBorderColor = DiscordBlurple,
                        unfocusedBorderColor = AppMuted.copy(alpha = 0.4f)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("discord_token_text_field")
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { if (tokenInput.isNotBlank()) onConfirm(tokenInput) },
                enabled = tokenInput.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = DiscordBlurple)
            ) {
                Text("Connect", color = Color.White, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = AppMuted)
            }
        }
    )
}

/**
 * Custom Canvas-drawn Discord icon so it renders crisply at any size without external bitmaps.
 */
@Composable
fun DiscordLogoIcon(
    size: Dp = 24.dp,
    tint: Color = DiscordBlurple,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height

        // Outer controller-like rounded silhouette of Discord Clyde
        val bodyPath = Path().apply {
            moveTo(w * 0.22f, h * 0.22f)
            quadraticTo(w * 0.36f, h * 0.16f, w * 0.44f, h * 0.20f)
            lineTo(w * 0.46f, h * 0.24f)
            quadraticTo(w * 0.50f, h * 0.23f, w * 0.54f, h * 0.24f)
            lineTo(w * 0.56f, h * 0.20f)
            quadraticTo(w * 0.64f, h * 0.16f, w * 0.78f, h * 0.22f)
            quadraticTo(w * 0.92f, h * 0.38f, w * 0.90f, h * 0.68f)
            quadraticTo(w * 0.80f, h * 0.82f, w * 0.66f, h * 0.80f)
            lineTo(w * 0.61f, h * 0.72f)
            quadraticTo(w * 0.50f, h * 0.75f, w * 0.39f, h * 0.72f)
            lineTo(w * 0.34f, h * 0.80f)
            quadraticTo(w * 0.20f, h * 0.82f, w * 0.10f, h * 0.68f)
            quadraticTo(w * 0.08f, h * 0.38f, w * 0.22f, h * 0.22f)
            close()
        }
        drawPath(bodyPath, color = tint)

        // Left & Right eyes cutout
        val eyeRadiusX = w * 0.085f
        val eyeRadiusY = h * 0.10f
        drawOval(
            color = Color(0xFF121318),
            topLeft = Offset(w * 0.35f - eyeRadiusX, h * 0.48f - eyeRadiusY),
            size = Size(eyeRadiusX * 2f, eyeRadiusY * 2f)
        )
        drawOval(
            color = Color(0xFF121318),
            topLeft = Offset(w * 0.65f - eyeRadiusX, h * 0.48f - eyeRadiusY),
            size = Size(eyeRadiusX * 2f, eyeRadiusY * 2f)
        )
    }
}

private fun formatDiscordTimestamp(ms: Long): String {
    val totalSec = TimeUnit.MILLISECONDS.toSeconds(ms.coerceAtLeast(0L))
    val min = totalSec / 60
    val sec = totalSec % 60
    return String.format("%02d:%02d", min, sec)
}
