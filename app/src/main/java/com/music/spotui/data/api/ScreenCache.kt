package com.music.spotui.data.api

import android.util.AtomicFile
import android.util.Log
import com.music.spotui.data.entity.HomeFeedModel
import com.music.spotui.data.entity.LibraryEntry
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.di.Graph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The last home feed, library and Liked Songs (and a few playlist pages), kept on disk: after a restart the screens
 * show them at once, while the hidden Web Player is still starting, then refresh.
 * Private app storage; cleared on logout ([clear]).
 */
object ScreenCache {
    private const val TAG = "ScreenCache"
    // Bumped when a cached model changes shape.
    private const val DIR = "screen_cache2"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    // One thread: writes and clear() run in call order, so a write already in
    // flight at logout cannot land after the clear and restore the old account.
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val dir: File by lazy { File(Graph.app.filesDir, DIR).also { it.mkdirs() } }

    val home = Entry("home", HomeFeedModel.serializer(), scopedByBackend = true)
    val libraryRoot = Entry("library_root", ListSerializer(LibraryEntry.serializer()), scopedByBackend = true)
    val liked = Entry("liked", ListSerializer(SongsModel.serializer()), scopedByBackend = true)

    /** A playlist page fetched ahead (see PagePrefetch), one file per playlist. */
    fun page(id: String) = Entry("page_$id", com.music.spotui.data.entity.ContextPageModel.serializer(), scopedByBackend = true)

    /**
     * The page of a list downloaded as a whole (see OfflineCollections): cover, details and songs
     * in order, so it opens offline. Never pruned; gone with the list or at logout.
     */
    fun offlinePage(id: String) = Entry("offline_page_$id", com.music.spotui.data.entity.ContextPageModel.serializer())

    fun dropOfflinePage(id: String) { io.launch { File(dir, "offline_page_$id.json").delete() } }

    /** When the page of [id] was last saved; null when there is none. */
    fun pageSavedAt(id: String): Long? = File(dir, "page_$id.json").takeIf { it.exists() }?.lastModified()

    /** Keeps the [keep] most recent pages on storage and deletes the others. */
    fun prunePages(keep: Int) {
        io.launch {
            dir.listFiles { f -> f.name.startsWith("page_") && f.name.endsWith(".json") }
                ?.sortedByDescending { it.lastModified() }?.drop(keep)?.forEach { it.delete() }
        }
    }

    class Entry<T>(
        private val name: String,
        private val serializer: KSerializer<T>,
        private val scopedByBackend: Boolean = false,
    ) {
        private val resolvedName: String get() {
            if (!scopedByBackend) return name
            val mode = app.wavebits.host.android.WaveBitsRuntime.backendMode.value.name
            return if (mode == "SPOTIFY") name else "${name}_$mode"
        }
        private val file get() = AtomicFile(File(dir, "$resolvedName.json"))

        /** Blocking read: call from a background thread. */
        fun read(): T? = runCatching {
            if (!file.baseFile.exists()) return null
            json.decodeFromString(serializer, file.readFully().decodeToString())
        }.onFailure { Log.w(TAG, "drop unreadable $name: ${it.javaClass.simpleName}"); runCatching { file.delete() } }.getOrNull()

        fun write(value: T) {
            val targetFile = file
            val targetName = resolvedName
            io.launch {
                runCatching {
                    val out = targetFile.startWrite()
                    try {
                        out.write(json.encodeToString(serializer, value).encodeToByteArray())
                        targetFile.finishWrite(out)
                    } catch (t: Throwable) { targetFile.failWrite(out); throw t }
                }.onFailure { Log.w(TAG, "could not save $targetName: ${it.javaClass.simpleName}") }
            }
        }
    }

    /** Logout / account switch. */
    fun clear() { io.launch { dir.listFiles()?.forEach { it.delete() } } }

    /** Synchronously drops cached YouTube entries so an immediate account switch cannot read stale Guest data. */
    fun clearYouTubeBlocking() {
        runCatching {
            dir.listFiles { f -> f.name.endsWith("_YOUTUBE.json") }?.forEach { it.delete() }
        }
        io.launch {
            runCatching {
                dir.listFiles { f -> f.name.endsWith("_YOUTUBE.json") }?.forEach { it.delete() }
            }
        }
    }
}
