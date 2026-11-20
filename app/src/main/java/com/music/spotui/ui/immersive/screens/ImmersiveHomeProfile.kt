/*
 * WaveBits Immersive Home Profile Sheet.
 */
package com.music.spotui.ui.immersive.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.wavebits.engine.R
import app.wavebits.host.android.WaveBitsRuntime
import com.music.spotui.data.api.ProfileCache
import com.music.spotui.data.api.SupportReminder
import com.music.spotui.di.Graph
import com.music.spotui.di.SessionActions
import com.music.spotui.ui.components.AboutSheet
import com.music.spotui.ui.components.SupporterBadge
import com.music.spotui.ui.components.SupporterSheet
import com.music.spotui.ui.components.SupportSheet
import com.music.spotui.ui.immersive.Immersive
import com.music.spotui.ui.immersive.components.ImmersiveButton
import com.music.spotui.ui.immersive.components.ImmersiveCover
import com.music.spotui.ui.navigation.Routes
import com.music.spotui.ui.navigation.profileRoute
import com.music.spotui.ui.screens.AccountBrowser

/** The profile picture as a disc (the first letter of the name until it loads), opening [onClick]. */
@Composable
internal fun ProfileDisc(onClick: () -> Unit, onLongClick: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    val isYouTube = WaveBitsRuntime.isYouTube.collectAsState().value
    val ytAvatar = if (isYouTube && WaveBitsRuntime.isStarted) {
        WaveBitsRuntime.youtubeAccount.accountAvatar.collectAsState().value
    } else {
        null
    }
    val avatar = ProfileCache.imageUrl?.takeIf { it.isNotBlank() } ?: ytAvatar
    val initial = ProfileCache.name?.trim()?.firstOrNull()?.uppercase() ?: ""
    AvatarDisc(
        url = avatar,
        initial = initial,
        contentDescription = stringResource(R.string.recently_played_user_profile),
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier,
    )
}

/** A round avatar: the picture at [url], or a Person icon / [initial] on the accent while there is none. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun AvatarDisc(
    url: String?,
    initial: String,
    contentDescription: String?,
    onClick: (() -> Unit)?,
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
) {
    val cleanUrl = url?.trim()?.let { if (it.startsWith("//")) "https:$it" else it }
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(Immersive.Accent)
            .let {
                if (onClick != null || onLongClick != null) {
                    it.combinedClickable(
                        onClick = { onClick?.invoke() },
                        onLongClick = { onLongClick?.invoke() },
                    )
                } else it
            },
    ) {
        val validInitial = initial.trim().takeIf { it.isNotEmpty() && it != "•" }
        if (validInitial != null && cleanUrl.isNullOrBlank()) {
            Icon(
                imageVector = Icons.Rounded.Person,
                contentDescription = contentDescription,
                tint = Immersive.Page,
                modifier = Modifier.size(size * 0.58f),
            )
        } else {
            Icon(
                imageVector = Icons.Rounded.Person,
                contentDescription = contentDescription,
                tint = Immersive.Page,
                modifier = Modifier.size(size * 0.58f),
            )
        }
        if (!cleanUrl.isNullOrBlank()) {
            if (cleanUrl.startsWith("http://") || cleanUrl.startsWith("https://") || cleanUrl.startsWith("content://") || cleanUrl.startsWith("file://")) {
                ImmersiveCover(
                    url = cleanUrl,
                    modifier = Modifier.size(size),
                    shape = CircleShape,
                    contentDescription = contentDescription,
                    border = false,
                )
            } else {
                com.music.spotui.ui.components.JioSaavnAnimatedAvatar(avatarId = cleanUrl, size = size)
            }
        }
    }
}

