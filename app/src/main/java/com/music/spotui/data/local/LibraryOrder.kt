package com.music.spotui.data.local

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.wavebits.host.android.BackendMode
import app.wavebits.host.android.WaveBitsRuntime
import com.music.spotui.data.entity.LibraryEntry
import java.text.Collator

/**
 * How "Your Library" is sorted: the user's own order (Spotify's until they move
 * something), alphabetical, or by creator. The custom order is kept on the phone, one
 * list per level (the root, or a folder's uri) and per backend platform. Pinned entries
 * (Liked Songs, Local Files, Downloaded) always stay on top, as in the official app.
 */
object LibraryOrder {
    enum class Sort { CUSTOM, RECENTS, RECENTLY_ADDED, ALPHABETICAL, CREATOR }

    const val ROOT = "root"
    private const val PREFS = "library_order"
    private const val KEY_SORT = "sort"
    private const val SEP = "\u001F"

    private val sortByBackend = java.util.concurrent.ConcurrentHashMap<BackendMode, Sort>()
    /** Bumped on every change, so screens re-arrange. */
    var version by mutableStateOf(0)
        private set
    private var context: Context? = null

    val sort: Sort
        get() {
            version // read Compose state so callers recompose on change
            val mode = WaveBitsRuntime.backendMode.value
            return sortByBackend[mode] ?: loadSortFor(mode).also { sortByBackend[mode] = it }
        }

    fun init(c: Context) {
        context = c.applicationContext
        BackendMode.entries.forEach { mode ->
            sortByBackend[mode] = loadSortFor(mode)
        }
        version++
    }

    private fun prefsName(mode: BackendMode = WaveBitsRuntime.backendMode.value): String =
        if (mode == BackendMode.SPOTIFY) PREFS else "${PREFS}_${mode.name}"

    private fun prefs(mode: BackendMode = WaveBitsRuntime.backendMode.value) =
        context?.getSharedPreferences(prefsName(mode), Context.MODE_PRIVATE)

    private fun loadSortFor(mode: BackendMode): Sort =
        runCatching { Sort.valueOf(prefs(mode)?.getString(KEY_SORT, null) ?: "") }.getOrDefault(Sort.CUSTOM)

    fun choose(s: Sort) {
        val mode = WaveBitsRuntime.backendMode.value
        sortByBackend[mode] = s
        prefs(mode)?.edit()?.putString(KEY_SORT, s.name)?.apply()
        version++
    }

    fun custom(level: String): List<String> =
        prefs()?.getString("order:$level", null)?.split(SEP)?.filter { it.isNotEmpty() } ?: emptyList()

    /** The user moved something: [keys] is the level's new order, and the sort becomes custom. */
    fun saveCustom(level: String, keys: List<String>) {
        prefs()?.edit()?.putString("order:$level", keys.joinToString(SEP))?.apply()
        if (sort != Sort.CUSTOM) choose(Sort.CUSTOM) else version++
    }

    /** Back to the platform's default order, everywhere in the active platform. */
    fun reset() {
        val p = prefs() ?: return
        p.edit().apply { p.all.keys.filter { it.startsWith("order:") }.forEach { remove(it) } }.apply()
        choose(Sort.CUSTOM)
    }

    fun key(e: LibraryEntry): String = e.folderUri.ifBlank { e.spotifyId }

    /** Who made it: the owner of a playlist, the artists of an album; folders have none. */
    fun creator(e: LibraryEntry): String = when {
        e.isFolder -> ""
        !e.isPlaylist -> e.artists.ifBlank { e.subtitle.substringAfter(" • ", "") }
        else -> e.subtitle.substringAfter(" • ", "")
    }

    /**
     * [entries] in the chosen order, the first [pinned] untouched. In the custom order,
     * entries never moved (new playlists) come first, in Spotify's order, as new
     * playlists do in the official app.
     */
    fun arrange(entries: List<LibraryEntry>, sort: Sort, saved: List<String>, pinned: Int): List<LibraryEntry> {
        val head = entries.take(pinned)
        val rest = entries.drop(pinned)
        val collator = Collator.getInstance().apply { strength = Collator.PRIMARY }
        val sorted = when (sort) {
            Sort.CUSTOM -> {
                if (saved.isEmpty()) rest
                else {
                    val at = saved.withIndex().associate { it.value to it.index }
                    rest.withIndex().sortedWith(compareBy({ at[key(it.value)] ?: -1 }, { it.index })).map { it.value }
                }
            }
            // Spotify's own order: the most recently played first.
            Sort.RECENTS -> rest
            // ISO 8601 dates sort as text; folders (no date) go last.
            Sort.RECENTLY_ADDED -> rest.sortedByDescending { it.addedAt }
            Sort.ALPHABETICAL -> rest.sortedWith { a, b -> collator.compare(a.name, b.name) }
            Sort.CREATOR -> rest.sortedWith(
                compareBy<LibraryEntry> { !it.isFolder }
                    .then { a, b -> collator.compare(creator(a), creator(b)) }
                    .then { a, b -> collator.compare(a.name, b.name) },
            )
        }
        // Items pinned on Spotify stay at the top, in the chosen order.
        return head + sorted.sortedBy { !it.pinned }
    }
}
