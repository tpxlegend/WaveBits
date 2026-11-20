// WaveBits Home Feed Components, GPL-3.0.
package com.music.spotui.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import com.music.spotui.ui.immersive.components.pressScalePointer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import app.wavebits.engine.R
import app.wavebits.host.android.WaveBitsRuntime
import app.wavebits.power.EnergySettings
import com.bumptech.glide.integration.compose.CrossFade
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.music.spotui.data.api.Features
import com.music.spotui.data.api.ProfileCache
import com.music.spotui.data.api.Response
import com.music.spotui.data.entity.HomeChipModel
import com.music.spotui.data.entity.HomeFeedModel
import com.music.spotui.data.entity.HomeItem
import com.music.spotui.data.entity.HomeSection
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.data.local.RecentPlayedStore
import com.music.spotui.di.Graph
import com.music.spotui.di.SongPlayer
import com.music.spotui.ui.navigation.Routes
import com.music.spotui.ui.navigation.TabReselect
import com.music.spotui.ui.navigation.albumRoute
import com.music.spotui.ui.navigation.artistRoute
import com.music.spotui.ui.navigation.playlistRoute
import com.music.spotui.ui.navigation.showRoute
import com.music.spotui.ui.immersive.shell.immersiveChromeScroll
import com.music.spotui.ui.immersive.shell.immersiveHazeSource
import com.music.spotui.ui.look.LocalEnergyPolicy
import com.music.spotui.ui.screens.ContextViewModel
import com.music.spotui.ui.viewmodel.HomeViewModel

// WaveBits palette tokens
private val WaveBitsPitch = Color(0xFF0A0A0C)
private val WaveBitsCard = Color(0xFF16161A)
private val WaveBitsElevated = Color(0xFF202026)
private val WaveBitsHairline = Color(0xFF2A2A32)
private val WaveBitsInk = Color(0xFFF5F5F7)
private val WaveBitsInkMuted = Color(0xFFB8B8C2)
private val WaveBitsInkDim = Color(0xFF7E7E8A)
private val SpotifyAccent = Color.White
private val YouTubeAccent = Color.White
private val JioSaavnAccent = Color.White

private const val COLLAPSE_DISTANCE_PX = 140f
private const val FADE_FRACTION = 0.045f

/**
 * WaveBits Home Feed.
 * Features:
 * - Collapsing header with service switcher pill, scaling title lockup, and profile avatar.
 * - Dynamic YouTube Music filter chips (`Relax`, `Workout`, `Energize`, `Focus`, etc.) and Spotify facets.
 * - Top edge fade mask as content scrolls underneath the header.
 * - "Play again" recent tracks row (`WaveBitsTrackRow`).
 * - 4-row snap-scrolling `WaveBitsQuickPicks` for song shelves (`tracks.size >= 4`).
 * - `WaveBitsFeedTile` (`152.dp` rounded `20.dp` cards), `WaveBitsFeedCard` hero cards, and circular artist grids.
 * - Infinite scroll continuation (`homeViewModel.loadMore()`) for YouTube Music shelves.
 */
@Composable
fun WaveBitsHomeFeedScreen(
    navController: NavController,
    homeViewModel: HomeViewModel,
    bottomPadding: Dp,
    modifier: Modifier = Modifier,
) {
    val home by homeViewModel.home.collectAsStateWithLifecycle()
    val loadingMore by homeViewModel.loadingMore.collectAsStateWithLifecycle()
    val feed = (home as? Response.Success)?.data
    val facet = homeViewModel.facet.value
    val isYouTube by WaveBitsRuntime.isYouTube.collectAsStateWithLifecycle()
    val isJioSaavn by WaveBitsRuntime.isJioSaavn.collectAsStateWithLifecycle()
    val recentSongs by RecentPlayedStore.recentSongs.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val backendMode by WaveBitsRuntime.backendMode.collectAsStateWithLifecycle()
    val switchVersion by com.music.spotui.ui.platform.PlatformManager.switchVersion.collectAsStateWithLifecycle()
    LaunchedEffect(backendMode, switchVersion) { ProfileCache.ensure(context) }

    var showPlatformSwitch by remember { mutableStateOf(false) }
    if (showPlatformSwitch) {
        PlatformSwitchDialog(onDismiss = { showPlatformSwitch = false })
    }

    var showAccountSheet by remember { mutableStateOf(false) }
    if (showAccountSheet) {
        val ytAvatar = if (isYouTube && WaveBitsRuntime.isStarted) {
            WaveBitsRuntime.youtubeAccount.accountAvatar.collectAsState().value
        } else null
        val ytName = if (isYouTube && WaveBitsRuntime.isStarted) {
            WaveBitsRuntime.youtubeAccount.accountName.collectAsState().value
        } else null
        val resolvedName = ProfileCache.name?.takeIf { it.isNotBlank() }
            ?: com.music.spotui.ui.platform.PlatformManager.sanitizeYouTubeName(ytName)
            ?: when {
                isYouTube -> "YouTube Music"
                isJioSaavn -> "JioSaavn"
                else -> "Spotify"
            }
        val resolvedAvatar = ProfileCache.imageUrl?.takeIf { it.isNotBlank() }
            ?: ytAvatar?.takeIf { it.isNotBlank() }
            ?: ""
        com.music.spotui.ui.immersive.screens.ImmersiveAccountSheet(
            account = com.music.spotui.data.entity.AccountModel(
                name = resolvedName,
                imageUrl = resolvedAvatar,
            ),
            navController = navController,
            onDismiss = { showAccountSheet = false },
        )
    }

    val scroll = rememberLazyListState()
    LaunchedEffect(Unit) {
        TabReselect.home.collect {
            if (scroll.firstVisibleItemIndex != 0 || scroll.firstVisibleItemScrollOffset != 0) {
                scroll.animateScrollToItem(0)
            }
        }
    }

    val topPadding = com.music.spotui.ui.immersive.shell.immersiveHeaderContentPadding()

    val sections = remember(feed) {
        val raw = feed?.sections.orEmpty()
        if (Features.PODCASTS) raw
        else raw.map { s -> s.copy(items = s.items.filterNot { it is HomeItem.Show }) }
            .filter { it.items.isNotEmpty() || it.tracks.isNotEmpty() }
    }

    val gridSection = remember(sections, isYouTube) {
        if (isYouTube) null else sections.firstOrNull()?.takeIf { it.title.isBlank() && it.items.isNotEmpty() }
    }
    val contentSections = remember(sections, gridSection, isJioSaavn, isYouTube, recentSongs, facet) {
        val base = if (gridSection != null) sections.drop(1) else sections
        val filtered = if (!isJioSaavn) {
            base.filterNot {
                it.title.equals("Play again", ignoreCase = true) ||
                    it.title.equals("Listen again", ignoreCase = true)
            }
        } else base
        if (isYouTube && facet.isEmpty() && recentSongs.isNotEmpty()) {
            val existingIdx = filtered.indexOfFirst {
                it.title.equals("Recents", ignoreCase = true) ||
                    it.title.equals("Recently Played", ignoreCase = true)
            }
            if (existingIdx >= 0) {
                val existing = filtered[existingIdx]
                val mergedTracks = (recentSongs + existing.tracks)
                    .distinctBy { it.spotifyTrackId.ifBlank { it.title } }
                    .take(20)
                filtered.toMutableList().apply {
                    this[existingIdx] = existing.copy(tracks = mergedTracks)
                }
            } else {
                listOf(
                    HomeSection(
                        title = "Recents",
                        items = emptyList(),
                        strapline = "RECENTLY PLAYED",
                        tracks = recentSongs.take(15),
                    )
                ) + filtered
            }
        } else {
            filtered
        }
    }

    LaunchedEffect(gridSection?.items?.map { it.name }) {
        val top = gridSection?.items.orEmpty().take(8).mapNotNull { item ->
            when (item) {
                is HomeItem.Album -> item.id.takeIf { it.isNotBlank() }?.let { "album" to it }
                is HomeItem.Playlist -> item.id.takeIf { it.isNotBlank() }?.let { "playlist" to it }
                else -> null
            }
        }
        if (top.isNotEmpty()) ContextViewModel.prefetchSoon(top)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(com.music.spotui.ui.immersive.Immersive.Page)
    ) {
        LazyColumn(
            state = scroll,
            contentPadding = PaddingValues(
                top = topPadding,
                bottom = bottomPadding + 24.dp,
            ),
            modifier = Modifier
                .fillMaxSize()
                .immersiveHazeSource()
                .immersiveChromeScroll(),
        ) {
            item(key = "home:chips", contentType = "chips") {
                if (isYouTube && !feed?.chips.isNullOrEmpty()) {
                    com.music.spotui.ui.immersive.screens.ImmersiveChipRow {
                        for (chip in feed?.chips.orEmpty()) {
                            com.music.spotui.ui.immersive.screens.ImmersiveChip(
                                label = chip.title,
                                selected = chip.params == facet,
                                onClick = { homeViewModel.selectFacet(chip.params) },
                            )
                        }
                    }
                } else {
                    com.music.spotui.ui.immersive.screens.ImmersiveChipRow {
                        com.music.spotui.ui.immersive.screens.ImmersiveChip(
                            label = stringResource(R.string.filter_chip_all_label),
                            selected = facet.isEmpty(),
                            onClick = { if (facet.isNotEmpty()) homeViewModel.selectFacet(facet) },
                        )
                        com.music.spotui.ui.immersive.screens.ImmersiveChip(
                            label = stringResource(R.string.recents_content_filter_music),
                            selected = facet == "music-chip",
                            onClick = { homeViewModel.selectFacet("music-chip") },
                        )
                        if (Features.PODCASTS) {
                            com.music.spotui.ui.immersive.screens.ImmersiveChip(
                                label = stringResource(R.string.ad_category_title_podcasts),
                                selected = facet == "podcasts-chip",
                                onClick = { homeViewModel.selectFacet("podcasts-chip") },
                            )
                            com.music.spotui.ui.immersive.screens.ImmersiveChip(
                                label = stringResource(R.string.audiobooks_title),
                                selected = facet == "audiobooks-chip",
                                onClick = { homeViewModel.selectFacet("audiobooks-chip") },
                            )
                        }
                    }
                }
            }

            if (isJioSaavn && recentSongs.isNotEmpty() && facet.isEmpty()) {
                item(key = "wavebits:play_again", contentType = "play_again") {
                    WaveBitsHeading(
                        text = "Play again",
                        strapline = "RECENTLY PLAYED",
                    )
                    WaveBitsTrackRow(
                        tracks = recentSongs.take(16),
                        contextKey = "spotify:collection:tracks",
                    )
                }
            }

            if (gridSection != null) {
                item(key = "wavebits:shortcuts", contentType = "shortcuts") {
                    WaveBitsShortcutGrid(navController, gridSection.items.take(8))
                }
            }

            when {
                contentSections.isNotEmpty() -> {
                    itemsIndexed(
                        items = contentSections,
                        key = { idx, sec -> "wavebits:sec:$idx:${sec.strapline.orEmpty()}:${sec.title}" },
                        contentType = { _, sec ->
                            when {
                                sec.tracks.isNotEmpty() && (sec.tracks.size >= 4 || sec.title.equals("Recents", ignoreCase = true)) -> "quick_picks"
                                sec.tracks.isNotEmpty() -> "track_row"
                                sec.items.isNotEmpty() && sec.items.all { it is HomeItem.Artist } -> "artist_grid"
                                else -> "carousel"
                            }
                        },
                    ) { idx, section ->
                        WaveBitsSectionContent(
                            navController = navController,
                            section = section,
                            sectionIndex = idx,
                        )
                    }

                    if (feed?.continuation != null) {
                        item(key = "wavebits:more:${feed.continuation}") {
                            LaunchedEffect(feed.continuation, contentSections.size) {
                                homeViewModel.loadMore()
                            }
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 28.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (loadingMore) {
                                    CircularProgressIndicator(
                                        color = WaveBitsInkMuted,
                                        strokeWidth = 2.dp,
                                        modifier = Modifier.size(22.dp),
                                    )
                                }
                            }
                        }
                    }

                    item(key = "wavebits:extensions", contentType = "extensions") {
                        com.music.spotui.extensions.ExtensionBlocks(
                            app.wavebits.extensions.Hook.HOME,
                            app.wavebits.extensions.Surface.HOME,
                            "{}",
                        )
                    }
                }

                home is Response.Loading -> {
                    item(key = "wavebits:loading") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 24.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Loader(SkeletonKind.Home)
                        }
                    }
                }

                else -> {
                    item(key = "wavebits:empty_or_error") {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 32.dp, vertical = 96.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                text = stringResource(if (home is Response.Error) R.string.home_error else R.string.home_empty),
                                color = WaveBitsInkMuted,
                                fontSize = 15.sp,
                                textAlign = TextAlign.Center,
                            )
                            if (home is Response.Error) {
                                Spacer(Modifier.height(14.dp))
                                Surface(
                                    onClick = { homeViewModel.retry() },
                                    shape = RoundedCornerShape(50),
                                    color = WaveBitsInk,
                                ) {
                                    Text(
                                        text = stringResource(R.string.home_retry),
                                        color = WaveBitsPitch,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 14.sp,
                                        modifier = Modifier.padding(horizontal = 22.dp, vertical = 10.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Previous WaveBits floating glass header with time greeting, PlatformSwitchButton, and ProfileDisc
        com.music.spotui.ui.immersive.shell.ImmersiveFloatingHeader(
            title = getTimeGreeting(),
        ) {
            PlatformSwitchButton(
                onClick = { showPlatformSwitch = true },
                size = 34.dp,
            )
            Spacer(Modifier.width(10.dp))
            com.music.spotui.ui.immersive.screens.ProfileDisc(
                onClick = { showAccountSheet = true },
                onLongClick = { showPlatformSwitch = true },
            )
        }
    }
}

private fun sanitizeYouTubeUiText(raw: String?): String = raw?.trim().orEmpty()

@Composable
fun WaveBitsHeading(
    text: String,
    strapline: String? = null,
) {
    val cleanStrapline = remember(strapline) { sanitizeYouTubeUiText(strapline) }
    val cleanText = remember(text) { sanitizeYouTubeUiText(text) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 24.dp, top = 28.dp, bottom = 14.dp),
    ) {
        if (cleanStrapline.isNotBlank()) {
            Text(
                text = cleanStrapline.uppercase(),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp,
                color = WaveBitsInkDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(bottom = 3.dp),
            )
        }
        Text(
            text = cleanText,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = WaveBitsInk,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.semantics { heading() },
        )
    }
}

@Composable
private fun WaveBitsSectionContent(
    navController: NavController,
    section: HomeSection,
    sectionIndex: Int,
) {
    if (section.title.isNotBlank() || !section.strapline.isNullOrBlank()) {
        WaveBitsHeading(
            text = section.title.ifBlank { "Featured" },
            strapline = section.strapline,
        )
    }

    val contextKey = remember(section.title, sectionIndex) {
        "spotify:list:home_${section.title.lowercase().replace(' ', '_')}_$sectionIndex"
    }

    if (section.tracks.isNotEmpty()) {
        if (section.tracks.size >= 4 || section.title.equals("Recents", ignoreCase = true)) {
            WaveBitsQuickPicks(
                tracks = section.tracks,
                contextKey = contextKey,
            )
        } else {
            WaveBitsTrackRow(
                tracks = section.tracks,
                contextKey = contextKey,
            )
        }
    }

    if (section.items.isNotEmpty()) {
        if (section.tracks.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
        }
        val allArtists = section.items.all { it is HomeItem.Artist }
        val isSpotlightAlbums = sectionIndex == 0 && section.tracks.isEmpty() && section.items.size in 1..2 &&
            section.items.all { it is HomeItem.Album }

        when {
            allArtists && section.items.size >= 3 -> {
                WaveBitsArtistGrid(navController = navController, artists = section.items)
            }
            isSpotlightAlbums -> {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    section.items.forEach { item ->
                        WaveBitsFeedCard(
                            item = item,
                            onClick = { onWaveBitsHomeItemClick(navController, item) },
                        )
                    }
                }
            }
            else -> {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    itemsIndexed(
                        items = section.items,
                        key = { idx, item -> "tile:${section.title}:${item.name}:$idx" },
                        contentType = { _, item -> if (item is HomeItem.Artist) "artist_tile" else "media_tile" },
                    ) { _, item ->
                        WaveBitsFeedTile(
                            item = item,
                            onClick = { onWaveBitsHomeItemClick(navController, item) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun WaveBitsQuickPicks(
    tracks: List<SongsModel>,
    contextKey: String,
) {
    val columns = remember(tracks) { tracks.take(20).chunked(4) }
    val rowState = rememberLazyListState()
    val fling = rememberSnapFlingBehavior(lazyListState = rowState)
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val columnWidth = if (screenWidthDp >= 600) {
        ((screenWidthDp.dp - 64.dp) / 2f).coerceIn(260.dp, 360.dp)
    } else {
        (screenWidthDp.dp - 44.dp).coerceIn(240.dp, 380.dp)
    }

    LazyRow(
        state = rowState,
        flingBehavior = fling,
        contentPadding = PaddingValues(horizontal = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        itemsIndexed(
            items = columns,
            key = { colIdx, col -> "qp_col_${colIdx}_${col.firstOrNull()?.spotifyTrackId ?: col.firstOrNull()?.id}" },
            contentType = { _, _ -> "qp_column" },
        ) { _, col ->
            Column(
                modifier = Modifier.width(columnWidth),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                for (song in col) {
                    WaveBitsQuickPickRow(
                        song = song,
                        onClick = { playWaveBitsSong(song, tracks, contextKey) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun WaveBitsQuickPickRow(
    song: SongsModel,
    onClick: () -> Unit,
) {
    val activeAccent = WaveBitsInk
    val songIdStr = remember(song.id) { song.id.toString() }
    val lowEnd = LocalEnergyPolicy.current.disableHeavyAnimations || EnergySettings.isLowEndDevice
    val isPlayingNow by remember(song.spotifyTrackId, songIdStr) {
        derivedStateOf {
            if (!Graph.currentSongState.playingState.value) {
                false
            } else {
                val nowPlayingUri = SongPlayer.nowPlaying.value.uri
                val tid = song.spotifyTrackId
                (tid.isNotBlank() && nowPlayingUri.endsWith(tid)) ||
                    nowPlayingUri.contains(songIdStr)
            }
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pressScalePointer(0.97f)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(WaveBitsCard),
            contentAlignment = Alignment.Center,
        ) {
            if (song.coverUri.isNotBlank()) {
                GlideImage(
                    transition = if (lowEnd) null else CrossFade,
                    model = song.coverUri,
                    contentDescription = song.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                ) { builder ->
                    val configured = builder.override(160).diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                    if (lowEnd) configured.format(DecodeFormat.PREFER_RGB_565).dontAnimate() else configured
                }
            }
            if (isPlayingNow) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.45f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_playing),
                        contentDescription = "Playing",
                        tint = activeAccent,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.title,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (isPlayingNow) activeAccent else WaveBitsInk,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = song.singer.ifBlank { song.album },
                fontSize = 13.sp,
                color = WaveBitsInkDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun WaveBitsTrackRow(
    tracks: List<SongsModel>,
    contextKey: String,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        itemsIndexed(
            items = tracks,
            key = { idx, t -> "tr_${t.spotifyTrackId.ifBlank { t.id.toString() }}_$idx" },
            contentType = { _, _ -> "track_tile" },
        ) { _, song ->
            WaveBitsTrackTile(
                song = song,
                onClick = { playWaveBitsSong(song, tracks, contextKey) },
            )
        }
    }
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun WaveBitsTrackTile(
    song: SongsModel,
    onClick: () -> Unit,
) {
    val activeAccent = WaveBitsInk
    val songIdStr = remember(song.id) { song.id.toString() }
    val lowEnd = LocalEnergyPolicy.current.disableHeavyAnimations || EnergySettings.isLowEndDevice
    val isPlayingNow by remember(song.spotifyTrackId, songIdStr) {
        derivedStateOf {
            if (!Graph.currentSongState.playingState.value) {
                false
            } else {
                val nowPlayingUri = SongPlayer.nowPlaying.value.uri
                val tid = song.spotifyTrackId
                (tid.isNotBlank() && nowPlayingUri.endsWith(tid)) ||
                    nowPlayingUri.contains(songIdStr)
            }
        }
    }

    Column(
        modifier = Modifier
            .width(128.dp)
            .pressScalePointer(0.97f)
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .size(128.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(WaveBitsCard),
            contentAlignment = Alignment.Center,
        ) {
            if (song.coverUri.isNotBlank()) {
                GlideImage(
                    transition = if (lowEnd) null else CrossFade,
                    model = song.coverUri,
                    contentDescription = song.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                ) { builder ->
                    val configured = builder.override(320).diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                    if (lowEnd) configured.format(DecodeFormat.PREFER_RGB_565).dontAnimate() else configured
                }
            }
            if (isPlayingNow) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.45f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_playing),
                        contentDescription = "Playing",
                        tint = activeAccent,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = song.title,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (isPlayingNow) activeAccent else WaveBitsInk,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = song.singer.ifBlank { song.album },
            fontSize = 12.sp,
            color = WaveBitsInkDim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun WaveBitsFeedTile(
    item: HomeItem,
    onClick: () -> Unit,
) {
    val isArtist = item is HomeItem.Artist
    val rawSubtitle = when (item) {
        is HomeItem.Album -> item.subtitle
        is HomeItem.Playlist -> item.subtitle
        is HomeItem.Artist -> stringResource(R.string.artist_subtitle)
        is HomeItem.Show -> item.subtitle
    }
    val subtitle = remember(rawSubtitle) { sanitizeYouTubeUiText(rawSubtitle) }
    Column(
        horizontalAlignment = if (isArtist) Alignment.CenterHorizontally else Alignment.Start,
        modifier = Modifier
            .width(152.dp)
            .pressScalePointer(0.97f)
            .pressHint { prefetchWaveBitsHomeItem(item) }
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .size(152.dp)
                .clip(if (isArtist) CircleShape else RoundedCornerShape(20.dp))
                .background(WaveBitsCard),
        ) {
            if (item.imageUrl.isNotBlank()) {
                val lowEnd = LocalEnergyPolicy.current.disableHeavyAnimations || EnergySettings.isLowEndDevice
                GlideImage(
                    transition = if (lowEnd) null else CrossFade,
                    model = item.imageUrl,
                    contentDescription = item.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                ) { builder ->
                    val configured = builder.override(384).diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                    if (lowEnd) configured.format(DecodeFormat.PREFER_RGB_565).dontAnimate() else configured
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = item.name,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = WaveBitsInk,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = if (isArtist) TextAlign.Center else TextAlign.Start,
        )
        if (subtitle.isNotBlank()) {
            Text(
                text = subtitle,
                fontSize = 12.sp,
                lineHeight = 15.sp,
                color = WaveBitsInkDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = if (isArtist) TextAlign.Center else TextAlign.Start,
            )
        }
    }
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun WaveBitsFeedCard(
    item: HomeItem,
    onClick: () -> Unit,
) {
    val rawSubtitle = when (item) {
        is HomeItem.Album -> item.subtitle
        is HomeItem.Playlist -> item.subtitle
        is HomeItem.Artist -> stringResource(R.string.artist_subtitle)
        is HomeItem.Show -> item.subtitle
    }
    val subtitle = remember(rawSubtitle) { sanitizeYouTubeUiText(rawSubtitle) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 560.dp)
                .pressScalePointer(0.98f)
                .clip(RoundedCornerShape(28.dp))
                .background(WaveBitsCard)
                .pressHint { prefetchWaveBitsHomeItem(item) }
                .clickable(onClick = onClick),
        ) {
        val lowEnd = LocalEnergyPolicy.current.disableHeavyAnimations || EnergySettings.isLowEndDevice
        if (item.imageUrl.isNotBlank()) {
            GlideImage(
                transition = if (lowEnd) null else CrossFade,
                model = item.imageUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            ) { builder ->
                builder
                    .override(128)
                    .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                    .format(DecodeFormat.PREFER_RGB_565)
                    .dontAnimate()
            }
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(Color.Black.copy(alpha = 0.56f)),
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(196.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(WaveBitsElevated),
            ) {
                if (item.imageUrl.isNotBlank()) {
                    GlideImage(
                        transition = if (lowEnd) null else CrossFade,
                        model = item.imageUrl,
                        contentDescription = item.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    ) { builder ->
                        val configured = builder.override(448).diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                        if (lowEnd) configured.format(DecodeFormat.PREFER_RGB_565).dontAnimate() else configured
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(
                text = item.name,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = WaveBitsInk,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
            if (subtitle.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    fontSize = 13.sp,
                    color = WaveBitsInkMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
        }
        }
    }
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun WaveBitsArtistGrid(
    navController: NavController,
    artists: List<HomeItem>,
) {
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val columnsPerRow = if (screenWidthDp >= 600) 6 else 3
    val rows = remember(artists, columnsPerRow) { artists.take(6).chunked(columnsPerRow) }
    val lowEnd = LocalEnergyPolicy.current.disableHeavyAnimations || EnergySettings.isLowEndDevice
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                row.forEach { artist ->
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .pressScalePointer(0.96f)
                            .clickable { onWaveBitsHomeItemClick(navController, artist) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f)
                                .clip(CircleShape)
                                .background(WaveBitsCard),
                        ) {
                            if (artist.imageUrl.isNotBlank()) {
                                GlideImage(
                                    transition = if (lowEnd) null else CrossFade,
                                    model = artist.imageUrl,
                                    contentDescription = artist.name,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                ) { builder ->
                                    val configured = builder.override(256).diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                                    if (lowEnd) configured.format(DecodeFormat.PREFER_RGB_565).dontAnimate() else configured
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = artist.name,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = WaveBitsInk,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
                repeat(columnsPerRow - row.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun WaveBitsShortcutGrid(
    navController: NavController,
    items: List<HomeItem>,
) {
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val columnsPerRow = if (screenWidthDp >= 720) 3 else 2
    val rows = remember(items, columnsPerRow) { items.chunked(columnsPerRow) }
    val lowEnd = LocalEnergyPolicy.current.disableHeavyAnimations || EnergySettings.isLowEndDevice
    Column(
        modifier = Modifier
            .padding(horizontal = 24.dp)
            .padding(top = 12.dp, bottom = 6.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        rows.forEach { rowItems ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                rowItems.forEach { item ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .weight(1f)
                            .height(54.dp)
                            .pressScalePointer(0.97f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(WaveBitsCard)
                            .pressHint { prefetchWaveBitsHomeItem(item) }
                            .clickable { onWaveBitsHomeItemClick(navController, item) },
                    ) {
                        Box(
                            modifier = Modifier
                                .size(54.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(WaveBitsElevated),
                        ) {
                            if (item.imageUrl.isNotBlank()) {
                                GlideImage(
                                    transition = if (lowEnd) null else CrossFade,
                                    model = item.imageUrl,
                                    contentDescription = item.name,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                ) { builder ->
                                    val configured = builder.override(160).diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                                    if (lowEnd) configured.format(DecodeFormat.PREFER_RGB_565).dontAnimate() else configured
                                }
                            }
                        }
                        Text(
                            text = item.name,
                            fontSize = 13.sp,
                            lineHeight = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = WaveBitsInk,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .padding(horizontal = 10.dp)
                                .weight(1f),
                        )
                    }
                }
                repeat(columnsPerRow - rowItems.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

private fun playWaveBitsSong(
    song: SongsModel,
    songs: List<SongsModel>,
    contextKey: String,
) {
    val playTarget = if (song.url.isNotBlank()) {
        song.url
    } else {
        val trackId = song.spotifyTrackId.ifBlank {
            if (WaveBitsRuntime.isYouTube.value) "yt_${song.id}" else "jio_${song.id}"
        }
        SongPlayer.buildSpotifyPlayQuery(trackId, song.title, song.singer)
    }
    SongPlayer.playSong(playTarget, songs = songs, contextUri = contextKey)
}

private fun prefetchWaveBitsHomeItem(item: HomeItem) {
    when (item) {
        is HomeItem.Album -> if (item.id.isNotBlank()) ContextViewModel.prefetch("album", item.id)
        is HomeItem.Playlist -> if (item.id.isNotBlank()) ContextViewModel.prefetch("playlist", item.id)
        else -> {}
    }
}

private fun onWaveBitsHomeItemClick(navController: NavController, item: HomeItem) {
    when (item) {
        is HomeItem.Album -> navController.navigate(albumRoute(item.name, item.artists.ifBlank { item.subtitle }, item.id))
        is HomeItem.Artist -> navController.navigate(artistRoute(item.name, item.id))
        is HomeItem.Show ->
            if (item.isEpisode) SongPlayer.playSong("episode:${item.id}")
            else navController.navigate(showRoute(item.id, item.name))
        is HomeItem.Playlist ->
            if (item.id.startsWith("song_") || item.id.startsWith("yt_RDAMVM") || item.id.startsWith("jio_song_")) {
                val trackId = when {
                    item.id.startsWith("yt_RDAMVM") -> "yt_" + item.id.removePrefix("yt_RDAMVM")
                    item.id.startsWith("jio_song_") -> "jio_" + item.id.removePrefix("jio_song_")
                    item.id.startsWith("song_") -> item.id.removePrefix("song_")
                    else -> item.id
                }
                val artistHint = item.subtitle.removePrefix("Recommended •").trim()
                val playArg = SongPlayer.buildSpotifyPlayQuery(trackId, item.name, artistHint)
                SongPlayer.playSong(playArg, contextUri = "spotify:playlist:${item.id}")
                navController.navigate(playlistRoute(item.id, item.name))
            } else if (item.id.isNotBlank()) navController.navigate(playlistRoute(item.id, item.name))
            else navController.navigate(albumRoute(item.name))
    }
}
