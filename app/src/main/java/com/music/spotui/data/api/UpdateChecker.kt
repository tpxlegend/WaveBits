package com.music.spotui.data.api

import android.content.Context
import app.wavebits.engine.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Checks for updates on every app launch against the GitHub releases of `tpxlegend/WaveBits`.
 * If the latest release tag name matches [BuildConfig.VERSION_NAME], no pop-up is shown.
 * If the tag name does not match, an update pop-up is displayed with options to "Update now"
 * (redirects to wavebits.vercel.app) or "Maybe later" (temporarily dismisses for the current session).
 */
object UpdateChecker {
    data class Release(
        val version: String,
        val pageUrl: String,
        val notes: String = "",
        val apkUrl: String = ""
    )

    private const val GITHUB_REPO = "tpxlegend/WaveBits"
    const val UPDATE_REDIRECT_URL = "https://wavebits.vercel.app"

    private val http by lazy {
        OkHttpClient.Builder()
            .callTimeout(10, TimeUnit.SECONDS)
            .addInterceptor(com.music.spotui.data.DataSaver.interceptor)
            .build()
    }

    private var cachedRelease: Release? = null

    suspend fun check(context: Context, manual: Boolean = false): Release? = withContext(Dispatchers.IO) {
        runCatching {
            val url = "https://api.github.com/repos/$GITHUB_REPO/releases/latest"
            val request = Request.Builder()
                .url(url)
                .addHeader("Accept", "application/vnd.github+json")
                .addHeader("User-Agent", "WaveBitsApp")
                .build()

            val response = http.newCall(request).execute()
            if (!response.isSuccessful) return@runCatching cachedRelease

            val body = response.body ?: return@runCatching null
            val bodyStr = body.string()
            val json = JSONObject(bodyStr)
            val rawTag = json.optString("tag_name", "").trim()
            if (rawTag.isBlank()) return@runCatching null

            val tagVersion = rawTag.removePrefix("v").removePrefix("V").trim()
            val currentVersion = BuildConfig.VERSION_NAME.removePrefix("v").removePrefix("V").trim()

            // If the tag is not newer than current version, no update pop-up
            if (!isNewer(tagVersion, currentVersion)) {
                cachedRelease = null
                return@runCatching null
            }

            // Tag is newer => update available
            val notes = cleanNotes(json.optString("body", ""))
            val apk = apkAsset(json)
            val release = Release(
                version = tagVersion,
                pageUrl = UPDATE_REDIRECT_URL,
                notes = notes,
                apkUrl = apk
            )
            cachedRelease = release
            release
        }.getOrNull() ?: cachedRelease
    }

    fun known(context: Context): Release? = cachedRelease

    internal fun apkAsset(release: JSONObject): String {
        val assets = release.optJSONArray("assets") ?: return ""
        val all = (0 until assets.length()).map { assets.getJSONObject(it) }
        val want = "myspoti-${release.optString("tag_name").removePrefix("v")}.apk"
        return (all.firstOrNull { it.optString("name") == want }
            ?: all.firstOrNull { it.optString("name").endsWith(".apk", ignoreCase = true) })
            ?.optString("browser_download_url").orEmpty()
    }

    fun dismiss(context: Context, release: Release) {
        // Session-only dismiss: intentionally empty so it prompts again on next app restart
    }

    fun cleanNotes(body: String): String = body
        .substringBefore("**Full Changelog**").substringBefore("## What's Changed")
        .lines().map { it.trimEnd() }
        .filter { it.isNotBlank() }
        .joinToString("\n") { line ->
            line.replace("**", "").replace(Regex("\\[([^]]+)]\\([^)]+\\)"), "$1")
                .replace(Regex("^(\\s*)[-*] "), "$1• ")
        }
        .trim()

    val isBeta get() = BuildConfig.VERSION_NAME.contains("-beta")

    fun isNewer(candidate: String, current: String): Boolean {
        val candClean = candidate.removePrefix("v").removePrefix("V").trim()
        val currClean = current.removePrefix("v").removePrefix("V").trim()
        if (candClean.equals(currClean, ignoreCase = true)) return false

        data class SemVer(val numbers: List<Int>, val preRelease: String?) : Comparable<SemVer> {
            override fun compareTo(other: SemVer): Int {
                val maxLen = maxOf(numbers.size, other.numbers.size)
                for (i in 0 until maxLen) {
                    val a = numbers.getOrElse(i) { 0 }
                    val b = other.numbers.getOrElse(i) { 0 }
                    if (a != b) return a.compareTo(b)
                }
                val pre = preRelease
                val otherPre = other.preRelease
                if (pre == null && otherPre != null) return 1
                if (pre != null && otherPre == null) return -1
                if (pre == null || otherPre == null) return 0
                return comparePreRelease(pre, otherPre)
            }

            private fun comparePreRelease(a: String, b: String): Int {
                val aTokens = a.split('.', '-')
                val bTokens = b.split('.', '-')
                val maxLen = maxOf(aTokens.size, bTokens.size)
                for (i in 0 until maxLen) {
                    val aTok = aTokens.getOrNull(i) ?: return -1
                    val bTok = bTokens.getOrNull(i) ?: return 1
                    val aNum = aTok.toIntOrNull()
                    val bNum = bTok.toIntOrNull()
                    if (aNum != null && bNum != null) {
                        if (aNum != bNum) return aNum.compareTo(bNum)
                    } else if (aTok != bTok) {
                        return aTok.compareTo(bTok)
                    }
                }
                return 0
            }
        }

        fun parse(s: String): SemVer {
            val dashIdx = s.indexOf('-')
            val (numPart, prePart) = if (dashIdx >= 0) {
                s.substring(0, dashIdx) to s.substring(dashIdx + 1)
            } else {
                s to null
            }
            val nums = numPart.split('.').mapNotNull { it.toIntOrNull() }
            return SemVer(nums, prePart)
        }

        return runCatching { parse(candClean) > parse(currClean) }.getOrDefault(false)
    }
}
