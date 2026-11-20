package com.music.spotui.ui.screens

import com.music.spotui.ui.theme.tone
import com.music.spotui.ui.theme.AppText
import com.music.spotui.ui.theme.AppMuted

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Coffee
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import app.wavebits.engine.R
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.testTag
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.PersonOutline
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import com.music.spotui.ui.components.DownloadQualityPicker
import com.music.spotui.ui.components.StreamingQualityPicker
import com.music.spotui.ui.theme.AppBackground

/**
 * Settings, on one page: sound quality (streaming and downloads), the download source,
 * data and battery, display, language, and the player's own repair tools.
 */
@Composable
fun SettingsScreen(navController: NavController) {
    val energy by app.wavebits.power.EnergySettings.state.collectAsStateWithLifecycle()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Column(Modifier.fillMaxSize().background(AppBackground).statusBarsPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(4.dp)) {
            IconButton(onClick = { navController.popBackStack() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.accessibility_navigate_back), tint = AppText)
            }
            Text(stringResource(R.string.settings_title), color = AppText, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        // One tab per group, so the page is no longer one long list.
        var tab by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(0) }
        val tabTitles = listOf(
            stringResource(R.string.settings_section_playback),
            stringResource(R.string.settings_section_downloads),
            stringResource(R.string.settings_tab_appearance),
            "Discord",
            stringResource(R.string.settings_tab_about),
        )
        androidx.compose.material3.ScrollableTabRow(
            selectedTabIndex = tab, containerColor = Color.Transparent, contentColor = AppText, edgePadding = 12.dp,
            indicator = { positions ->
                androidx.compose.material3.TabRowDefaults.SecondaryIndicator(
                    Modifier.tabIndicatorOffset(positions[tab]), color = com.music.spotui.ui.theme.AppAccent)
            },
            divider = { HorizontalDivider(color = tone(0xFF2A2A2A)) },
        ) {
            tabTitles.forEachIndexed { i, title ->
                androidx.compose.material3.Tab(
                    selected = tab == i, onClick = { tab = i },
                    text = { Text(title, fontSize = 14.sp, fontWeight = if (tab == i) FontWeight.Bold else FontWeight.Normal) },
                    selectedContentColor = AppText, unselectedContentColor = AppMuted,
                )
            }
        }
        androidx.compose.runtime.LaunchedEffect(Unit) {
            com.music.spotui.data.api.ProfileCache.ensure(ctx)
            com.music.spotui.data.preferences.AppIconManager.init(ctx)
            com.music.spotui.data.preferences.LookPref.init(ctx)
        }
        val profileName = com.music.spotui.data.api.ProfileCache.name.orEmpty()
        val profileAvatar = com.music.spotui.data.api.ProfileCache.imageUrl
        val myId = com.music.spotui.data.api.ProfileCache.id
        val backendMode by app.wavebits.host.android.WaveBitsRuntime.backendMode.collectAsStateWithLifecycle()
        val isSpotify = backendMode == app.wavebits.host.android.BackendMode.SPOTIFY
        val isYouTube = backendMode == app.wavebits.host.android.BackendMode.YOUTUBE
        val isJioSaavn = backendMode == app.wavebits.host.android.BackendMode.JIOSAAVN
        val validSpotifyUserId = myId?.takeIf {
            isSpotify && it.isNotBlank() && it != "youtube" && it != "youtube_guest" && it != "jiosaavn"
        }
        var showAbout by remember { mutableStateOf(false) }
        var showSupportUpi by remember { mutableStateOf(false) }
        var showPlatformSwitch by remember { mutableStateOf(false) }
        var showJioProfile by remember { mutableStateOf(false) }
        if (showAbout) com.music.spotui.ui.components.AboutSheet(onDismiss = { showAbout = false })
        if (showSupportUpi) com.music.spotui.ui.components.SupportUpiSheet(onDismiss = { showSupportUpi = false })
        if (showPlatformSwitch) com.music.spotui.ui.components.PlatformSwitchDialog(onDismiss = { showPlatformSwitch = false })
        if (showJioProfile) {
            com.music.spotui.ui.components.JioSaavnProfileDialog(
                onDismiss = { showJioProfile = false },
                onSaved = {
                    showJioProfile = false
                    com.music.spotui.data.api.ProfileCache.ensure(ctx)
                },
            )
        }
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            SupportWavebitsCard(onClick = { showSupportUpi = true })

            // Active Account Card (Always visible at top of settings)
            val classicScope = androidx.compose.runtime.rememberCoroutineScope()
            val liveYtGuest = if (app.wavebits.host.android.WaveBitsRuntime.isStarted) {
                app.wavebits.host.android.WaveBitsRuntime.youtubeAccount.isGuest.collectAsStateWithLifecycle().value
            } else true
            val isYtGuest = remember(backendMode, profileName, myId, liveYtGuest) {
                com.music.spotui.ui.platform.PlatformManager.isYouTubeGuest(ctx)
            }
            var syncingYt by remember { mutableStateOf(false) }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        when {
                            validSpotifyUserId != null ->
                                navController.navigate(com.music.spotui.ui.navigation.profileRoute(validSpotifyUserId, profileName))
                            isJioSaavn -> showJioProfile = true
                            isYouTube -> showPlatformSwitch = true
                            else -> showPlatformSwitch = true
                        }
                    }
                    .padding(horizontal = 20.dp, vertical = 10.dp),
            ) {
                com.music.spotui.ui.components.ProfileAvatar(url = profileAvatar, name = profileName, size = 48.dp)
                Column(Modifier.padding(start = 14.dp).weight(1f)) {
                    Text(
                        profileName.ifBlank { stringResource(R.string.spotify_account) },
                        color = AppText,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        when {
                            validSpotifyUserId != null -> stringResource(R.string.view_profile)
                            isYouTube && isYtGuest -> "YouTube Music • Google Guest Mode (Tap to switch)"
                            isYouTube -> "YouTube Music • Tap for Google Account / Guest Mode"
                            isJioSaavn -> "JioSaavn • Tap to edit profile"
                            else -> "Tap to switch music platform"
                        },
                        color = AppMuted,
                        fontSize = 13.sp,
                    )
                }
                if (com.music.spotui.data.api.SupportReminder.level != com.music.spotui.data.api.SupportReminder.Level.NONE) {
                    com.music.spotui.ui.components.SupporterBadge(Modifier.padding(start = 10.dp))
                }
            }

            if (isYouTube) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(tone(0xFF1A1A1A))
                        .padding(14.dp),
                ) {
                    Text(
                        text = "YouTube Music Account",
                        color = AppMuted,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(8.dp))
                    if (isYtGuest) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .testTag("settings_connect_youtube_gmail")
                                .semantics {
                                    contentDescription = "Connect Gmail account to sync YouTube Music library"
                                }
                                .clickable(role = Role.Button) {
                                    com.music.spotui.ui.platform.PlatformManager.requestPlatformLogin(
                                        app.wavebits.host.android.BackendMode.YOUTUBE,
                                    )
                                }
                                .padding(vertical = 6.dp),
                        ) {
                            Icon(
                                Icons.Rounded.AccountCircle,
                                contentDescription = null,
                                tint = Color(0xFFFF4E45),
                                modifier = Modifier.size(22.dp),
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = "Connect Gmail Account",
                                    color = AppText,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = "Sync your library, liked songs & playlists for a better experience",
                                    color = AppMuted,
                                    fontSize = 12.sp,
                                )
                            }
                        }
                    } else {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .testTag("settings_youtube_account_status")
                                .padding(vertical = 4.dp),
                        ) {
                            Icon(
                                Icons.Rounded.AccountCircle,
                                contentDescription = "Google account connected",
                                tint = Color(0xFF1ED760),
                                modifier = Modifier.size(22.dp),
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = "Google Account Connected",
                                    color = AppText,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = profileName.ifBlank { "Synced" },
                                    color = AppMuted,
                                    fontSize = 12.sp,
                                )
                            }
                        }
                        HorizontalDivider(color = tone(0xFF2A2A2A), modifier = Modifier.padding(vertical = 4.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .testTag("settings_sync_youtube_library")
                                .semantics { contentDescription = "Sync YouTube Music library now" }
                                .clickable(enabled = !syncingYt, role = Role.Button) {
                                    syncingYt = true
                                    classicScope.launch {
                                        runCatching {
                                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                                if (app.wavebits.host.android.WaveBitsRuntime.isStarted) {
                                                    app.wavebits.host.android.WaveBitsRuntime.youtubeAccount.revalidate()
                                                    app.wavebits.host.android.WaveBitsRuntime.youtube.clearSessionCaches(clearUnlikedFilter = true)
                                                }
                                                com.music.spotui.data.preferences.clearYouTubeLibraryCache(ctx)
                                                com.music.spotui.data.api.ScreenCache.clearYouTubeBlocking()
                                                com.music.spotui.data.api.Api.HomeCache.clear()
                                                ContextViewModel.clearCache()
                                                com.music.spotui.data.api.ProfileCache.clearSession()
                                            }
                                            com.music.spotui.data.api.LibraryEdits.reloadNow()
                                            if (app.wavebits.host.android.WaveBitsRuntime.isStarted) {
                                                app.wavebits.host.android.WaveBitsRuntime.youtube.notifyLibraryChanged()
                                            }
                                            com.music.spotui.data.api.ProfileCache.ensure(ctx)
                                            android.widget.Toast.makeText(
                                                ctx,
                                                "YouTube Music library synced",
                                                android.widget.Toast.LENGTH_SHORT,
                                            ).show()
                                        }
                                        syncingYt = false
                                    }
                                }
                                .padding(vertical = 4.dp),
                        ) {
                            Icon(
                                Icons.Rounded.Sync,
                                contentDescription = null,
                                tint = AppText,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                text = if (syncingYt) "Syncing Library..." else "Sync Library Now",
                                color = AppText,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .testTag("settings_switch_youtube_gmail")
                                .semantics { contentDescription = "Switch Gmail account" }
                                .clickable(role = Role.Button) {
                                    runCatching {
                                        android.webkit.CookieManager.getInstance().removeAllCookies(null)
                                        android.webkit.CookieManager.getInstance().flush()
                                    }
                                    com.music.spotui.ui.platform.PlatformManager.requestPlatformLogin(
                                        app.wavebits.host.android.BackendMode.YOUTUBE,
                                    )
                                }
                                .padding(vertical = 4.dp),
                        ) {
                            Icon(
                                Icons.Rounded.SwapHoriz,
                                contentDescription = null,
                                tint = AppText,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                text = "Switch Gmail Account",
                                color = AppText,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .testTag("settings_youtube_switch_guest")
                                .semantics { contentDescription = "Switch YouTube Music to Guest mode" }
                                .clickable(role = Role.Button) {
                                    classicScope.launch {
                                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                            if (app.wavebits.host.android.WaveBitsRuntime.isStarted) {
                                                app.wavebits.host.android.WaveBitsRuntime.youtubeAccount.signOut()
                                                app.wavebits.host.android.WaveBitsRuntime.youtube.clearSessionCaches(clearUnlikedFilter = true)
                                            }
                                            com.music.spotui.data.preferences.clearYouTubeLibraryCache(ctx)
                                            com.music.spotui.data.api.ScreenCache.clearYouTubeBlocking()
                                            com.music.spotui.data.api.Api.HomeCache.clear()
                                            ContextViewModel.clearCache()
                                            com.music.spotui.data.api.ProfileCache.clearSession()
                                        }
                                        com.music.spotui.ui.platform.PlatformManager.saveYouTubeGuestSession(ctx, "Google Guest")
                                        com.music.spotui.ui.platform.PlatformManager.activatePlatform(
                                            ctx,
                                            app.wavebits.host.android.BackendMode.YOUTUBE,
                                        )
                                        com.music.spotui.data.api.LibraryEdits.reloadNow()
                                        android.widget.Toast.makeText(
                                            ctx,
                                            "Switched to YouTube Music Guest Mode",
                                            android.widget.Toast.LENGTH_SHORT,
                                        ).show()
                                    }
                                }
                                .padding(vertical = 4.dp),
                        ) {
                            Icon(
                                Icons.Rounded.PersonOutline,
                                contentDescription = null,
                                tint = AppMuted,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                text = "Switch to Guest Mode",
                                color = AppMuted,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                }
            }

            Divider()

            if (tab == 0) {
                Section(R.string.settings_section_playback)
                StreamingQualityPicker(title = R.string.quality_streaming_wifi_title)
                StreamingQualityPicker(cellular = true, title = R.string.quality_streaming_cell_title)
                var canvasWifiOnly by remember { mutableStateOf(com.music.spotui.data.preferences.isCanvasWifiOnly(ctx)) }
                OptionRow(
                    label = stringResource(R.string.canvas_wifi_only),
                    detail = stringResource(R.string.canvas_wifi_only_detail),
                    checked = canvasWifiOnly,
                    onChecked = { canvasWifiOnly = it; com.music.spotui.data.preferences.setCanvasWifiOnly(ctx, it) },
                )
                OptionRow(
                    label = stringResource(R.string.settings_autoplay_title),
                    detail = stringResource(R.string.settings_autoplay_detail),
                    checked = com.music.spotui.data.preferences.AutoplayPref.on,
                    onChecked = { com.music.spotui.data.preferences.AutoplayPref.set(ctx, it) },
                )
                CrossfadeRow()
                var keepPlaying by remember { mutableStateOf(app.wavebits.host.android.PlaybackService.keepsPlayingOnClose(ctx)) }
                OptionRow(
                    label = stringResource(R.string.settings_keep_playing_title),
                    detail = stringResource(R.string.settings_keep_playing_detail),
                    checked = keepPlaying,
                    onChecked = { keepPlaying = it; app.wavebits.host.android.PlaybackService.setKeepsPlayingOnClose(ctx, it) },
                )
                // Lyrics: word by word (karaoke) first, or line by line with Spotify's first.
                var wordSync by remember { mutableStateOf(com.music.spotui.data.preferences.isLyricsWordSync(ctx)) }
                OptionRow(
                    label = stringResource(R.string.lyrics_word_sync_title),
                    detail = stringResource(if (wordSync) R.string.lyrics_word_sync_on else R.string.lyrics_word_sync_off),
                    checked = wordSync,
                    onChecked = {
                        wordSync = it
                        com.music.spotui.data.preferences.setLyricsWordSync(ctx, it)
                        com.music.spotui.data.api.LyricsApi.wordSync = it
                        com.music.spotui.data.api.LyricsApi.forgetAll()
                    },
                )
                var extraLyrics by remember { mutableStateOf(com.music.spotui.data.preferences.isLyricsExtraSources(ctx)) }
                OptionRow(
                    label = "Extra Lyrics Sources (NetEase & Genius)",
                    detail = "Query NetEase Cloud Music and Genius when primary sources miss",
                    checked = extraLyrics,
                    onChecked = {
                        extraLyrics = it
                        com.music.spotui.data.preferences.setLyricsExtraSources(ctx, it)
                        com.music.spotui.data.api.LyricsApi.extraSources = it
                        com.music.spotui.data.api.LyricsApi.forgetAll()
                    },
                )
                Divider()

                Section(R.string.settings_section_data)
                val saverDetail = when {
                    energy.policy.saverEnabled -> stringResource(R.string.energy_saver_status_active)
                    energy.manualSaver && energy.isCharging -> stringResource(R.string.energy_saver_status_charging)
                    else -> stringResource(R.string.energy_saver_summary)
                }
                OptionRow(
                    label = stringResource(R.string.energy_saver_title),
                    detail = saverDetail,
                    checked = energy.manualSaver,
                    onChecked = app.wavebits.power.EnergySettings::setSaver,
                )
                OptionRow(
                    label = stringResource(R.string.data_saver_title),
                    detail = stringResource(R.string.data_saver_summary),
                    checked = com.music.spotui.data.DataSaver.enabled,
                    onChecked = com.music.spotui.data.DataSaver::set,
                )
                // Battery optimization off = music and Connect keep going with the screen off.
                var exempt by remember { androidx.compose.runtime.mutableStateOf(app.wavebits.power.BatteryExemption.isExempt(ctx)) }
                androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                    exempt = app.wavebits.power.BatteryExemption.isExempt(ctx)
                }
                OptionRow(
                    label = stringResource(R.string.background_playback_title),
                    detail = stringResource(if (exempt) R.string.background_playback_on else R.string.background_playback_off),
                    checked = exempt,
                    onChecked = {
                        if (exempt) ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                        else app.wavebits.power.BatteryExemption.request(ctx)
                    },
                )
            }
            if (tab == 1) {
                Section(R.string.settings_section_downloads)
                DownloadQualityPicker()
                val currentDownloadMode = app.wavebits.host.android.WaveBitsRuntime.backendMode.value
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                ) {
                    Text(stringResource(R.string.downloads_source_row_title), color = AppText, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        when (currentDownloadMode) {
                            app.wavebits.host.android.BackendMode.SPOTIFY -> "Built-in WaveBits Multi-Source Engine (Running in background)"
                            app.wavebits.host.android.BackendMode.YOUTUBE -> "Built-in YouTube Music Server & WaveBits Download Manager"
                            app.wavebits.host.android.BackendMode.JIOSAAVN -> "Built-in JioSaavn 320kbps Server & WaveBits Download Manager"
                        },
                        color = AppMuted, fontSize = 12.sp,
                    )
                }
                var dlWifiOnly by remember { mutableStateOf(com.music.spotui.data.preferences.isDownloadWifiOnly(ctx)) }
                OptionRow(
                    label = "Download Over Wi-Fi Only",
                    detail = "Only download music and albums when connected to a Wi-Fi network",
                    checked = dlWifiOnly,
                    onChecked = {
                        dlWifiOnly = it
                        com.music.spotui.data.preferences.setDownloadWifiOnly(ctx, it)
                    },
                )
                Divider()
                Section(R.string.settings_item_remove_downloads_title)
                RemoveDownloadsRow()
            }
            if (tab == 2) {

            Section(R.string.settings_section_display)
            OptionRow(
                label = stringResource(R.string.settings_amoled_theme),
                detail = stringResource(R.string.settings_amoled_theme_subtitle),
                checked = energy.blackPreference,
                onChecked = app.wavebits.power.EnergySettings::setBlackBackground,
            )
            OptionRow(
                label = stringResource(R.string.settings_item_canvas_title),
                detail = stringResource(if (energy.policy.saverEnabled) R.string.energy_saver_summary else R.string.video_settings_canvas_subtitle),
                checked = energy.canvasEnabled && !energy.policy.saverEnabled,
                enabled = !energy.policy.saverEnabled,
                onChecked = app.wavebits.power.EnergySettings::setCanvasEnabled,
            )
            OptionRow(
                label = stringResource(R.string.settings_music_video_title),
                detail = stringResource(R.string.settings_music_video_detail),
                checked = com.music.spotui.data.preferences.MusicVideoPref.on,
                onChecked = { com.music.spotui.data.preferences.MusicVideoPref.set(ctx, it) },
            )
            OptionRow(
                label = stringResource(R.string.settings_solid_nav_title),
                detail = stringResource(R.string.settings_solid_nav_detail),
                checked = com.music.spotui.data.preferences.SolidNavBar.on,
                onChecked = { com.music.spotui.data.preferences.SolidNavBar.set(ctx, it) },
            )
            AccentRow()
            com.music.spotui.ui.components.AppIconSelectorGrid()
            val glass = com.music.spotui.ui.immersive.shell.liquidGlassAvailability()
            OptionRow(
                label = stringResource(R.string.settings_liquid_glass_title),
                detail = stringResource(
                    when (glass) {
                        com.music.spotui.ui.immersive.shell.LiquidGlassAvailability.UNSUPPORTED -> R.string.settings_liquid_glass_unsupported
                        com.music.spotui.ui.immersive.shell.LiquidGlassAvailability.REDUCED -> R.string.settings_liquid_glass_reduced
                        com.music.spotui.ui.immersive.shell.LiquidGlassAvailability.AVAILABLE -> R.string.settings_liquid_glass_detail
                    },
                ),
                checked = com.music.spotui.data.preferences.LiquidGlassPref.on &&
                    glass != com.music.spotui.ui.immersive.shell.LiquidGlassAvailability.UNSUPPORTED,
                enabled = glass != com.music.spotui.ui.immersive.shell.LiquidGlassAvailability.UNSUPPORTED,
                onChecked = { com.music.spotui.data.preferences.LiquidGlassPref.set(ctx, it) },
            )
            OrientationRow()
            LanguageRow()
            }
            if (tab == 3) {
                DiscordStatusSettingsCard(navController)
            }
            if (tab == 4) {
            Section(R.string.settings_section_app)
            Column(
                Modifier.fillMaxWidth().clickable { showSupportUpi = true }.padding(horizontal = 20.dp, vertical = 10.dp),
            ) {
                Text("Support Wavebits", color = AppText, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text("Buy me a coffee with UPI to keep WaveBits 100% free and ad-free", color = AppMuted, fontSize = 12.sp)
            }
            AboutRow()
            OptionRow(
                label = "Automatic update checks",
                detail = "Check for new WaveBits updates at startup",
                checked = com.music.spotui.data.preferences.AutoUpdateCheckPref.on,
                onChecked = { com.music.spotui.data.preferences.AutoUpdateCheckPref.set(ctx, it) },
            )
            CheckUpdateRow()
            Column(
                Modifier.fillMaxWidth().clickable { showAbout = true }.padding(horizontal = 20.dp, vertical = 10.dp),
            ) {
                Text(stringResource(R.string.creator_biography_section_title), color = AppText, fontSize = 16.sp)
                Text(stringResource(R.string.about_detail), color = AppMuted, fontSize = 12.sp)
            }
            val appTabMode = app.wavebits.host.android.WaveBitsRuntime.backendMode.value
            Column(
                Modifier.fillMaxWidth().clickable {
                    com.music.spotui.ui.screens.AccountBrowser.open(
                        ctx,
                        com.music.spotui.ui.screens.AccountBrowser.accountUrlForPlatform(appTabMode),
                    )
                }.padding(horizontal = 20.dp, vertical = 10.dp),
            ) {
                Text(
                    com.music.spotui.ui.screens.AccountBrowser.accountPageTitle(appTabMode, stringResource(R.string.account_page_title)),
                    color = AppText,
                    fontSize = 16.sp,
                )
                Text(
                    com.music.spotui.ui.screens.AccountBrowser.accountPageDetail(appTabMode, stringResource(R.string.account_page_detail)),
                    color = AppMuted,
                    fontSize = 12.sp,
                )
            }
            // The hidden Web Player stuck or crashed: rebuild it without leaving the app.
            Column(
                Modifier.fillMaxWidth().clickable {
                    com.music.spotui.data.preferences.restartActivePlayer(ctx)
                    // Reported: nothing said the restart happened.
                    android.widget.Toast.makeText(ctx, ctx.getString(R.string.restart_player_done), android.widget.Toast.LENGTH_SHORT).show()
                }
                    .padding(horizontal = 20.dp, vertical = 10.dp),
            ) {
                Text(stringResource(R.string.restart_player_title), color = AppText, fontSize = 16.sp)
                Text(stringResource(R.string.restart_player_detail), color = AppMuted, fontSize = 12.sp)
            }
            // The report a tester hands us. The engine's log is otherwise reachable only through
            // adb logcat, which the person reporting the bug cannot run: the label is a literal
            // rather than a string resource so this change stays out of the shared string files.
            Column(
                Modifier.fillMaxWidth().clickable {
                    val file = runCatching { app.wavebits.host.android.Logbook.export(ctx) }.getOrNull()
                    if (file != null) shareDiagnosticReport(ctx, file)
                    else android.widget.Toast.makeText(ctx, ctx.getString(R.string.diagnostic_export_failed), android.widget.Toast.LENGTH_SHORT).show()
                }.padding(horizontal = 20.dp, vertical = 10.dp),
            ) {
                Text(stringResource(R.string.diagnostic_export_title), color = AppText, fontSize = 16.sp)
                Text(stringResource(R.string.diagnostic_export_detail), color = AppMuted, fontSize = 12.sp)
            }
            Text(
                stringResource(R.string.connect_device_menu_logout),
                color = AppText, fontSize = 16.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { com.music.spotui.di.SessionActions.logout(ctx) }
                    .padding(horizontal = 20.dp, vertical = 14.dp),
            )
            }
            // Clear of the mini player and the navigation bar, as every other page (96 dp left the
            // last rows, "Restart the player" among them, under the mini player).
            Spacer(Modifier.height(com.music.spotui.ui.components.bottomBarsSpace()))
        }
    }
}



@Composable
private fun DiscordStatusSettingsCard(navController: NavController) {
    val config by com.music.spotui.data.discord.DiscordRpcManager.config.collectAsStateWithLifecycle()
    val status by com.music.spotui.data.discord.DiscordRpcManager.status.collectAsStateWithLifecycle()
    val user by com.music.spotui.data.discord.DiscordRpcManager.user.collectAsStateWithLifecycle()
    val subtitle = when {
        status == com.music.spotui.data.discord.DiscordRpcManager.ConnectionStatus.CONNECTED && user != null ->
            "Active on Discord as @${user!!.username}"
        config.enabled ->
            "Enabled • Tap to configure Discord Rich Presence"
        else ->
            "Show your current WaveBits music on your Discord profile"
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
            .background(tone(0xFF1C1E26))
            .border(1.dp, DiscordBlurple.copy(alpha = 0.35f), androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
            .clickable { navController.navigate(com.music.spotui.ui.navigation.Routes.DiscordStatus.route) }
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(DiscordBlurple.copy(alpha = 0.2f)),
        ) {
            DiscordLogoIcon(size = 22.dp, tint = DiscordBlurple)
        }
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text("Discord Music Status", color = AppText, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = AppMuted, fontSize = 12.sp)
        }
        androidx.compose.material3.Switch(
            checked = config.enabled,
            onCheckedChange = { enabled ->
                com.music.spotui.data.discord.DiscordRpcManager.updateConfig { it.copy(enabled = enabled) }
            },
        )
    }
}

@Composable
private fun Section(title: Int) {
    Text(stringResource(title).uppercase(), color = com.music.spotui.ui.theme.AppAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 8.dp))
}

@Composable
private fun Divider() = HorizontalDivider(color = tone(0xFF2A2A2A), modifier = Modifier.padding(top = 8.dp))

/** The app's accent colour, for everyone (the same palette the supporter sheet uses). */
@Composable
private fun AccentRow() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
        Text(stringResource(R.string.supporter_accent), color = AppText, fontSize = 16.sp)
        Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(14.dp), modifier = Modifier.padding(top = 10.dp)) {
            for (a in com.music.spotui.data.api.SupportReminder.Accent.entries) {
                val selected = com.music.spotui.data.api.SupportReminder.accent == a
                val name = stringResource(when (a) {
                    com.music.spotui.data.api.SupportReminder.Accent.WHITE -> R.string.color_white
                    com.music.spotui.data.api.SupportReminder.Accent.GREEN -> R.string.color_green
                    com.music.spotui.data.api.SupportReminder.Accent.BLUE -> R.string.color_blue
                    com.music.spotui.data.api.SupportReminder.Accent.PURPLE -> R.string.color_purple
                    com.music.spotui.data.api.SupportReminder.Accent.PINK -> R.string.color_pink
                    com.music.spotui.data.api.SupportReminder.Accent.ORANGE -> R.string.color_orange
                })
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(40.dp)
                        .semantics { contentDescription = name; role = Role.RadioButton }
                        .clip(CircleShape).background(a.color)
                        .then(if (selected) Modifier.border(2.dp, if (a == com.music.spotui.data.api.SupportReminder.Accent.WHITE) Color.Black else AppText, CircleShape) else Modifier)
                        .clickable { com.music.spotui.data.api.SupportReminder.setAccent(ctx, a) },
                ) {
                    if (selected) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            tint = if (a.color.red * 0.299f + a.color.green * 0.587f + a.color.blue * 0.114f > 0.55f) Color.Black else Color.White,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * "Crossfade": the length of the fade at the end of a song. The label says what it is: the two
 * songs never overlap (the page plays one media element), so this is a fade, not a true
 * crossfade. The default is 0: off, as before.
 */
@Composable
private fun CrossfadeRow() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val pref = com.music.spotui.data.preferences.CrossfadePref
    androidx.compose.foundation.layout.Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
        Text(stringResource(R.string.settings_crossfade_title), color = AppText, fontSize = 16.sp)
        Text(
            stringResource(R.string.settings_crossfade_detail),
            color = AppMuted, fontSize = 12.sp,
            modifier = Modifier.padding(top = 2.dp),
        )
        Row(
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 10.dp),
        ) {
            for (v in listOf(0, 3, 6, 9, 12)) {
                val selected = pref.seconds == v
                Text(
                    if (v == 0) stringResource(R.string.settings_crossfade_off)
                    else stringResource(R.string.settings_crossfade_seconds, v),
                    color = if (selected) com.music.spotui.ui.immersive.Immersive.OnAccent else AppText, fontSize = 14.sp,
                    modifier = Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(50))
                        .background(if (selected) com.music.spotui.ui.theme.AppAccent else tone(0xFF2A2A2A))
                        .clickable { pref.set(ctx, v) }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
    }
}

/** Screen rotation: portrait (default), automatic or landscape. */
@Composable
private fun OrientationRow() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val pref = com.music.spotui.data.preferences.OrientationPref
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
        Text(stringResource(R.string.settings_rotation_title), color = AppText, fontSize = 16.sp)
        Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 10.dp)) {
            for ((value, label) in listOf(
                pref.PORTRAIT to R.string.settings_rotation_portrait,
                pref.AUTO to R.string.settings_rotation_auto,
                pref.LANDSCAPE to R.string.settings_rotation_landscape,
            )) {
                val selected = pref.mode == value
                Text(
                    stringResource(label),
                    color = if (selected) com.music.spotui.ui.immersive.Immersive.OnAccent else AppText, fontSize = 14.sp,
                    modifier = Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(50))
                        .background(if (selected) com.music.spotui.ui.theme.AppAccent else tone(0xFF2A2A2A))
                        .clickable { pref.set(ctx, value) }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }
}

/** "App language": Automatic (French or English) or any language with Spotify's labels. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun LanguageRow() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val current = remember { com.music.spotui.data.AppLanguage.current(ctx) }
    var picking by remember { androidx.compose.runtime.mutableStateOf(false) }
    val auto = stringResource(R.string.language_auto)
    fun label(tag: String) = if (tag == com.music.spotui.data.AppLanguage.SYSTEM) auto else com.music.spotui.data.AppLanguage.nativeName(tag)
    androidx.compose.foundation.layout.Column(
        Modifier.fillMaxWidth().clickable { picking = true }.padding(horizontal = 20.dp, vertical = 10.dp),
    ) {
        Text(stringResource(R.string.settings_item_app_language_title), color = AppText, fontSize = 16.sp)
        Text(label(current), color = AppMuted, fontSize = 12.sp)
    }
    if (picking) androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = { picking = false },
        containerColor = tone(0xFF282828),
    ) {
        androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            item {
                Text(stringResource(R.string.settings_item_app_language_title), color = AppText, fontSize = 18.sp,
                    fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                Text(stringResource(R.string.language_partial_note), color = AppMuted, fontSize = 12.sp,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp))
            }
            items(com.music.spotui.data.AppLanguage.AVAILABLE.size) { i ->
                val tag = com.music.spotui.data.AppLanguage.AVAILABLE[i]
                val selected = tag == current
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable {
                        picking = false
                        if (!selected) {
                            com.music.spotui.data.AppLanguage.apply(ctx, tag)
                            // Screens take the new language when the activity is rebuilt.
                            generateSequence(ctx) { (it as? android.content.ContextWrapper)?.baseContext }
                                .filterIsInstance<android.app.Activity>().firstOrNull()?.recreate()
                        }
                    }.padding(horizontal = 20.dp, vertical = 8.dp),
                ) {
                    androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                        Text(label(tag), color = if (selected) com.music.spotui.ui.theme.AppAccent else AppText, fontSize = 16.sp)
                        if (tag == com.music.spotui.data.AppLanguage.SYSTEM)
                            Text(stringResource(R.string.language_auto_detail), color = AppMuted, fontSize = 12.sp)
                    }
                    if (selected) androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Default.Check, null, tint = com.music.spotui.ui.theme.AppAccent)
                }
            }
        }
    }
}

/** "Remove all downloads", with the space they take; asks first, like the official app. */
@Composable
private fun RemoveDownloadsRow() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var ask by remember { androidx.compose.runtime.mutableStateOf(false) }
    val size = android.text.format.Formatter.formatShortFileSize(ctx, com.music.spotui.data.offline.OfflineStore.totalBytes())
    androidx.compose.foundation.layout.Column(
        Modifier.fillMaxWidth().clickable { ask = true }.padding(horizontal = 20.dp, vertical = 10.dp),
    ) {
        Text(stringResource(R.string.settings_item_remove_downloads_title), color = AppText, fontSize = 16.sp)
        Text(stringResource(R.string.downloads_size, size), color = AppMuted, fontSize = 12.sp)
    }
    if (ask) androidx.compose.material3.AlertDialog(
        onDismissRequest = { ask = false },
        containerColor = tone(0xFF282828),
        title = { Text(stringResource(R.string.settings_storage_remove_downloads_dialog_title), color = AppText) },
        text = { Text(stringResource(R.string.playlist_entity_remove_download_dialog_description), color = AppMuted) },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = {
                ask = false
                com.music.spotui.data.offline.OfflineDownloader.removeAll()
            }) { Text(stringResource(R.string.playlist_entity_remove_download_dialog_positive_button), color = com.music.spotui.ui.theme.AppAccent) }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = { ask = false }) {
                Text(stringResource(R.string.add_to_playlist_button_cancel), color = AppText)
            }
        },
    )
}



@Composable
private fun OptionRow(label: String, detail: String, checked: Boolean,
    enabled: Boolean = true, onChecked: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)
            .toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChecked)
            .padding(horizontal = 20.dp, vertical = 10.dp)) {
        androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
            Text(label, color = AppText, fontSize = 16.sp)
            Text(detail, color = AppMuted, fontSize = 12.sp)
        }
        androidx.compose.material3.Switch(checked = checked, enabled = enabled, onCheckedChange = null)
    }
}

/** The installed version and, when a newer one is known, the update on offer (its card opens on tap). */
@Composable
private fun AboutRow() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    // Also what "Later" put aside: the card in the app offers it again from here.
    val live = com.music.spotui.updateRelease.value
    val known = live ?: remember { com.music.spotui.data.api.UpdateChecker.known(ctx) }
    Column(
        Modifier.fillMaxWidth()
            .then(if (known != null) Modifier.clickable { com.music.spotui.updateRelease.value = known } else Modifier)
            .padding(horizontal = 20.dp, vertical = 10.dp),
    ) {
        Text(stringResource(R.string.settings_about_version, app.wavebits.engine.BuildConfig.VERSION_NAME), color = AppText, fontSize = 16.sp)
        if (known != null) {
            Text(stringResource(R.string.settings_update_available, known.version), color = com.music.spotui.ui.theme.AppAccent, fontSize = 14.sp,
                fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 2.dp))
            Text(stringResource(R.string.settings_update_available_detail), color = AppMuted, fontSize = 12.sp)
        }
    }
}

/** "Check for updates now": the launch check can be off; this one always asks. */
@Composable
private fun CheckUpdateRow() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var checking by remember { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.foundation.layout.Column(
        Modifier.fillMaxWidth().clickable(enabled = !checking) {
            if (checking) return@clickable
            checking = true
            scope.launch {
                val found = runCatching { com.music.spotui.data.api.UpdateChecker.check(ctx, manual = true) }.getOrNull()
                checking = false
                // The card is the app's one update UI: fill it, it shows itself.
                if (found != null) com.music.spotui.updateRelease.value = found
                else android.widget.Toast.makeText(ctx, ctx.getString(R.string.settings_update_check_latest), android.widget.Toast.LENGTH_SHORT).show()
            }
        }.padding(horizontal = 20.dp, vertical = 10.dp),
    ) {
        Text(
            stringResource(if (checking) R.string.settings_update_checking else R.string.settings_check_update_title),
            color = AppText, fontSize = 16.sp,
        )
        Text(stringResource(R.string.settings_check_update_detail), color = AppMuted, fontSize = 12.sp)
    }
}

/**
 * Hands the exported report to the share sheet. The file goes through the app's FileProvider;
 * if the provider refuses the path (external storage absent), the report's own path is shared
 * instead, so the tester still has something to send.
 */
internal fun shareDiagnosticReport(context: android.content.Context, file: java.io.File) {
    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(android.content.Intent.EXTRA_SUBJECT, "WaveBits diagnostic report")
    }
    val uri = runCatching {
        androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }.getOrNull()
    if (uri != null) {
        send.type = "application/octet-stream"
        send.putExtra(android.content.Intent.EXTRA_STREAM, uri)
        send.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    } else {
        send.putExtra(android.content.Intent.EXTRA_TEXT, "WaveBits diagnostic report: ${file.absolutePath}")
    }
    context.startActivity(android.content.Intent.createChooser(send, null).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
}

@Composable
private fun SupportWavebitsCard(onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
            .background(Color(0xFF381B24))
            .border(1.dp, Color(0xFFF09886).copy(alpha = 0.4f), androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(Color(0xFF522331))
        ) {
            Icon(
                imageVector = androidx.compose.material.icons.Icons.Outlined.Coffee,
                contentDescription = null,
                tint = Color(0xFFF09886),
                modifier = Modifier.size(20.dp)
            )
        }
        Column(Modifier.padding(start = 14.dp).weight(1f)) {
            Text(
                text = "Support Wavebits",
                color = AppText,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Buy me a coffee with UPI to keep WaveBits free",
                color = Color(0xFFF09886),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(50))
                .background(Color(0xFFF09886))
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Text(
                text = "Donate",
                color = Color(0xFF221114),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
