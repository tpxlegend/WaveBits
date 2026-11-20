package com.music.spotui.ui.components

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Coffee
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.music.spotui.ui.theme.AppText
import com.music.spotui.ui.theme.tone
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object SupportAutoManager {
    private const val PREFS = "support_upi_prefs"
    private const val KEY_LAST_DATE = "last_shown_date"
    private const val KEY_COUNT = "daily_count"
    private const val KEY_LAST_TIME_MS = "last_shown_time_ms"
    private const val FOUR_HOURS_MS = 4 * 3600 * 1000L

    fun shouldShow(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val savedDate = prefs.getString(KEY_LAST_DATE, "") ?: ""

        var count = prefs.getInt(KEY_COUNT, 0)
        val lastTimeMs = prefs.getLong(KEY_LAST_TIME_MS, 0L)
        val now = System.currentTimeMillis()

        if (savedDate != today) {
            // New day: reset daily count
            count = 0
            prefs.edit().putString(KEY_LAST_DATE, today).putInt(KEY_COUNT, 0).apply()
        }

        if (count >= 2) return false

        // For 2nd popup of the day, enforce at least 4 hours gap
        if (count == 1 && (now - lastTimeMs) < FOUR_HOURS_MS) {
            return false
        }

        return true
    }

    fun markShown(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val count = prefs.getInt(KEY_COUNT, 0)
        prefs.edit()
            .putString(KEY_LAST_DATE, today)
            .putInt(KEY_COUNT, count + 1)
            .putLong(KEY_LAST_TIME_MS, System.currentTimeMillis())
            .apply()
    }
}

/**
 * Automatic 2 times a day "Support Wavebits" pop-up.
 * Features options:
 * - "Donate with UPI" -> Opens SupportUpiSheet (Buy Me a Coffee UPI card)
 * - "Maybe later" -> Dismisses the pop-up for now.
 */
@Composable
fun SupportAutoPopup(
    onOpenSupportSheet: () -> Unit
) {
    val context = LocalContext.current
    var showDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        // Small delay after app startup so it doesn't interrupt launch
        kotlinx.coroutines.delay(6_000)
        if (SupportAutoManager.shouldShow(context)) {
            SupportAutoManager.markShown(context)
            showDialog = true
        }
    }

    if (showDialog) {
        Dialog(
            onDismissRequest = { showDialog = false },
            properties = DialogProperties(dismissOnClickOutside = false)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(tone(0xFF221F22))
                    .padding(horizontal = 24.dp, vertical = 24.dp)
            ) {
                // Coffee Icon Header
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF381B24))
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Coffee,
                        contentDescription = null,
                        tint = Color(0xFFF09886),
                        modifier = Modifier.size(28.dp)
                    )
                }

                Spacer(Modifier.height(16.dp))

                // Title
                Text(
                    text = "Support Wavebits",
                    color = AppText,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )

                Spacer(Modifier.height(8.dp))

                // Description
                Text(
                    text = "Help us improve our app and service! Keep WaveBits 100% free, ad-free, and open for everyone.",
                    color = AppText.copy(alpha = 0.75f),
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    textAlign = TextAlign.Center
                )

                Spacer(Modifier.height(24.dp))

                // Donate with UPI button
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .pressable(0.96f) {
                            showDialog = false
                            onOpenSupportSheet()
                        }
                        .clip(RoundedCornerShape(50))
                        .background(Color(0xFFF09886))
                ) {
                    Text(
                        text = "Donate with UPI",
                        color = Color(0xFF221114),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(Modifier.height(10.dp))

                // Maybe later button
                Text(
                    text = "Maybe later",
                    color = AppText.copy(alpha = 0.8f),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .pressable(0.96f) { showDialog = false }
                        .padding(horizontal = 20.dp, vertical = 10.dp)
                )
            }
        }
    }
}
