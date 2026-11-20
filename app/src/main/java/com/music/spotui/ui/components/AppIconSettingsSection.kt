package com.music.spotui.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.integration.compose.CrossFade
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import com.music.spotui.data.preferences.AppIconManager
import com.music.spotui.data.preferences.AppIconOption
import app.wavebits.power.EnergySettings
import com.music.spotui.ui.immersive.Immersive
import com.music.spotui.ui.look.LocalEnergyPolicy
import com.music.spotui.ui.look.rememberReduceMotion
import com.music.spotui.ui.theme.AppAccent
import com.music.spotui.ui.theme.AppMuted
import com.music.spotui.ui.theme.AppText
import com.music.spotui.ui.theme.tone

@OptIn(ExperimentalLayoutApi::class, ExperimentalGlideComposeApi::class)
@Composable
fun AppIconSelectorGrid(
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        AppIconManager.init(context)
        AppIconManager.syncFromGitHub(context)
    }
    val icons by AppIconManager.icons.collectAsStateWithLifecycle()
    val isSyncing by AppIconManager.isSyncing.collectAsStateWithLifecycle()
    val selectedId = AppIconManager.selectedIconId

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag("app_icon_selector_section"),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "App Icon",
                    color = AppText,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    text = "Official icons synced from github.com/tpxlegend/WaveBits/tree/main/Logo",
                    color = AppMuted,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.minimumInteractiveComponentSize(),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(tone(0xFF26262C))
                        .clickable(
                            enabled = !isSyncing,
                            role = Role.Button,
                            onClick = { AppIconManager.syncFromGitHub(context) },
                        )
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .testTag("sync_github_app_icons_button"),
                ) {
                    if (isSyncing) {
                        CircularProgressIndicator(
                            color = Immersive.Accent,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(15.dp),
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = "Sync icons from GitHub",
                            tint = AppText,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (isSyncing) "Syncing..." else "Sync",
                        color = AppText,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            maxItemsInEachRow = 2,
        ) {
            icons.forEach { option ->
                val isSelected = option.id == selectedId
                AppIconCardItem(
                    option = option,
                    selected = isSelected,
                    onClick = { AppIconManager.selectIcon(context, option) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
fun AppIconCardItem(
    option: AppIconOption,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val reduceMotion = rememberReduceMotion()
    val liteMode = LocalEnergyPolicy.current.disableHeavyAnimations || EnergySettings.isLowEndDevice
    val scale by animateFloatAsState(
        targetValue = if (selected) 1.03f else 1f,
        animationSpec = if (reduceMotion) snap() else com.music.spotui.ui.immersive.motion.ImmersiveMotion.pressSpring(),
        label = "appIconCardScale",
    )
    val cardShape = RoundedCornerShape(22.dp)
    val iconShape = RoundedCornerShape(22.dp)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .graphicsLayer {
                if (scale != 1f) {
                    scaleX = scale
                    scaleY = scale
                }
            }
            .clip(cardShape)
            .background(
                if (selected) Immersive.Accent.copy(alpha = 0.16f) else tone(0xFF1E1E24),
            )
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) Immersive.Accent else Color.White.copy(alpha = 0.08f),
                shape = cardShape,
            )
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(horizontal = 12.dp, vertical = 14.dp)
            .testTag("app_icon_option_${option.id}"),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(114.dp),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(110.dp)
                    .clip(iconShape)
                    .background(Color.Black)
                    .border(1.2.dp, if (selected) Immersive.Accent.copy(alpha = 0.5f) else Color.White.copy(alpha = 0.14f), iconShape),
            ) {
                val isDefaultLogo = option.id == "default"
                val innerPad = if (isDefaultLogo) 4.dp else 0.dp
                val artScale = if (isDefaultLogo) 1.04f else 1.12f
                when {
                    option.localPreviewRes != null -> {
                        Image(
                            painter = painterResource(option.localPreviewRes),
                            contentDescription = option.title,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(innerPad)
                                .graphicsLayer {
                                    scaleX = artScale
                                    scaleY = artScale
                                },
                        )
                    }
                    !option.imageUrl.isNullOrBlank() -> {
                        GlideImage(
                            model = option.imageUrl,
                            contentDescription = option.title,
                            contentScale = ContentScale.Fit,
                            transition = if (liteMode) null else CrossFade,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(innerPad)
                                .graphicsLayer {
                                    scaleX = artScale
                                    scaleY = artScale
                                },
                        ) { req ->
                            req.override(256).diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                        }
                    }
                }
            }

            if (selected) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(AppAccent)
                        .border(2.dp, Color.Black, CircleShape),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = "Selected",
                        tint = Color.Black,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        Text(
            text = option.title,
            color = if (selected) AppText else AppText.copy(alpha = 0.88f),
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )

        if (selected) {
            Text(
                text = "Active Icon",
                color = AppAccent,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}
