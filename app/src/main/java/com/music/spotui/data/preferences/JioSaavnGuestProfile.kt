package com.music.spotui.data.preferences

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.music.spotui.ui.platform.PlatformManager

/**
 * Manages JioSaavn Guest Mode profile details (Guest Name & Animated Avatar ID).
 */
object JioSaavnGuestProfile {
    private const val PREFS = "myspoti_prefs"
    private const val KEY_NAME = "JioSaavnGuestName"
    private const val KEY_AVATAR = "JioSaavnGuestAvatarId"
    private const val KEY_CONFIGURED = "JioSaavnGuestConfigured"

    var guestName by mutableStateOf("Jiosaavn Guest")
        private set

    var avatarId by mutableStateOf("beard_man")
        private set

    var isConfigured by mutableStateOf(false)
        private set

    private val SAFE_AVATAR_ID = Regex("^[a-z0-9_]{1,32}$")
    private val HTML_TAG_REGEX = Regex("<[^>]*>")

    private fun sanitizeName(raw: String?): String {
        val stripped = raw.orEmpty()
            .replace(HTML_TAG_REGEX, "")
            .filter { ch -> ch.code >= 0x20 && ch != '<' && ch != '>' && ch != '"' && ch != '\'' && ch != '`' }
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(40)
        return stripped.ifBlank { "Jiosaavn Guest" }
    }

    private fun sanitizeAvatar(raw: String?): String {
        val candidate = raw.orEmpty().trim().lowercase()
        return if (SAFE_AVATAR_ID.matches(candidate)) candidate else "beard_man"
    }

    fun load(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val rawName = p.getString(KEY_NAME, null)?.takeIf { it.isNotBlank() }
            ?: p.getString("JioSaavnName", null)?.takeIf { it.isNotBlank() }
        val rawAvatar = p.getString(KEY_AVATAR, null)?.takeIf { it.isNotBlank() }
            ?: p.getString("JioSaavnAvatar", null)?.takeIf { it.isNotBlank() }
        val name = sanitizeName(rawName)
        val avatar = sanitizeAvatar(rawAvatar)
        guestName = name
        avatarId = avatar
        isConfigured = p.getBoolean(KEY_CONFIGURED, false) || name != "Jiosaavn Guest" || avatar != "beard_man"
    }

    fun save(context: Context, name: String, newAvatarId: String) {
        val cleanName = sanitizeName(name)
        val cleanAvatar = sanitizeAvatar(newAvatarId)
        guestName = cleanName
        avatarId = cleanAvatar
        isConfigured = true
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_NAME, cleanName)
            .putString("JioSaavnName", cleanName)
            .putString(KEY_AVATAR, cleanAvatar)
            .putString("JioSaavnAvatar", cleanAvatar)
            .putBoolean(KEY_CONFIGURED, true)
            .apply()

        // Sync with PlatformManager & ProfileCache
        PlatformManager.saveJioSaavnSession(context, cleanName, cleanAvatar)
        com.music.spotui.data.api.ProfileCache.ensure(context)
    }

    fun reset(context: Context) {
        guestName = "Jiosaavn Guest"
        avatarId = "beard_man"
        isConfigured = false
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_NAME)
            .remove("JioSaavnName")
            .remove(KEY_AVATAR)
            .remove("JioSaavnAvatar")
            .putBoolean(KEY_CONFIGURED, false)
            .apply()
        com.music.spotui.data.api.ProfileCache.ensure(context)
    }
}
