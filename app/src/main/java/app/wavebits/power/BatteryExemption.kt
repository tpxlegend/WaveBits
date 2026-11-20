package app.wavebits.power

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * Battery optimization off for Myspoti: without it, Android may put the app to sleep in
 * the background (music stopping, a Connect device no longer followed with the screen off).
 */
object BatteryExemption {
    private const val PREFS = "myspoti_prefs"
    private const val KEY_ASKED = "BatteryExemptionAsked"

    fun isExempt(context: Context): Boolean =
        runCatching {
            context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) ?: true
        }.getOrDefault(true)

    /** Opens the system battery optimization settings list (or app details if unavailable). */
    fun request(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ASKED, true).apply()
        val settingsIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(settingsIntent) }.onFailure {
            val detailsIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(detailsIntent) }
        }
    }

    /** Asks once (onboarding, or the first launch after an update); later from Settings. */
    fun askOnce(context: Context) {
        val asked = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ASKED, false)
        if (!asked && !isExempt(context)) request(context)
    }
}
