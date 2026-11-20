package com.music.spotui.ui.navigation

import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.navigation.NamedNavArgument
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import com.music.spotui.ui.theme.AppBackground
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.navArgument
import com.music.spotui.ui.screens.AlbumByIdScreen
import com.music.spotui.ui.screens.ContextScreen
import com.music.spotui.ui.screens.ArtistReleasesScreen
import com.music.spotui.ui.screens.ArtistScreen
import com.music.spotui.ui.screens.PlayerScreen
import com.music.spotui.ui.screens.SearchScreen
import com.music.spotui.ui.viewmodel.PlayerViewModel
import com.music.spotui.ui.immersive.motion.ImmersiveMotion.Pages as IPages

@Composable
fun MyNavHost(
    navHostController: NavHostController,
    bottomBarState: MutableState<Boolean>,
    bottomBarPlayerState: MutableState<Boolean>,
    modifier: Modifier = Modifier,
) {

    val playerViewModel : PlayerViewModel = viewModel()
    val playerState by playerViewModel.currentSongTitle

//    val context = LocalContext.current
//    var player : ExoPlayer? = null
//    player = ExoPlayer.Builder(context).build()

    val context = LocalContext.current
    val startDestination = Routes.Home.route
    val reduceState = androidx.compose.runtime.rememberUpdatedState(com.music.spotui.ui.look.rememberReduceMotion())

    val currentBackStackEntry by navHostController.currentBackStackEntryAsState()
    val currentRoute = currentBackStackEntry?.destination?.route
    LaunchedEffect(currentRoute, playerState) {
        val hideChrome = currentRoute == Routes.Queue.route || currentRoute == Routes.DiscordStatus.route
        bottomBarState.value = !hideChrome
        bottomBarPlayerState.value = !hideChrome && playerState != ""
    }

    com.music.spotui.ui.components.ArtistChooserHost(navHostController)
    NavHost(
        navController = navHostController,
        startDestination = startDestination,
        modifier = modifier.background(com.music.spotui.ui.immersive.Immersive.Page),
        enterTransition = {
            val reduce = reduceState.value
            when {
                isTabSwitch(initialState, targetState) ->
                    fadeIn(if (reduce) snap() else tween(TAB_MS, easing = FastOutSlowInEasing))
                targetState.isSheet() ->
                    if (reduce) fadeIn(tween(90)) else slideInVertically(tween(SHEET_IN_MS, easing = PageCurve)) { it }
                else -> IPages.enter(reduce)(this)
            }
        },
        exitTransition = {
            val reduce = reduceState.value
            when {
                isTabSwitch(initialState, targetState) ->
                    fadeOut(if (reduce) snap() else tween(TAB_MS, easing = FastOutSlowInEasing))
                targetState.isSheet() ->
                    if (reduce) fadeOut(tween(90)) else fadeOut(tween(SHEET_IN_MS, easing = FastOutSlowInEasing), targetAlpha = 0.92f)
                else -> IPages.exit(reduce)(this)
            }
        },
        popEnterTransition = {
            val reduce = reduceState.value
            when {
                isTabSwitch(initialState, targetState) ->
                    fadeIn(if (reduce) snap() else tween(TAB_MS, easing = FastOutSlowInEasing))
                initialState.isSheet() ->
                    if (reduce) fadeIn(tween(90)) else fadeIn(tween(SHEET_OUT_MS, easing = FastOutSlowInEasing), initialAlpha = 0.92f)
                else -> IPages.popEnter(reduce)(this)
            }
        },
        popExitTransition = {
            val reduce = reduceState.value
            when {
                isTabSwitch(initialState, targetState) ->
                    fadeOut(if (reduce) snap() else tween(TAB_MS, easing = FastOutSlowInEasing))
                initialState.isSheet() ->
                    if (reduce) fadeOut(tween(90)) else slideOutVertically(tween(SHEET_OUT_MS, easing = PageCurve)) { it }
                else -> IPages.popExit(reduce)(this)
            }
        },
    ){
        page(Routes.Home.route){
            com.music.spotui.ui.immersive.screens.ImmersiveHomeScreen(navHostController)
        }
        page(Routes.Search.route){
            com.music.spotui.ui.immersive.screens.ImmersiveSearchScreen(navHostController)
        }
        page(Routes.Library.route) {
            com.music.spotui.ui.immersive.screens.ImmersiveLibraryScreen(navHostController)
        }
        // Older links to the player route open the player sheet over the page instead.
        page(Routes.Player.route){
            LaunchedEffect(Unit) {
                navHostController.popBackStack()
                PlayerSheet.show()
            }
        }

        page(Routes.Queue.route){
            com.music.spotui.ui.immersive.pages.ImmersiveQueueScreen(navHostController)
        }

        page(Routes.LocalFiles.route){
            com.music.spotui.ui.immersive.pages.ImmersiveLocalFilesScreen(navHostController)
        }

        page(Routes.Downloads.route){
            com.music.spotui.ui.immersive.pages.ImmersiveDownloadsScreen(navHostController)
        }

        page(Routes.NewReleases.route) {
            com.music.spotui.ui.immersive.pages.ImmersiveNewReleasesScreen(navHostController)
        }

        page(Routes.Episodes.route) {
            com.music.spotui.ui.immersive.pages.ImmersiveEpisodesScreen(navHostController)
        }

        page(Routes.Recap.route) {
            com.music.spotui.ui.immersive.pages.ImmersiveRecapScreen(navHostController)
        }

        page(Routes.Friends.route) {
            com.music.spotui.ui.immersive.pages.ImmersiveFriendsScreen(navHostController)
        }

        // Spotify's own list for the account (every device), not this phone's counter.
        page(Routes.Recent.route) {
            com.music.spotui.ui.immersive.pages.ImmersiveRecentlyPlayedScreen(navHostController)
        }

        page(Routes.Liked.route){
            com.music.spotui.ui.immersive.screens.ImmersiveContextScreen(navHostController, type = "liked", id = "")
        }

        //Settings: sound quality, downloads, data, display, language.
        page(Routes.Settings.route){
            com.music.spotui.ui.immersive.pages.ImmersiveSettingsScreen(navHostController)
        }

        // Discord Music Status (Rich Presence) configuration & live preview screen
        page(Routes.DiscordStatus.route) {
            com.music.spotui.ui.screens.DiscordStatusScreen(navHostController)
        }

        // Extensions: the Store and Installed tabs; one installed extension's page; a repository's extension before install.
        page(Routes.Extensions.route){
            com.music.spotui.ui.screens.ExtensionsScreen(navHostController)
        }
        page("${Routes.Extension.route}/{id}", arguments = listOf(navArgument("id") { defaultValue = "" })){ entry ->
            com.music.spotui.ui.screens.ExtensionScreen(navHostController, entry.arguments?.getString("id").orEmpty())
        }
        page("${Routes.ExtensionEntry.route}/{id}", arguments = listOf(navArgument("id") { defaultValue = "" })){ entry ->
            com.music.spotui.ui.screens.ExtensionEntryScreen(navHostController, entry.arguments?.getString("id").orEmpty())
        }
        // An extension's own page (webview.page), sandboxed.
        page("extension-page/{id}", arguments = listOf(navArgument("id") { defaultValue = "" })){ entry ->
            com.music.spotui.ui.screens.ExtensionPageScreen(navHostController, entry.arguments?.getString("id").orEmpty())
        }

        page(
            "${Routes.Category.route}/{genre}?title={title}",
            arguments = listOf(navArgument("title") { defaultValue = "" }),
        ) { navBackStackEntry ->
            val genre = navBackStackEntry.arguments?.getString("genre").orEmpty()
            val title = navBackStackEntry.arguments?.getString("title").orEmpty()
            com.music.spotui.ui.immersive.screens.ImmersiveCategoryScreen(navHostController, genre = genre, title = title.ifBlank { genre })
        }

        page(
            "${Routes.Album.route}/{uString}?artist={artist}&id={albumId}",
            arguments = listOf(
                navArgument("artist") { defaultValue = "" },
                navArgument("albumId") { defaultValue = "" },
            ),
        ) { navBackStackEntry ->
            val uId = navBackStackEntry.arguments?.getString("uString")
            val artist = navBackStackEntry.arguments?.getString("artist").orEmpty()
            val albumId = navBackStackEntry.arguments?.getString("albumId").orEmpty()
            uId?.let { name ->
                AlbumByIdScreen(navHostController, name = name, artist = artist, id = albumId)
            }
        }

        page(
            "${Routes.Playlist.route}/{pId}?name={name}",
            arguments = listOf(navArgument("name") { defaultValue = "" }),
        ) { navBackStackEntry ->
            val pId = navBackStackEntry.arguments?.getString("pId")
            pId?.let { com.music.spotui.ui.immersive.screens.ImmersiveContextScreen(navHostController, type = "playlist", id = it) }
        }

        page(
            "${Routes.Profile.route}/{uId}?name={name}",
            arguments = listOf(navArgument("name") { defaultValue = "" }),
        ) { navBackStackEntry ->
            val uId = navBackStackEntry.arguments?.getString("uId")
            val name = navBackStackEntry.arguments?.getString("name").orEmpty()
            uId?.let { com.music.spotui.ui.immersive.people.ImmersiveProfileScreen(navHostController, userId = it, userName = name) }
        }

        page("$LIKED_BY_ARTIST_ROUTE/{key}") { navBackStackEntry ->
            navBackStackEntry.arguments?.getString("key")?.let { com.music.spotui.ui.immersive.screens.ImmersiveContextScreen(navHostController, type = "liked-artist", id = it) }
        }

        page(
            "${com.music.spotui.ui.screens.FOLLOW_LIST_ROUTE}/{uId}/{mode}",
        ) { navBackStackEntry ->
            val uId = navBackStackEntry.arguments?.getString("uId")
            val mode = navBackStackEntry.arguments?.getString("mode")
            uId?.let { com.music.spotui.ui.immersive.people.ImmersiveFollowListScreen(navHostController, userId = it, followers = mode == "followers") }
        }

        page(
            "${Routes.Show.route}/{sId}?name={name}",
            arguments = listOf(navArgument("name") { defaultValue = "" }),
        ) { navBackStackEntry ->
            val sId = navBackStackEntry.arguments?.getString("sId")
            val name = navBackStackEntry.arguments?.getString("name").orEmpty()
            sId?.let { com.music.spotui.ui.immersive.podcast.ImmersiveShowScreen(navHostController, showId = it, showName = name) }
        }

        page(
            "${Routes.Episode.route}/{eId}?name={name}",
            arguments = listOf(navArgument("name") { defaultValue = "" }),
        ) { navBackStackEntry ->
            val eId = navBackStackEntry.arguments?.getString("eId")
            val name = navBackStackEntry.arguments?.getString("name").orEmpty()
            eId?.let { com.music.spotui.ui.immersive.pages.ImmersiveEpisodeScreen(navHostController, episodeId = it, fallbackName = name) }
        }

        page(
            "${Routes.Folder.route}/{uri}?name={name}",
            arguments = listOf(navArgument("name") { defaultValue = "" }),
        ) { navBackStackEntry ->
            val uri = navBackStackEntry.arguments?.getString("uri")
            val name = navBackStackEntry.arguments?.getString("name").orEmpty()
            uri?.let { com.music.spotui.ui.screens.FolderScreen(navHostController, it, name) }
        }

        page("${Routes.LocalPlaylist.route}/{id}") { navBackStackEntry ->
            navBackStackEntry.arguments?.getString("id")?.let { com.music.spotui.ui.screens.LocalPlaylistScreen(navHostController, it) }
        }

        page(
            "${Routes.ArtistReleases.route}/{aString}?id={artistId}",
            arguments = listOf(navArgument("artistId") { defaultValue = "" }),
        ) { navBackStackEntry ->
            val aId = navBackStackEntry.arguments?.getString("aString")
            val artistId = navBackStackEntry.arguments?.getString("artistId").orEmpty()
            aId?.let { com.music.spotui.ui.immersive.screens.ImmersiveArtistReleasesScreen(navHostController, it, artistId) }
        }

        page(
            "${Routes.Artist.route}/{aString}?id={artistId}",
            arguments = listOf(navArgument("artistId") { defaultValue = "" }),
        ) { navBackStackEntry ->
            val aId = navBackStackEntry.arguments?.getString("aString")
            val artistId = navBackStackEntry.arguments?.getString("artistId").orEmpty()
            aId?.let { aid->
                com.music.spotui.ui.immersive.screens.ImmersiveArtistScreen(navHostController, aid, artistId)
            }
        }
    }
}

private val tabRoutes = setOf(Routes.Home.route, Routes.Search.route, Routes.Library.route)
// Full-height sheets over the current page (the official player / queue).
private val sheetRoutes = setOf(Routes.Player.route, Routes.Queue.route)

private fun isTabSwitch(from: NavBackStackEntry, to: NavBackStackEntry) =
    from.destination.route in tabRoutes && to.destination.route in tabRoutes
private fun NavBackStackEntry.isSheet() = destination.route in sheetRoutes

// Smooth Material 3 / iOS stack curves and durations
internal val Decelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
internal val Accelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
private val PageCurve = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)
private const val TAB_MS = 100
internal const val SHEET_IN_MS = 240
internal const val SHEET_OUT_MS = 200

/** A destination on an opaque background, so sliding pages never show through each other. */
private fun NavGraphBuilder.page(
    route: String,
    arguments: List<NamedNavArgument> = emptyList(),
    content: @Composable AnimatedContentScope.(NavBackStackEntry) -> Unit,
) = composable(route, arguments = arguments) { entry ->
    val scope = this
    val settled = androidx.compose.runtime.remember {
        androidx.compose.runtime.derivedStateOf {
            scope.transition.currentState == scope.transition.targetState
        }
    }
    val isTarget = androidx.compose.runtime.remember {
        androidx.compose.runtime.derivedStateOf {
            scope.transition.targetState == androidx.compose.animation.EnterExitState.Visible
        }
    }
    val rootHaze = com.music.spotui.ui.immersive.shell.LocalImmersiveHaze.current
    val rootBackdrop = com.music.spotui.ui.immersive.shell.LocalImmersiveBackdrop.current
    androidx.compose.runtime.CompositionLocalProvider(
        LocalPageSettled provides settled,
        com.music.spotui.ui.immersive.shell.LocalImmersiveHaze provides if (isTarget.value) rootHaze else null,
        com.music.spotui.ui.immersive.shell.LocalImmersiveBackdrop provides if (isTarget.value) rootBackdrop else null,
    ) {
        Box(Modifier.fillMaxSize().background(com.music.spotui.ui.immersive.Immersive.Page)) { scope.content(entry) }
    }
}

/**
 * false while this page is sliding in or out. Heavy views (video players, surfaces)
 * wait for it: built in the first frame, they stalled it and the motion jumped.
 */
val LocalPageSettled = androidx.compose.runtime.staticCompositionLocalOf<androidx.compose.runtime.State<Boolean>> {
    androidx.compose.runtime.mutableStateOf(true)
}

/** The Immersive look is always active (Classic look has been removed). */
@Composable
internal fun isImmersive(): Boolean = true
