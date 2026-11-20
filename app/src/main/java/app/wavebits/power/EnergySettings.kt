package app.wavebits.power

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.BatteryManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Application-owned power and lite preferences with charging awareness; no polling. */
object EnergySettings {
    private val mutableState = MutableStateFlow(EnergyState())
    val state = mutableState.asStateFlow()
    var oledBlack by mutableStateOf(true)
        private set
    var isLowEndDevice by mutableStateOf(false)
        private set

    /** True when either low-end hardware lite mode or active uncharging Battery Saver applies. */
    val isLowEndOrSaver: Boolean
        get() = isLowEndDevice || state.value.policy.saverEnabled

    /** False when Battery Saver is actively reducing background work (not charging) or on low-end devices. */
    val allowBackgroundPrefetch: Boolean
        get() = state.value.policy.allowBackgroundLookups && !isLowEndDevice

    private var app: Application? = null
    private var preferences: SharedPreferences? = null
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> refresh() }
    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_POWER_CONNECTED -> refresh(explicitCharging = true)
                Intent.ACTION_POWER_DISCONNECTED -> refresh(explicitCharging = false)
                else -> refresh()
            }
        }
    }

    fun init(context: Context) {
        if (app != null) return
        val application = context.applicationContext as Application
        app = application
        isLowEndDevice = detectLowEndHardware(application)
        preferences = application.getSharedPreferences("energy_settings", Context.MODE_PRIVATE)
        preferences?.registerOnSharedPreferenceChangeListener(preferenceListener)
        runCatching {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
            }
            ContextCompat.registerReceiver(application, powerReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        }
        refresh()
    }

    val hasHardwareRenderNode: Boolean = true

    private fun detectLowEndHardware(context: Context): Boolean = runCatching {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
        if (am?.isLowRamDevice == true) return@runCatching true
        val memInfo = android.app.ActivityManager.MemoryInfo()
        am?.getMemoryInfo(memInfo)
        val totalRamBytes = memInfo.totalMem
        (totalRamBytes in 1..3_200_000_000L) || Runtime.getRuntime().availableProcessors() <= 4
    }.getOrDefault(false)

    private fun readIsCharging(context: Context): Boolean = runCatching {
        val batteryIntent = ContextCompat.registerReceiver(
            context,
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        val plugged = batteryIntent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        plugged != 0 ||
            status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
    }.getOrDefault(false)

    fun setSaver(enabled: Boolean) {
        preferences?.edit { putBoolean("manual_saver", enabled) }
        refresh()
    }

    fun setBlackBackground(enabled: Boolean) {
        preferences?.edit { putBoolean("black_background", enabled) }
        refresh()
    }

    fun setCanvasEnabled(enabled: Boolean) {
        preferences?.edit { putBoolean("canvas", enabled) }
        refresh()
    }

    private fun refresh(explicitCharging: Boolean? = null) {
        val application = app ?: return
        val charging = explicitCharging ?: readIsCharging(application)
        val next = EnergyState(
            manualSaver = preferences?.getBoolean("manual_saver", false) == true,
            blackPreference = preferences?.getBoolean("black_background", true) != false,
            canvasEnabled = preferences?.getBoolean("canvas", true) != false,
            isCharging = charging,
        )
        mutableState.value = next
        oledBlack = next.policy.oledBlack
    }
}
