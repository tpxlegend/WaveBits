package com.music.spotui.ui.screens

import com.music.spotui.ui.theme.tone
import com.music.spotui.ui.theme.AppText
import com.music.spotui.ui.theme.AppMuted

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.delay
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.wavebits.engine.R
import app.wavebits.power.BatteryExemption
import com.music.spotui.data.local.LocalMusic
import com.music.spotui.ui.theme.AppBackground

/**
 * Shown once after signing in: what Myspoti needs from the phone, each with its own button.
 * Nothing is required; "Continue" goes on with whatever was allowed.
 */
object Setup {
    private const val KEY = "SetupDone"
    /** The permissions step (sound quality and look steps are bypassed). */
    fun needed(context: Context) = !permissionsDone(context)
    fun permissionsDone(context: Context) = context.getSharedPreferences("myspoti_prefs", Context.MODE_PRIVATE).getBoolean(KEY, false)
    fun done(context: Context) = context.getSharedPreferences("myspoti_prefs", Context.MODE_PRIVATE).edit().putBoolean(KEY, true).apply()
    fun resetDone(context: Context) = context.getSharedPreferences("myspoti_prefs", Context.MODE_PRIVATE).edit().putBoolean(KEY, false).apply()
}

private fun granted(context: Context, permission: String) =
    context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

@Composable
fun SetupScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        com.music.spotui.data.preferences.setQualityChosen(context)
    }
    Box(Modifier.fillMaxSize().background(AppBackground)) {
        Glow(Modifier.fillMaxSize(), alpha = 0.16f)
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Spacer(Modifier.width(1.dp))
                TextButton(
                    onClick = {
                        Setup.done(context)
                        onDone()
                    }
                ) {
                    Text(
                        text = "Skip",
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Box(modifier = Modifier.weight(1f)) {
                PermissionsStep(onDone)
            }
        }
    }
}

/** Where the user is in the steps: the current one a longer green dash. */
@Composable
private fun StepDots(count: Int, current: Int, modifier: Modifier = Modifier) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = modifier) {
        for (i in 0 until count) {
            val w by animateDpAsState(if (i == current) 22.dp else 8.dp, tween(350), label = "dot")
            val c by animateColorAsState(if (i == current) Color.White else AppText.copy(alpha = 0.25f), tween(350), label = "dotColor")
            Box(Modifier.height(6.dp).width(w).clip(RoundedCornerShape(50)).background(c))
        }
    }
}

/** Each part rising into place a beat after the one above it. */
@Composable
private fun Modifier.riseIn(index: Int): Modifier {
    val a = remember { Animatable(0f) }
    LaunchedEffect(Unit) { delay(80L + index * 70L); a.animateTo(1f, tween(450, easing = FastOutSlowInEasing)) }
    return graphicsLayer { alpha = a.value; translationY = (1f - a.value) * 18.dp.toPx() }
}

@Composable
private fun PermissionsStep(onDone: () -> Unit) {
    val context = LocalContext.current
    // Re-read when coming back from a system dialog or the settings.
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(800); tick++ } }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }

    @Suppress("UNUSED_EXPRESSION") tick
    val notificationsGranted = if (Build.VERSION.SDK_INT >= 33) granted(context, Manifest.permission.POST_NOTIFICATIONS) else true
    val localGranted = LocalMusic.hasPermission(context)
    val bluetoothGranted = if (Build.VERSION.SDK_INT >= 31) granted(context, Manifest.permission.BLUETOOTH_CONNECT) else true
    val batteryGranted = BatteryExemption.isExempt(context)

    val allGranted = notificationsGranted && localGranted && bluetoothGranted && batteryGranted

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        var row = 0
        Text(stringResource(R.string.setup_title), color = AppText, fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.riseIn(row++))
        Text(stringResource(R.string.setup_subtitle), color = AppMuted, fontSize = 14.sp, modifier = Modifier.riseIn(row++))
        Spacer(Modifier.height(8.dp))
        if (Build.VERSION.SDK_INT >= 33) SetupRow(
            stringResource(R.string.setup_notifications_title), stringResource(R.string.setup_notifications_detail),
            notificationsGranted, Modifier.riseIn(row++),
        ) { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }
        SetupRow(
            stringResource(R.string.setup_local_title), stringResource(R.string.setup_local_detail),
            localGranted, Modifier.riseIn(row++),
        ) { launcher.launch(LocalMusic.permission) }
        if (Build.VERSION.SDK_INT >= 31) SetupRow(
            stringResource(R.string.setup_bluetooth_title), stringResource(R.string.setup_bluetooth_detail),
            bluetoothGranted, Modifier.riseIn(row++),
        ) { launcher.launch(Manifest.permission.BLUETOOTH_CONNECT) }
        SetupRow(
            stringResource(R.string.setup_battery_title), stringResource(R.string.setup_battery_detail),
            batteryGranted, Modifier.riseIn(row++),
        ) { BatteryExemption.request(context) }
        Spacer(Modifier.height(12.dp))
        Box(Modifier.riseIn(row)) {
            Button(
                onClick = { Setup.done(context); onDone() },
                enabled = true,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White,
                    contentColor = Color.Black,
                    disabledContainerColor = Color.White.copy(alpha = 0.35f),
                    disabledContentColor = Color.Black.copy(alpha = 0.5f)
                ),
                modifier = Modifier.fillMaxWidth().height(50.dp),
            ) { Text(stringResource(R.string.setup_continue), fontWeight = FontWeight.Bold, fontSize = 16.sp) }
        }
    }
}

/** Onboarding, step one: how Myspoti streams and saves music (changed later in Settings). */
@Composable
private fun QualityStep(onNext: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.setup_quality_title), color = AppText, fontSize = 26.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 20.dp).riseIn(0))
        Text(stringResource(R.string.setup_quality_subtitle), color = AppMuted, fontSize = 14.sp,
            modifier = Modifier.padding(horizontal = 20.dp).riseIn(1))
        Box(Modifier.riseIn(2)) { com.music.spotui.ui.components.StreamingQualityPicker(Modifier.padding(top = 8.dp)) }
        Box(Modifier.riseIn(3)) { com.music.spotui.ui.components.DownloadQualityPicker() }
        Button(
            onClick = onNext,
            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).height(50.dp).riseIn(4),
        ) { Text(stringResource(R.string.setup_continue), fontWeight = FontWeight.Bold, fontSize = 16.sp) }
    }
}

@Composable
private fun SetupRow(title: String, detail: String, on: Boolean, modifier: Modifier = Modifier, onEnable: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth().background(tone(0xFF1E1E1E), RoundedCornerShape(12.dp)).padding(16.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = AppText, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(detail, color = AppMuted, fontSize = 13.sp)
        }
        // Allowed: the button gives way to a green check that pops in.
        AnimatedContent(
            targetState = on,
            transitionSpec = { (scaleIn(spring(dampingRatio = 0.5f)) + fadeIn()) togetherWith (scaleOut() + fadeOut()) },
            label = "allowed",
        ) { allowed ->
            if (allowed) Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color.White, modifier = Modifier.padding(start = 12.dp).size(28.dp))
            else TextButton(onClick = onEnable) { Text(stringResource(R.string.setup_enable), color = Color.White, fontWeight = FontWeight.Bold) }
        }
    }
}
