/*
 * WaveBits Immersive Artist Releases Screen.
 */
package com.music.spotui.ui.immersive.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import app.wavebits.engine.R
import com.music.spotui.data.api.Response
import com.music.spotui.data.entity.AlbumsModel
import com.music.spotui.ui.immersive.Immersive
import com.music.spotui.ui.immersive.components.ImmersiveListRow
import com.music.spotui.ui.immersive.components.ImmersiveSongRowSkeleton
import com.music.spotui.ui.immersive.components.SectionHeader
import com.music.spotui.ui.immersive.components.immersiveClickable
import com.music.spotui.ui.immersive.shell.ImmersiveTopBar
import com.music.spotui.ui.immersive.shell.immersiveHazeSource
import com.music.spotui.ui.immersive.shell.immersiveLargeTitle
import com.music.spotui.ui.immersive.shell.immersiveTopContentPadding
import com.music.spotui.ui.navigation.albumRoute
import com.music.spotui.ui.viewmodel.ArtistViewModel

/**
 * All of an artist's releases, in the Immersive look: filter chips (albums, singles and EPs,
 * featured on) over the latest release and then the releases grouped by kind, newest first.
 * Same data as the Classic releases page.
 */
@Composable
fun ImmersiveArtistReleasesScreen(navController: NavController, artistName: String, artistId: String) {
    val artistViewModel: ArtistViewModel = viewModel(key = "artist-releases:${artistId.ifBlank { artistName }}")
    remember(artistName, artistId) { artistViewModel.loadArtistOverview(artistName, artistId); true }
    val overview by artistViewModel.overview.collectAsState()

    val data = (overview as? Response.Success)?.data
    val featuredOn = data?.appearsOn ?: emptyList()
    // Releases grouped by kind. The catalogue names the kinds in capitals ("ALBUM", "SINGLE"…):
    // compared without case.
    val (albums, singles, untyped, latestHolder) = remember(data) {
        val rel = data?.popularReleases ?: emptyList()
        listOf(
            rel.filter { it.type.lowercase() == "album" || it.type.lowercase() == "compilation" },
            rel.filter { it.type.lowercase() == "single" || it.type.lowercase() == "ep" },
            rel.filter { it.type.lowercase() !in setOf("album", "compilation", "single", "ep") },
            rel.maxByOrNull { it.time }?.let(::listOf) ?: emptyList(),
        )
    }
    val latest = latestHolder.firstOrNull()
    var filter by remember { mutableStateOf("") } // "" | "albums" | "singles" | "featured"
    val listState = rememberLazyListState()
    val releasesTitle = stringResource(R.string.releases_title)

    fun openAlbum(album: AlbumsModel) {
        navController.navigate(albumRoute(album.name, album.artists.ifBlank { artistName }, album.spotifyId))
    }

    Box(Modifier.fillMaxSize().background(Immersive.Page)) {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(top = immersiveTopContentPadding(), bottom = Immersive.BottomChrome),
            modifier = Modifier.fillMaxSize().immersiveHazeSource(),
        ) {
            immersiveLargeTitle(title = releasesTitle, subtitle = data?.name?.ifBlank { artistName } ?: artistName)
            item(key = "chips") {
                Row(
                    modifier = Modifier.padding(horizontal = Immersive.Gutter).padding(top = 4.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ReleaseFilterChip(stringResource(R.string.artist_releases_albums), filter == "albums") {
                        filter = if (filter == "albums") "" else "albums"
                    }
                    ReleaseFilterChip(stringResource(R.string.artist_releases_singles), filter == "singles") {
                        filter = if (filter == "singles") "" else "singles"
                    }
                    if (featuredOn.isNotEmpty()) {
                        ReleaseFilterChip(stringResource(R.string.immersive_detail_featured_on), filter == "featured") {
                            filter = if (filter == "featured") "" else "featured"
                        }
                    }
                }
            }
            if (overview is Response.Loading) {
                items(6, key = { "skeleton:release:$it" }, contentType = { "skeleton_row" }) { index -> ImmersiveSongRowSkeleton(index) }
            }
            when (filter) {
                "albums" -> releaseRows(albums, "f_albums", ::openAlbum)
                "singles" -> releaseRows(singles, "f_singles", ::openAlbum)
                "featured" -> releaseRows(featuredOn, "f_featured", ::openAlbum)
                else -> {
                    latest?.let {
                        item(key = "latest-header", contentType = "section_header") { SectionHeader(stringResource(R.string.latest_release_title)) }
                        releaseRows(listOf(it), "latest", ::openAlbum)
                    }
                    if (albums.isNotEmpty()) {
                        item(key = "albums-header", contentType = "section_header") { SectionHeader(stringResource(R.string.artist_releases_albums)) }
                        releaseRows(albums, "albums", ::openAlbum)
                    }
                    if (singles.isNotEmpty()) {
                        item(key = "singles-header", contentType = "section_header") { SectionHeader(stringResource(R.string.artist_releases_singles)) }
                        releaseRows(singles, "singles", ::openAlbum)
                    }
                    if (untyped.isNotEmpty()) {
                        if (albums.isEmpty() && singles.isEmpty()) {
                            item(key = "untyped-header", contentType = "section_header") { SectionHeader(stringResource(R.string.popular_releases_section_title)) }
                        }
                        releaseRows(untyped, "untyped", ::openAlbum)
                    }
                }
            }
        }
        ImmersiveTopBar(
            title = releasesTitle,
            listState = listState,
            onBack = { navController.navigateUp() },
        )
    }
}

/** One [ImmersiveListRow] per release. */
private fun androidx.compose.foundation.lazy.LazyListScope.releaseRows(
    releases: List<AlbumsModel>,
    prefix: String,
    onOpen: (AlbumsModel) -> Unit,
) {
    items(
        items = releases,
        key = { "$prefix:${it.spotifyId.ifBlank { it.name + ":" + it.time }}" },
        contentType = { "release_row" },
    ) { album ->
        ReleaseRow(album) { onOpen(album) }
    }
}

@Composable
private fun ReleaseRow(album: AlbumsModel, onClick: () -> Unit) {
    ImmersiveListRow(
        title = album.name,
        subtitle = releaseDetail(album).ifBlank { album.artists },
        imageUrl = album.coverUri,
        round = false,
        onClick = onClick,
    )
}

/** A filter pill: the accent when it is on, the raised grey when it is not. */
@Composable
private fun ReleaseFilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .semantics {
                this.role = Role.Tab
                this.selected = selected
            }
            .immersiveClickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .clip(CircleShape)
                .background(if (selected) Immersive.Accent else Immersive.Raised)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                color = if (selected) Immersive.OnAccent else Immersive.Text,
                style = Immersive.Subhead.copy(
                    fontWeight = FontWeight.SemiBold,
                ),
            )
        }
    }
}
