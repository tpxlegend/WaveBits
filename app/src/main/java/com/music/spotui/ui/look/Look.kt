package com.music.spotui.ui.look

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.wavebits.engine.R
import app.wavebits.power.EnergyPolicy
import app.wavebits.power.EnergySettings
import com.music.spotui.data.preferences.Look
import com.music.spotui.data.preferences.LookPref

/** The look the screens below are drawn in; see [Look]. */
val LocalLook = compositionLocalOf { Look.IMMERSIVE }

/** Current reduce-motion flag computed once at the root so list items never allocate observers. */
val LocalReduceMotion = compositionLocalOf { false }

/** Current energy policy computed once at the root so glass surfaces avoid per-node flow collectors. */
val LocalEnergyPolicy = compositionLocalOf { EnergyPolicy() }

/** Hands the chosen look ([LookPref.look]), energy policy, and reduce-motion flag to everything inside. */
@Composable
fun ProvideLook(content: @Composable () -> Unit) {
    val context = LocalContext.current
    var systemReduced by remember(context) {
        mutableStateOf(readSystemReduceMotion(context))
    }
    DisposableEffect(context) {
        val uri = Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE)
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                systemReduced = readSystemReduceMotion(context)
            }
        }
        runCatching { context.contentResolver.registerContentObserver(uri, false, observer) }
        onDispose {
            runCatching { context.contentResolver.unregisterContentObserver(observer) }
        }
    }
    val energy by EnergySettings.state.collectAsStateWithLifecycle()
    val reduceMotion = systemReduced || energy.policy.disableHeavyAnimations || EnergySettings.isLowEndDevice
    CompositionLocalProvider(
        LocalLook provides LookPref.look,
        LocalEnergyPolicy provides energy.policy,
        LocalReduceMotion provides reduceMotion,
        content = content,
    )
}

private fun readSystemReduceMotion(context: android.content.Context): Boolean = runCatching {
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
}.getOrDefault(false)

/**
 * The Immersive look's typeface: Inter (SIL Open Font License 1.1, licenses/Inter-OFL.txt), the
 * free face closest to the system one of the player it follows, cut down to Latin. One variable
 * file with a weight axis and an optical-size axis: text sizes use its "Text" cut (opsz 14),
 * titles from 20sp up its "Display" cut (opsz 32), tighter and finer, as that face does.
 */
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun inter(weight: Int, opticalSize: Float) = Font(
    R.font.inter,
    weight = FontWeight(weight),
    variationSettings = androidx.compose.ui.text.font.FontVariation.Settings(
        androidx.compose.ui.text.font.FontVariation.weight(weight),
        androidx.compose.ui.text.font.FontVariation.Setting("opsz", opticalSize),
    ),
)

private val weights = listOf(400, 500, 600, 700, 800)

/** Body text, captions, buttons. */
val ImmersiveSans = FontFamily(weights.map { inter(it, 14f) })

/** Titles, the player's title and the lyrics (20sp and up). */
val ImmersiveDisplay = FontFamily(weights.map { inter(it, 32f) })

/**
 * True when animations should be reduced or disabled: system "Remove animations",
 * active app Battery Saver (when not charging), or low-end hardware lite mode.
 */
@Composable
fun rememberReduceMotion(): Boolean = LocalReduceMotion.current

