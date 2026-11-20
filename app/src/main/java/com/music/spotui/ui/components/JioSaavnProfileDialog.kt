package com.music.spotui.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.music.spotui.data.preferences.JioSaavnGuestProfile
import com.music.spotui.ui.platform.PlatformManager
import com.music.spotui.ui.screens.JioSaavnLogoIcon
import app.wavebits.host.android.BackendMode

/**
 * Full-screen JioSaavn Account Login & Profile Customization matching the reference layout.
 */
@Composable
fun JioSaavnProfileDialog(
    onDismiss: () -> Unit,
    onSaved: () -> Unit = {},
) {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        JioSaavnGuestProfile.load(context)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            JioSaavnProfileEditContent(
                onDismiss = onDismiss,
                onSaved = onSaved,
            )
        }
    }
}

/**
 * Full-screen JioSaavn Account Login & Profile Content matching the reference image layout.
 */
@Composable
fun JioSaavnProfileEditContent(
    onDismiss: (() -> Unit)? = null,
    onSaved: () -> Unit = {},
) {
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current

    var nameInput by remember { mutableStateOf(JioSaavnGuestProfile.guestName.ifBlank { "Jiosaavn Guest" }) }
    var selectedAvatar by remember { mutableStateOf(JioSaavnGuestProfile.avatarId) }

    LaunchedEffect(JioSaavnGuestProfile.guestName, JioSaavnGuestProfile.avatarId) {
        nameInput = JioSaavnGuestProfile.guestName.ifBlank { "Jiosaavn Guest" }
        selectedAvatar = JioSaavnGuestProfile.avatarId
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Header Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 28.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    JioSaavnLogoIcon(size = 44.dp)
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(
                            text = "JioSaavn Account",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 22.sp,
                        )
                        Text(
                            text = "Guest Mode • Customize Profile",
                            color = Color.White.copy(alpha = 0.6f),
                            fontSize = 13.sp,
                        )
                    }
                }

                if (onDismiss != null) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.16f))
                            .clickable { onDismiss() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // Hero Avatar Preview
            Box(
                modifier = Modifier
                    .size(140.dp)
                    .clip(CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                JioSaavnAnimatedAvatar(
                    avatarId = selectedAvatar,
                    size = 140.dp,
                )
            }

            Spacer(Modifier.height(16.dp))

            // Display Name below Hero Avatar
            Text(
                text = nameInput.ifBlank { "Jiosaavn Guest" },
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 26.sp,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(32.dp))

            // Display Name Input Section
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.Start,
            ) {
                Text(
                    text = "Display Name",
                    color = Color.White.copy(alpha = 0.7f),
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(bottom = 8.dp),
                )

                OutlinedTextField(
                    value = nameInput,
                    onValueChange = { nameInput = it },
                    singleLine = true,
                    maxLines = 1,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { keyboard?.hide() }),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.White,
                        unfocusedBorderColor = Color.White.copy(alpha = 0.35f),
                        focusedContainerColor = Color(0xFF101012),
                        unfocusedContainerColor = Color(0xFF101012),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        cursorColor = Color.White,
                    ),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(60.dp)
                        .testTag("jiosaavn_name_input"),
                )
            }

            Spacer(Modifier.height(28.dp))

            // Choose Illustrated Avatar Section
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.Start,
            ) {
                Text(
                    text = "Choose Illustrated Avatar",
                    color = Color.White.copy(alpha = 0.7f),
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(bottom = 12.dp),
                )

                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    items(JIOSAAVN_AVATAR_PRESETS, key = { it.id }) { preset ->
                        val isSelected = selectedAvatar.equals(preset.id, ignoreCase = true)
                        Box(
                            modifier = Modifier.padding(top = 4.dp, end = 4.dp)
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .width(112.dp)
                                    .clip(RoundedCornerShape(20.dp))
                                    .background(
                                        if (isSelected) Color(0xFF1C1C1E)
                                        else Color(0xFF121214),
                                    )
                                    .border(
                                        width = if (isSelected) 2.dp else 1.dp,
                                        color = if (isSelected) Color.White else Color.White.copy(alpha = 0.12f),
                                        shape = RoundedCornerShape(20.dp),
                                    )
                                    .clickable { selectedAvatar = preset.id }
                                    .padding(vertical = 16.dp, horizontal = 8.dp)
                                    .testTag("jiosaavn_avatar_preset_${preset.id}"),
                            ) {
                                JioSaavnAnimatedAvatar(
                                    avatarId = preset.id,
                                    size = 64.dp,
                                )
                                Spacer(Modifier.height(10.dp))
                                Text(
                                    text = preset.title,
                                    color = Color.White,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    fontSize = 12.sp,
                                    textAlign = TextAlign.Center,
                                    maxLines = 1,
                                )
                            }

                            // Top-Right Checkmark Badge for selected avatar card
                            if (isSelected) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .size(24.dp)
                                        .clip(CircleShape)
                                        .background(Color.White)
                                        .border(2.dp, Color.Black, CircleShape),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = "Selected",
                                        tint = Color.Black,
                                        modifier = Modifier.size(14.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(32.dp))

        // Full-width White Save Button
        Button(
            onClick = {
                keyboard?.hide()
                JioSaavnGuestProfile.save(context, nameInput.ifBlank { "Jiosaavn Guest" }, selectedAvatar)
                PlatformManager.activatePlatform(context, BackendMode.JIOSAAVN)
                onSaved()
                onDismiss?.invoke()
            },
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.White,
                contentColor = Color.Black,
            ),
            shape = RoundedCornerShape(50),
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .testTag("jiosaavn_save_profile_btn"),
        ) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = Color.Black,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = "Save & Continue Listening",
                color = Color.Black,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
            )
        }
    }
}
