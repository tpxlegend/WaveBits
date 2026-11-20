package com.music.spotui.data.preferences

import android.app.Activity
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.wavebits.engine.R
import com.bumptech.glide.Glide
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class AppIconOption(
    val id: String,
    val title: String,
    val fileName: String,
    val imageUrl: String?,
    @DrawableRes val localPreviewRes: Int?,
    val aliasClassName: String?,
)

/**
 * Manages the limited set of official App Icons synced live from:
 * https://github.com/tpxlegend/WaveBits/tree/main/Logo
 *
 * - No custom user gallery uploads: only the official icons in the GitHub `Logo` directory
 *   (plus the Default WaveBits icon) are offered.
 * - Any new `.png` uploaded to `tpxlegend/WaveBits/tree/main/Logo` automatically appears
 *   in the Settings > App Icon section when opened or refreshed.
 */
object AppIconManager {
    private const val TAG = "AppIconManager"
    private const val PREFS_NAME = "wavebits_app_icon_prefs"
    private const val KEY_SELECTED_ID = "selected_icon_id"
    private const val KEY_CACHED_REMOTE_JSON = "cached_github_logos_json"
    const val GITHUB_CONTENTS_API = "https://api.github.com/repos/tpxlegend/WaveBits/contents/Logo"
    const val GITHUB_TREE_URL = "https://github.com/tpxlegend/WaveBits/tree/main/Logo"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val builtInAliases = listOf(
        "app.wavebits.engine.ui.LauncherDefault",
        "app.wavebits.engine.ui.LauncherAnimeBoy",
        "app.wavebits.engine.ui.LauncherCartoonBoy",
        "app.wavebits.engine.ui.LauncherMinecraft",
        "app.wavebits.engine.ui.LauncherPixel",
        "app.wavebits.engine.ui.LauncherRoblox",
    )

    private val seedGitHubIcons = listOf(
        AppIconOption(
            id = "default",
            title = "WaveBits Default",
            fileName = "wavebits-logo.png",
            imageUrl = "https://raw.githubusercontent.com/tpxlegend/WaveBits/main/assets/wavebits-logo.png",
            localPreviewRes = R.drawable.wavebits_logo,
            aliasClassName = "app.wavebits.engine.ui.LauncherDefault",
        ),
        AppIconOption(
            id = "anime_boy",
            title = "Anime Boy",
            fileName = "Anime boy.png",
            imageUrl = "https://raw.githubusercontent.com/tpxlegend/WaveBits/main/Logo/Anime%20boy.png",
            localPreviewRes = R.drawable.ic_app_icon_anime_boy,
            aliasClassName = "app.wavebits.engine.ui.LauncherAnimeBoy",
        ),
        AppIconOption(
            id = "cartoon_boy",
            title = "Cartoon Boy",
            fileName = "cartoon boy.png",
            imageUrl = "https://raw.githubusercontent.com/tpxlegend/WaveBits/main/Logo/cartoon%20boy.png",
            localPreviewRes = R.drawable.ic_app_icon_cartoon_boy,
            aliasClassName = "app.wavebits.engine.ui.LauncherCartoonBoy",
        ),
        AppIconOption(
            id = "minecraft",
            title = "Minecraft",
            fileName = "Minecraft.png",
            imageUrl = "https://raw.githubusercontent.com/tpxlegend/WaveBits/main/Logo/Minecraft.png",
            localPreviewRes = R.drawable.ic_app_icon_minecraft,
            aliasClassName = "app.wavebits.engine.ui.LauncherMinecraft",
        ),
        AppIconOption(
            id = "pixel",
            title = "Pixel",
            fileName = "Pixel.png",
            imageUrl = "https://raw.githubusercontent.com/tpxlegend/WaveBits/main/Logo/Pixel.png",
            localPreviewRes = R.drawable.ic_app_icon_pixel,
            aliasClassName = "app.wavebits.engine.ui.LauncherPixel",
        ),
        AppIconOption(
            id = "roblox",
            title = "Roblox",
            fileName = "Roblox.png",
            imageUrl = "https://raw.githubusercontent.com/tpxlegend/WaveBits/main/Logo/Roblox.png",
            localPreviewRes = R.drawable.ic_app_icon_roblox,
            aliasClassName = "app.wavebits.engine.ui.LauncherRoblox",
        ),
    )

    private val _icons = MutableStateFlow<List<AppIconOption>>(seedGitHubIcons)
    val icons: StateFlow<List<AppIconOption>> = _icons.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    var selectedIconId by mutableStateOf("default")
        private set

    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        selectedIconId = prefs.getString(KEY_SELECTED_ID, "default") ?: "default"
        val cachedJson = prefs.getString(KEY_CACHED_REMOTE_JSON, null)
        if (!cachedJson.isNullOrBlank()) {
            runCatching { parseGitHubContents(cachedJson) }.getOrNull()?.takeIf { it.isNotEmpty() }?.let {
                _icons.value = it
            }
        }
        // Ensure MainActivity itself is always enabled so emulator/ADB launches never fail
        runCatching {
            val mainComp = ComponentName(context.applicationContext.packageName, "app.wavebits.MainActivity")
            val pm = context.applicationContext.packageManager
            if (pm.getComponentEnabledSetting(mainComp) == PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
                pm.setComponentEnabledSetting(
                    mainComp,
                    PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
                    PackageManager.DONT_KILL_APP,
                )
            }
        }
        // Enforce active launcher alias on startup (only if no alias is active or selected icon has a compiled alias)
        val activeAlias = currentOption().aliasClassName ?: findActiveBuiltInAlias(context.applicationContext) ?: builtInAliases.first()
        applyLauncherAlias(context.applicationContext, activeAlias)
        syncFromGitHub(context)
    }

    private fun findActiveBuiltInAlias(appContext: Context): String? {
        val pm = appContext.packageManager
        val pkg = appContext.packageName
        return builtInAliases.firstOrNull { aliasClass ->
            runCatching {
                val state = pm.getComponentEnabledSetting(ComponentName(pkg, aliasClass))
                state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED ||
                    (aliasClass == builtInAliases.first() && state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)
            }.getOrDefault(false)
        }
    }

    fun currentOption(): AppIconOption =
        _icons.value.firstOrNull { it.id == selectedIconId } ?: seedGitHubIcons.first()

    /**
     * Updates the running Activity's TaskDescription icon (Recents & Pixel Launcher suggested dock slot)
     * so that the icon reflects the user's selected App Icon immediately.
     */
    fun applyTaskDescription(activity: Activity) {
        val option = currentOption()
        val label = activity.getString(R.string.app_name)
        val resId = option.localPreviewRes ?: R.drawable.wavebits_logo
        runCatching {
            val bmp = BitmapFactory.decodeResource(activity.resources, resId)
            if (bmp != null) {
                val framed = frameAdaptiveBitmap(bmp)
                @Suppress("DEPRECATION")
                activity.setTaskDescription(ActivityManager.TaskDescription(label, framed))
            }
        }
        val remoteUrl = option.imageUrl
        if (option.localPreviewRes == null && !remoteUrl.isNullOrBlank()) {
            val appCtx = activity.applicationContext
            scope.launch {
                val remoteBmp = runCatching {
                    Glide.with(appCtx).asBitmap().load(remoteUrl).submit(256, 256).get()
                }.getOrNull() ?: return@launch
                val framed = frameAdaptiveBitmap(remoteBmp)
                withContext(Dispatchers.Main) {
                    if (!activity.isDestroyed && !activity.isFinishing) {
                        @Suppress("DEPRECATION")
                        activity.setTaskDescription(ActivityManager.TaskDescription(label, framed))
                    }
                }
            }
        }
    }

    /**
     * Fetches the live file list from `https://api.github.com/repos/tpxlegend/WaveBits/contents/Logo`
     * so any newly uploaded `.png` in `tpxlegend/WaveBits/tree/main/Logo` appears automatically.
     */
    fun syncFromGitHub(context: Context, onComplete: ((Boolean) -> Unit)? = null) {
        val appContext = context.applicationContext
        scope.launch {
            _isSyncing.value = true
            val result = runCatching {
                val conn = (URL(GITHUB_CONTENTS_API).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 10_000
                    readTimeout = 10_000
                    setRequestProperty("Accept", "application/vnd.github.v3+json")
                    setRequestProperty("User-Agent", "WaveBits-Android")
                }
                try {
                    if (conn.responseCode in 200..299) {
                        val body = conn.inputStream.bufferedReader().use { it.readText() }
                        val parsed = parseGitHubContents(body)
                        if (parsed.isNotEmpty()) {
                            appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                                .edit()
                                .putString(KEY_CACHED_REMOTE_JSON, body)
                                .apply()
                            _icons.value = parsed
                            true
                        } else {
                            false
                        }
                    } else {
                        false
                    }
                } finally {
                    conn.disconnect()
                }
            }.getOrDefault(false)
            _isSyncing.value = false
            if (onComplete != null) {
                withContext(Dispatchers.Main) { onComplete(result) }
            }
        }
    }

    private fun parseGitHubContents(jsonStr: String): List<AppIconOption> {
        val array = JSONArray(jsonStr)
        val discovered = mutableListOf<AppIconOption>()
        discovered += seedGitHubIcons.first() // Always keep WaveBits Default first

        val byId = seedGitHubIcons.drop(1).associateBy { normalizeId(it.fileName) }

        for (i in 0 until array.length()) {
            val obj: JSONObject = array.optJSONObject(i) ?: continue
            val type = obj.optString("type")
            val name = obj.optString("name")
            val downloadUrl = obj.optString("download_url")
            if (type != "file" || !isImageFile(name) || downloadUrl.isBlank()) continue

            val normId = normalizeId(name)
            val known = byId[normId]
            if (known != null) {
                discovered += known.copy(
                    fileName = name,
                    imageUrl = downloadUrl,
                )
            } else {
                discovered += AppIconOption(
                    id = normId,
                    title = formatTitle(name),
                    fileName = name,
                    imageUrl = downloadUrl,
                    localPreviewRes = null,
                    aliasClassName = null,
                )
            }
        }

        // Ensure all bundled GitHub icons remain available even if GitHub API rate-limits or omits one
        for (seed in seedGitHubIcons.drop(1)) {
            if (discovered.none { it.id == seed.id }) {
                discovered += seed
            }
        }
        return discovered
    }

    private fun isImageFile(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".png") || lower.endsWith(".webp") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
    }

    private fun normalizeId(fileName: String): String =
        fileName.substringBeforeLast('.')
            .trim()
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')

    private fun formatTitle(fileName: String): String =
        fileName.substringBeforeLast('.')
            .replace('_', ' ')
            .replace('-', ' ')
            .trim()
            .split(Regex("\\s+"))
            .joinToString(" ") { word ->
                word.replaceFirstChar { ch -> ch.uppercase() }
            }

    /**
     * Enables [targetAliasClass] first and then disables all other built-in aliases.
     * Enabling the new alias BEFORE disabling the old one ensures Android's Launcher3 / Pixel Launcher
     * sees a valid replacement launcher activity and migrates existing home screen / dock icons in place.
     */
    private fun applyLauncherAlias(appContext: Context, targetAliasClass: String) {
        val pm = appContext.packageManager
        val pkg = appContext.packageName
        val defaultAlias = builtInAliases.first()

        // When using the default icon and no alias has been explicitly overridden yet,
        // AndroidManifest.xml already enables LauncherDefault and disables the others.
        if (targetAliasClass == defaultAlias) {
            val allDefault = builtInAliases.all { aliasClass ->
                runCatching {
                    pm.getComponentEnabledSetting(ComponentName(pkg, aliasClass)) ==
                        PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                }.getOrDefault(false)
            }
            if (allDefault) return
        }

        // 1. Enable the chosen alias FIRST (including LauncherDefault) so launcher migrates in place
        runCatching {
            val targetComp = ComponentName(pkg, targetAliasClass)
            val desiredState = if (targetAliasClass == defaultAlias) {
                PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            }
            if (pm.getComponentEnabledSetting(targetComp) != desiredState) {
                pm.setComponentEnabledSetting(
                    targetComp,
                    desiredState,
                    PackageManager.DONT_KILL_APP,
                )
            }
        }.onFailure { e ->
            Log.e(TAG, "Failed to enable alias $targetAliasClass", e)
        }

        // 2. Disable all other built-in aliases cleanly
        for (aliasClass in builtInAliases) {
            if (aliasClass == targetAliasClass) continue
            runCatching {
                val comp = ComponentName(pkg, aliasClass)
                val targetState = if (aliasClass == defaultAlias) {
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                } else {
                    PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                }
                if (pm.getComponentEnabledSetting(comp) != targetState) {
                    pm.setComponentEnabledSetting(
                        comp,
                        targetState,
                        PackageManager.DONT_KILL_APP,
                    )
                }
            }.onFailure { e ->
                Log.e(TAG, "Failed to disable alias $aliasClass", e)
            }
        }
    }

    /**
     * Applies the chosen [option]:
     * 1. Persists the choice in SharedPreferences.
     * 2. Enables the matching `<activity-alias>` in `PackageManager` (and disables the others)
     *    so the Android launcher updates the app icon directly without showing a widget/shortcut prompt.
     * 3. Updates the running Activity's TaskDescription if [context] is an Activity.
     *
     * Note: Android only allows switching the home-screen launcher icon among `<activity-alias>`
     * entries statically compiled into the APK manifest. Remote-only PNGs added after build time
     * update the in-app/Recents icon preview without triggering the "Add widget" prompt.
     */
    fun selectIcon(context: Context, option: AppIconOption) {
        val appContext = context.applicationContext
        selectedIconId = option.id
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SELECTED_ID, option.id)
            .apply()

        findActivity(context)?.let { applyTaskDescription(it) }

        val targetAliasClass = option.aliasClassName
        if (targetAliasClass != null) {
            applyLauncherAlias(appContext, targetAliasClass)
            Toast.makeText(
                appContext,
                "App icon changed to ${option.title}",
                Toast.LENGTH_SHORT,
            ).show()
        } else {
            // Do NOT call requestPinShortcut (which pops up Android's "Add widget" sheet instead of
            // changing the app icon). Ensure a valid built-in launcher alias remains active and
            // inform the user cleanly.
            val fallbackAlias = findActiveBuiltInAlias(appContext) ?: builtInAliases.first()
            applyLauncherAlias(appContext, fallbackAlias)
            Toast.makeText(
                appContext,
                "${option.title} preview applied. Home screen launcher icons require a bundled app update.",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun findActivity(context: Context): Activity? {
        var c: Context? = context
        while (c is android.content.ContextWrapper) {
            if (c is Activity) return c
            c = c.baseContext
        }
        return null
    }

    private fun frameAdaptiveBitmap(src: Bitmap): Bitmap {
        val canvasSize = 216
        val maxIconSize = 144 // ~66.7% of 216 (matches 66–72dp inside 108dp adaptive icon)
        val out = Bitmap.createBitmap(canvasSize, canvasSize, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(out)
        canvas.drawColor(android.graphics.Color.BLACK)
        val srcW = src.width.coerceAtLeast(1).toFloat()
        val srcH = src.height.coerceAtLeast(1).toFloat()
        val scale = minOf(maxIconSize / srcW, maxIconSize / srcH)
        val dstW = srcW * scale
        val dstH = srcH * scale
        val left = (canvasSize - dstW) / 2f
        val top = (canvasSize - dstH) / 2f
        val dst = android.graphics.RectF(left, top, left + dstW, top + dstH)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(src, null, dst, paint)
        return out
    }
}
