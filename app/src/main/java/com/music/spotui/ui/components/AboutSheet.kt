package com.music.spotui.ui.components

import com.music.spotui.ui.theme.tone
import com.music.spotui.ui.theme.AppText
import com.music.spotui.ui.theme.AppMuted

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.wavebits.engine.BuildConfig
import app.wavebits.engine.R
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import com.bumptech.glide.integration.compose.placeholder
import com.music.spotui.data.api.SupportReminder
import com.music.spotui.ui.theme.AppAccent

const val DISCORD_URL = "https://discord.gg/2DjNketpsM"
const val GITHUB_URL = "https://github.com/tpxlegend/WaveBits"
const val CREATOR_GITHUB_URL = "https://github.com/tpxlegend"
private val repo get() = BuildConfig.UPDATE_REPO.ifBlank { "tpxlegend/WaveBits" }

private fun openUrl(context: Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** About: GitHub Creator profile photo & name on top, app version, Discord community, and source code. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalGlideComposeApi::class)
@Composable
fun AboutSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    com.music.spotui.extensions.ProtectedSurfaceEffect(app.wavebits.extensions.ProtectedSurface.ABOUT)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = tone(0xFF282828),
    ) {
        Column(Modifier.navigationBarsPadding().verticalScroll(rememberScrollState()).padding(bottom = 20.dp)) {
            // Creator Card on top of About section
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { openUrl(context, CREATOR_GITHUB_URL) }
                    .padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
                GlideImage(
                    model = "https://github.com/tpxlegend.png",
                    contentDescription = "Creator Profile Photo",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(tone(0xFF181818))
                        .border(2.dp, AppAccent, CircleShape),
                    loading = placeholder(R.drawable.wavebits_logo),
                    failure = placeholder(R.drawable.wavebits_logo)
                )
                Column(Modifier.padding(start = 16.dp)) {
                    Text(
                        text = "TP x LEGEND",
                        color = AppText,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "App Creator & Lead Developer",
                        color = AppMuted,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "github.com/tpxlegend",
                        color = AppAccent,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }

            HorizontalDivider(
                color = tone(0xFF383838),
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
            )

            // App details
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                Image(
                    painterResource(R.drawable.wavebits_logo), contentDescription = null,
                    modifier = Modifier.size(52.dp).clip(RoundedCornerShape(14.dp)).background(tone(0xFF121212)),
                )
                Column(Modifier.padding(start = 14.dp)) {
                    Text(stringResource(R.string.app_name), color = AppText, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text("${stringResource(R.string.settings_item_version_title)} ${BuildConfig.VERSION_NAME}",
                        color = AppMuted, fontSize = 13.sp)
                    Text("WaveBits Open Source · GPL-3.0", color = AppMuted.copy(alpha = 0.8f), fontSize = 11.sp)
                }
            }
            Spacer(Modifier.height(8.dp))
            AboutRow(stringResource(R.string.about_discord), stringResource(R.string.about_discord_detail)) { openUrl(context, DISCORD_URL) }
            AboutRow(stringResource(R.string.about_github), stringResource(R.string.about_github_detail)) { openUrl(context, GITHUB_URL) }
        }
    }
}

/** GitHub Sponsors, under the Ko-fi button: outlined, so Ko-fi stays the main one. */
@Composable
fun GitHubSponsorButton(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val context = LocalContext.current
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(50))
            .border(1.dp, AppText.copy(alpha = 0.6f), RoundedCornerShape(50))
            .pressable(0.96f) { onClick?.invoke() ?: SupportReminder.openSponsors(context) },
    ) { Text(stringResource(R.string.support_github_action), color = AppText, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
}

/** "Support Myspoti" from the profile menu: Ko-fi or GitHub Sponsors, the user picks. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SupportSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    com.music.spotui.extensions.ProtectedSurfaceEffect(app.wavebits.extensions.ProtectedSurface.SUPPORT)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = tone(0xFF282828),
    ) {
        androidx.compose.foundation.layout.Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 28.dp)) {
            Text(stringResource(R.string.support_title), color = AppText, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.support_body), color = AppMuted, fontSize = 14.sp,
                modifier = Modifier.padding(top = 10.dp, bottom = 18.dp))
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(50))
                    .background(AppAccent).pressable(0.96f) { onDismiss(); SupportReminder.open(context) },
            ) { Text(stringResource(R.string.support_action), color = Color.Black, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
            GitHubSponsorButton(Modifier.padding(top = 10.dp)) { onDismiss(); SupportReminder.openSponsors(context) }
        }
    }
}

@Composable
private fun AboutRow(title: String, detail: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 10.dp)) {
        Text(title, color = AppText, fontSize = 16.sp)
        Text(detail, color = AppMuted, fontSize = 12.sp)
    }
}

