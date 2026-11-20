package app.wavebits.extensions

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.mozilla.javascript.Callable
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.LambdaFunction
import org.mozilla.javascript.RhinoException
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptRuntime
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.Undefined
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** An extension that failed: its script threw, ran out of time, or answered something unusable. */
open class ExtensionException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The script ran past its time limit (a slow phone, a cold start), as opposed to an error of its own. */
class ExtensionTimeout(message: String, cause: Throwable? = null) : ExtensionException(message, cause)

/** The call never started: the extension was still busy with earlier ones. Not the extension's fault. */
class ExtensionBusy(message: String) : ExtensionException(message)

/**
 * One extension, running. Its script lives in its own Rhino scope on its own thread, sees only
 * `myspoti` (prelude.js) and the standard objects without Java, and every call it makes to the
 * app is checked here against the actions the user granted, a subset of what its manifest
 * declares ([Manifest.declared]). While a protected surface is on screen ([guard]), the hooks the
 * app draws (blocks, page, taps) answer nothing.
 *
 * Limits: [Limits.loadMs] to run its top level (compiling it is not counted), a per-call time limit given by the caller (counted in script
 * instructions, so a loop cannot hold the thread), [Limits.requestsPerMinute] network requests,
 * [Limits.storageChars] of storage, [Limits.spotifyCallsPerMinute] `myspoti.spotify` calls. A call that runs over is abandoned; the next one waits behind
 * it on the extension's thread, never on the app's.
 */
class ExtensionRuntime(
    val manifest: Manifest,
    private val script: String,
    private val host: Host,
    /** Ask keys the user accepted ([Ask.key]); keys of before actions are mapped ([Action.migrateGranted]). */
    grantedKeys: Set<String>,
    private val limits: Limits = Limits(),
    private val guard: SurfaceGuard = SurfaceGuard.app,
) : AutoCloseable {
    private val granted: Set<String> = Action.migrateGranted(grantedKeys)
    private val declared: Set<Action> = manifest.actionSet()

    /** Whether [a] is in the manifest and granted. */
    fun has(a: Action): Boolean = a in declared && (a.key in granted || (a == Action.NETWORK && Action.NETWORK_ANY in granted))

    /** How long a call may wait for its turn, on top of its own time (the longest hook is 30 s). */
    private val QUEUE_MS = 30_000L

    data class Limits(
        /** Running the script's top level, counted once it is compiled. */
        val loadMs: Long = 60_000,
        /** Compiling it: the app's work, not the script's, slow only on a busy phone (a cold start). */
        val compileMs: Long = 60_000,
        val requestsPerMinute: Int = 120,
        val spotifyCallsPerMinute: Int = 60,
        /** How long the user has to answer a confirmation; no answer is a refusal. */
        val confirmMs: Long = 120_000,
        /** The biggest answer a Spotify call gives the script. */
        val spotifyResultChars: Int = 2 * 1024 * 1024,
        val storageChars: Int = 512 * 1024,
    )

    private val id = manifest.id
    private val thread: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ext-$id").apply { isDaemon = true; priority = Thread.NORM_PRIORITY }
    }
    private var scope: ScriptableObject? = null
    @Volatile private var deadline = Long.MAX_VALUE
    private val requestTimes = ArrayDeque<Long>()
    private val spotifyTimes = ArrayDeque<Long>()
    /** Time spent waiting for the user in the run on the thread now: it is not the script's time. */
    @Volatile private var activePause: Pause? = null

    /** The last failure, for the extension's page ("this extension failed: …"). */
    @Volatile var lastError: String? = null
        private set

    /** Loads the script. Throws [ExtensionException] when it does not load. */
    fun start() {
        // Compiling is not the script's time: on a phone busy starting the app it alone can take
        // seconds, and an extension was turned off for it. [Limits.loadMs] counts its top level only.
        val (prelude, main) = run(limits.compileMs) { cx ->
            cx.compileString(PRELUDE, "prelude.js", 1, null) to cx.compileString(script, manifest.main, 1, null)
        }
        run(limits.loadMs) { cx ->
            val s = cx.initSafeStandardObjects(null, false)
            ScriptableObject.putProperty(s, "__host", hostObject(cx, s))
            prelude.exec(cx, s)
            scope = s
            main.exec(cx, s)
            null
        }
    }

    /** Whether the user lets the script provide [hook] (its action declared and granted; `tap`: any drawn action; `status`: always). */
    fun provides(hook: Hook): Boolean = hook.free || (hook.action?.let(::has) ?: Action.BLOCKS.any(::has))

    /**
     * Calls the script's [hook] with [inputJson]; its answer as JSON text, or null when it has
     * none. Throws [ExtensionException] on failure or after [timeoutMs]. A drawn hook answers
     * nothing while a protected surface is on screen.
     */
    fun call(hook: Hook, inputJson: String, timeoutMs: Long): String? {
        if (!provides(hook)) return null
        if (hook.drawn && guard.suspended) return null
        return run(timeoutMs) { cx -> dispatch(cx, "hook", hook.key, inputJson) }
    }

    private val listening: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** Whether the script listens to [event] and may receive it (so the app can skip the work). */
    fun listens(event: String): Boolean = event in listening && EVENTS[event]?.let(::has) == true

    /**
     * Delivers an app event (the listeners' answers are ignored). One still waiting its turn after
     * [timeoutMs] (a long hook call held the extension's thread) is dropped quietly: not the
     * script's failure, nothing logged and no [lastError].
     */
    fun emit(event: String, inputJson: String, timeoutMs: Long = 3_000) {
        if (EVENTS[event]?.let(::has) != true) return
        runCatching { run(timeoutMs, quietWhileQueued = true) { cx -> dispatch(cx, "event", event, inputJson) } }
    }

    /** Whether [close] ran: a call it cut short is not the script's failure. */
    val closed: Boolean get() = thread.isShutdown

    override fun close() {
        thread.shutdownNow()
        pool?.shutdownNow()
    }

    private fun dispatch(cx: Context, kind: String, name: String, input: String): String? {
        val s = scope ?: throw ExtensionException("not started")
        val fn = ScriptableObject.getProperty(s, "__dispatch") as Callable
        val out = fn.call(cx, s, s, arrayOf(kind, name, input))
        return if (out == null || out is Undefined) null else Context.toString(out)
    }

    /** [quietWhileQueued]: a run that never started within [timeoutMs] is dropped without a failure. */
    private fun <T> run(timeoutMs: Long, quietWhileQueued: Boolean = false, block: (Context) -> T): T {
        val pause = Pause()
        // The time limit counts from when the call runs: one waiting behind a slow call (a song
        // looked up for 15 s) has used none of its own time.
        val began = java.util.concurrent.atomic.AtomicLong(0)
        val task = thread.submit<T> {
            began.set(System.nanoTime())
            activePause = pause
            deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            try {
                FACTORY.call { cx -> block(cx) }
            } finally {
                deadline = Long.MAX_VALUE
                activePause = null
            }
        }
        val queued = System.nanoTime()
        return try {
            // Still waiting for its turn after its own time plus the longest call: it is dropped.
            while (began.get() == 0L) {
                val waited = System.nanoTime() - queued
                // An event not run within its own time is dropped without a failure.
                if (quietWhileQueued && waited > TimeUnit.MILLISECONDS.toNanos(timeoutMs)) {
                    task.cancel(false)
                    if (began.get() == 0L) throw ExtensionException("dropped: the extension was busy")
                    break
                }
                if (waited > TimeUnit.MILLISECONDS.toNanos(timeoutMs + QUEUE_MS)) {
                    task.cancel(false)
                    if (began.get() == 0L) throw ExtensionBusy("busy with earlier calls: not run")
                    break
                }
                try { return task.get(10, TimeUnit.MILLISECONDS) } catch (_: TimeoutException) { /* not its turn yet */ }
            }
            await(task, timeoutMs, began.get(), pause)
        } catch (e: TimeoutException) {
            task.cancel(true)
            fail("took longer than $timeoutMs ms", e, timeout = true)
        } catch (e: java.util.concurrent.ExecutionException) {
            val c = e.cause
            when (c) {
                is TimeLimit -> fail("took longer than $timeoutMs ms", c, timeout = true)
                is RhinoException -> fail("${c.details()} (${c.sourceName()}:${c.lineNumber()})", c)
                is ExtensionException -> fail(c.message ?: "failed", c)
                else -> fail(c?.toString() ?: "failed", c)
            }
        }
    }

    /** Waits for [task] for [timeoutMs] (and a little more), plus as long as the user was asked something meanwhile. */
    private fun <T> await(task: java.util.concurrent.Future<T>, timeoutMs: Long, started: Long, pause: Pause): T {
        while (true) {
            val left = TimeUnit.MILLISECONDS.toNanos(timeoutMs + 500) + pause.nanos() - (System.nanoTime() - started)
            if (left <= 0) throw TimeoutException()
            try { return task.get(left, TimeUnit.NANOSECONDS) } catch (_: TimeoutException) { /* the pause may have grown: look again */ }
        }
    }

    private fun fail(message: String, cause: Throwable?, timeout: Boolean = false): Nothing {
        if (closed) throw ExtensionException("stopped", cause)
        lastError = message
        host.log(id, "failed: $message")
        throw if (timeout) ExtensionTimeout(message, cause) else ExtensionException(message, cause)
    }

    // ── What the script can ask of the app ──

    private fun hostObject(cx: Context, s: Scriptable): Scriptable {
        val o = cx.newObject(s)
        fun def(name: String, length: Int, fn: (Array<Any?>) -> Any?) =
            ScriptableObject.putProperty(o, name, LambdaFunction(s, name, length) { _, _, _, args -> fn(args) ?: Undefined.instance })
        fun str(args: Array<Any?>, i: Int): String = args.getOrNull(i)?.takeUnless { it is Undefined }?.let { Context.toString(it) }.orEmpty()

        def("info", 0) { ExtJson.encodeToString(Info.serializer(), Info(manifest.id, manifest.name, manifest.version)) }
        def("mayProvide", 1) { a ->
            val key = str(a, 0)
            val hook = Hook.of(key) ?: throw error("unknown hook \"$key\" (known: ${Hook.entries.joinToString { it.key }})")
            val action = hook.action
            if (hook.free) return@def null
            if (action == null) {
                if (Action.BLOCKS.none { it in declared }) throw error("provide(\"tap\") needs an action drawn as blocks (${Action.BLOCKS.joinToString { it.key }})")
            } else if (action !in declared) throw error("provide(\"$key\") needs the ${action.key} action in the manifest")
            null
        }
        def("mayListen", 1) { a ->
            val event = str(a, 0)
            val action = EVENTS[event] ?: throw error("unknown event \"$event\" (known: ${EVENTS.keys.joinToString()})")
            if (action !in declared) throw error("on(\"$event\") needs the ${action.key} action")
            listening += event
            null
        }
        def("http", 1) { a -> http(str(a, 0)) }
        def("httpAll", 1) { a -> httpAll(str(a, 0)) }
        def("storageGet", 1) { a -> need(Action.STORAGE); host.storage(id).get(str(a, 0)) }
        def("storageSet", 2) { a ->
            need(Action.STORAGE)
            val store = host.storage(id)
            val key = str(a, 0)
            val value = str(a, 1)
            val after = store.size() - (store.get(key)?.let { it.length + key.length } ?: 0) + key.length + value.length
            if (after > limits.storageChars) throw error("storage full (${limits.storageChars / 1024} KB)")
            store.set(key, value)
            null
        }
        def("storageRemove", 1) { a -> need(Action.STORAGE); host.storage(id).remove(str(a, 0)); null }
        def("setting", 1) { a ->
            val key = str(a, 0)
            host.setting(id, key) ?: manifest.settings.firstOrNull { it.key == key }?.default?.toString()
        }
        def("nowPlaying", 0) { need(Action.PLAYBACK_EVENTS); host.nowPlaying() }
        def("control", 2) { a ->
            need(Action.PLAYBACK_CONTROL)
            val action = str(a, 0)
            if (action !in CONTROLS) throw error("unknown player action \"$action\"")
            val arg = (a.getOrNull(1) as? Number)?.toLong()?.takeIf { it >= 0 }
            host.control(id, action, arg)
        }
        def("notify", 1) { a -> need(Action.NOTIFY); host.notify(id, str(a, 0).take(200)); null }
        def("refresh", 1) { a ->
            val surface = Surface.entries.firstOrNull { it.key == str(a, 0) || it.key.substringBefore('.') == str(a, 0) }
                ?: throw error("ui.refresh: unknown place \"${str(a, 0)}\" (${Surface.entries.joinToString { it.key.substringBefore('.') }.split(", ").distinct().joinToString()})")
            need(surface.action)
            host.refresh(id, surface.key.substringBefore('.'))
            null
        }
        def("pagePost", 1) { a ->
            need(Action.WEBVIEW_PAGE)
            val json = str(a, 0)
            if (json.length > MAX_PAGE_MESSAGE) throw error("page.post: message over ${MAX_PAGE_MESSAGE / 1024} KB")
            if (!guard.suspended) host.postToPage(id, json)
            null
        }
        def("authOpen", 2) { a ->
            need(Action.WEBVIEW_AUTH)
            val request = try { AuthRequest(str(a, 0), str(a, 1), hidden = a.getOrNull(2) == true).checked() } catch (e: IllegalArgumentException) { throw error(e.message ?: "auth.open: refused") }
            runCatching { host.openAuth(id, request) }.getOrElse { throw error("auth.open: ${it.message ?: it.javaClass.simpleName}") }
        }
        def("whenOnline", 0) { need(Action.NETWORK); host.whenOnline(id); null }
        def("log", 1) { a -> host.log(id, str(a, 0).take(2_000)); null }
        def("util", 2) { a -> util(str(a, 0), str(a, 1)) }
        def("spotifyMethods", 0) { ExtJson.encodeToString(METHOD_NAMES, SpotifyMethods.names()) }
        def("spotify", 3) { a -> spotify(str(a, 0), str(a, 1), str(a, 2)) }
        return o
    }

    private fun need(a: Action) {
        if (!has(a)) throw error(
            if (a in declared) "the user did not grant ${a.key}"
            else "${a.key} is not in this extension's manifest (actions)",
        )
    }

    private fun http(json: String): String {
        val r = runCatching { ExtJson.decodeFromString(HttpRequest.serializer(), json) }.getOrElse { throw error("http: unreadable request") }
        refusal(r)?.let { throw error(it) }
        throttle()
        val response = runCatching { host.http(id, r.copy(timeout = r.timeout.coerceAtMost(remainingMs()))) }
            .getOrElse { throw error("http: ${it.message ?: it.javaClass.simpleName}") }
        return ExtJson.encodeToString(HttpResponse.serializer(), response)
    }

    /** Why [r] may not be sent (its host, its scheme), or null. */
    private fun refusal(r: HttpRequest): String? {
        val uri = runCatching { URI(r.url) }.getOrElse { return "http: not a URL: ${r.url}" }
        val hostName = uri.host?.lowercase() ?: return "http: no host in ${r.url}"
        if (isPrivate(hostName)) return "http: $hostName is a local address"
        val listed = manifest.hosts()
        val anyHost = Action.NETWORK_ANY in granted && "*" in listed && Action.NETWORK in declared
        val named = listed.any { it != "*" && hostMatches(it, hostName) }
        if (!(anyHost || (Action.NETWORK.key in granted && Action.NETWORK in declared && named)))
            return "http: $hostName is not in this extension's network hosts"
        // Plain http only to a host named outright: never through a wildcard or "any host".
        val exact = listed.any { it == hostName }
        if (uri.scheme != "https" && !(uri.scheme == "http" && exact)) return "http: https only (or http to a host the manifest names exactly)"
        return null
    }

    /** For `http.all`: at most [MAX_PARALLEL] requests at a time, made only while the script waits. */
    @Volatile private var pool: ExecutorService? = null
    private fun parallel(): ExecutorService = pool ?: synchronized(this) {
        pool ?: Executors.newFixedThreadPool(MAX_PARALLEL) { r -> Thread(r, "ext-$id-http").apply { isDaemon = true } }.also { pool = it }
    }

    /**
     * `http.all([request, ...])`: up to [MAX_PARALLEL] requests sent together, each checked and
     * counted like one `http.request`; the answers in the same order, `{ error }` in place of one
     * that failed. The script waits for all of them, within its time limit.
     */
    private fun httpAll(json: String): String {
        val items = runCatching { ExtJson.parseToJsonElement(json) as JsonArray }.getOrElse { throw error("http.all: unreadable requests") }
        if (items.size > MAX_PARALLEL) throw error("http.all: at most $MAX_PARALLEL requests at once")
        val jobs = items.map { e ->
            val r = runCatching { ExtJson.decodeFromJsonElement(HttpRequest.serializer(), e) }.getOrNull()
                ?: return@map Result.failure<java.util.concurrent.Future<HttpResponse>>(IllegalArgumentException("http: unreadable request"))
            val why = refusal(r) ?: if (!allowRequest()) "more than ${limits.requestsPerMinute} requests in a minute" else null
            if (why != null) return@map Result.failure(IllegalArgumentException(why))
            val sent = r.copy(timeout = r.timeout.coerceAtMost(remainingMs()))
            Result.success(parallel().submit<HttpResponse> { host.http(id, sent) })
        }
        try {
            val answers = jobs.map { job ->
                job.mapCatching { f ->
                    try { f.get(leftMs(), TimeUnit.MILLISECONDS) } catch (e: java.util.concurrent.ExecutionException) { throw e.cause ?: e }
                }.fold(
                    { ExtJson.encodeToJsonElement(HttpResponse.serializer(), it) },
                    { e ->
                        if (e is TimeoutException || e is TimeLimit) throw TimeLimit()
                        if (e is InterruptedException) throw e
                        JsonObject(mapOf("error" to JsonPrimitive(e.message ?: e.javaClass.simpleName)))
                    },
                )
            }
            return JsonArray(answers).toString()
        } finally {
            jobs.forEach { it.getOrNull()?.cancel(true) }
        }
    }

    private fun throttle(times: ArrayDeque<Long> = requestTimes, max: Int = limits.requestsPerMinute, what: String = "requests") {
        if (!allowRequest(times, max)) throw error("more than $max $what in a minute")
    }

    /** Counts one more call in [times], or false when [max] were made in the last minute. */
    private fun allowRequest(times: ArrayDeque<Long> = requestTimes, max: Int = limits.requestsPerMinute): Boolean {
        val now = System.currentTimeMillis()
        synchronized(times) {
            while (times.isNotEmpty() && now - times.first() > 60_000) times.removeFirst()
            if (times.size >= max) return false
            times.addLast(now)
            return true
        }
    }

    private fun leftMs(): Long {
        val left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
        if (left <= 0) throw TimeLimit()
        return left
    }

    private fun remainingMs(): Int = leftMs().coerceAtMost(20_000).toInt()

    /**
     * `myspoti.spotify.<area>.<method>(...)`: the method must be in [SpotifyMethods] and its right
     * granted; a destructive one runs only if the user says yes ([Host.confirm]). The script's
     * thread waits for the host, within the call's time limit.
     */
    private fun spotify(area: String, name: String, argsJson: String): String {
        val method = SpotifyMethods.find(area, name) ?: throw error("spotify.$area.$name: no such method")
        need(method.action)
        val args = SpotifyArgs(
            runCatching { ExtJson.parseToJsonElement(argsJson) as JsonArray }.getOrElse { throw error("spotify.${method.key}: unreadable arguments") },
            "spotify.${method.key}",
        )
        if (argsJson.length > MAX_SPOTIFY_ARGS) throw error("spotify.${method.key}: arguments over ${MAX_SPOTIFY_ARGS / 1024} KB")
        val destructive = try { method.isDestructive(args, host::spotifyMutation) } catch (e: IllegalArgumentException) { throw error(e.message ?: "bad arguments") }
        if (destructive && !mayWrite(method, args, argsJson)) throw error("spotify.${method.key}: the user refused")
        throttle(spotifyTimes, limits.spotifyCallsPerMinute, "Spotify calls")
        val result = try {
            runBlocking { withTimeout(leftMs()) { host.spotify(id, SpotifyCall(area, name, argsJson)) } }
        } catch (e: TimeoutCancellationException) {
            throw TimeLimit()
        } catch (e: InterruptedException) {
            throw e
        } catch (e: Exception) {
            val why = e.message ?: e.javaClass.simpleName
            throw error(if (why.startsWith("spotify.")) why else "spotify.${method.key}: $why")
        }
        if (result.length > limits.spotifyResultChars) throw error("spotify.${method.key}: answer over ${limits.spotifyResultChars / 1024 / 1024} MB")
        return result
    }

    /**
     * Whether a destructive call may run: asked each time, apart from a `raw.request` write one of
     * the user's [RawGrant]s covers; that question offers "always" for this kind of request.
     */
    private fun mayWrite(method: SpotifyMethod, args: SpotifyArgs, argsJson: String): Boolean {
        val text = "${manifest.name} wants to ${method.label}.\n${summary(argsJson)}"
        if (method.key != RAW_REQUEST) return confirm(text)
        val verb = RawRequest.check(args.str(0, "method"), args.str(1, "url"))
        val url = args.str(1, "url")
        if (RawRequest.covered(host.rawGrants(id), verb, url)) return true
        val offer = RawRequest.offer(verb, url)
        return paused { host.confirmWrite(id, text, offer) }.let { it == Consent.ONCE || it == Consent.ALWAYS }
    }

    /** Asks the user through the host; the wait is not the script's time. */
    private fun confirm(text: String): Boolean = paused { host.confirm(id, text) } == true

    private fun <T> paused(ask: suspend () -> T): T? {
        val pause = activePause
        pause?.begin()
        val started = System.nanoTime()
        try {
            return try {
                runBlocking { withTimeout(limits.confirmMs) { ask() } }
            } catch (e: TimeoutCancellationException) {
                null
            }
        } finally {
            val waited = System.nanoTime() - started
            if (deadline != Long.MAX_VALUE) deadline += waited
            pause?.end(waited)
        }
    }

    private fun summary(argsJson: String) = argsJson.take(300) + if (argsJson.length > 300) "…" else ""

    /** The time a run spent waiting for the user: finished waits, and the one going on. */
    private class Pause {
        private val done = AtomicLong()
        @Volatile private var since = 0L
        fun begin() { since = System.nanoTime() }
        fun end(nanos: Long) { done.addAndGet(nanos); since = 0L }
        fun nanos(): Long = done.get() + since.let { if (it == 0L) 0L else System.nanoTime() - it }
    }

    private fun util(op: String, input: String): String = when (op) {
        "base64Encode" -> java.util.Base64.getEncoder().encodeToString(input.toByteArray())
        "base64Decode" -> runCatching { String(java.util.Base64.getDecoder().decode(input)) }.getOrElse { throw error("base64Decode: not base64") }
        "md5" -> hex(MessageDigest.getInstance("MD5").digest(input.toByteArray()))
        "sha1" -> hex(MessageDigest.getInstance("SHA-1").digest(input.toByteArray()))
        "sha256" -> hex(MessageDigest.getInstance("SHA-256").digest(input.toByteArray()))
        "sha512" -> hex(MessageDigest.getInstance("SHA-512").digest(input.toByteArray()))
        "hmacSha256" -> {
            val key = input.substringBefore('\u0000')
            val text = input.substringAfter('\u0000')
            val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key.toByteArray(), "HmacSHA256")) }
            hex(mac.doFinal(text.toByteArray()))
        }
        "pbkdf2Sha256" -> {
            val parts = input.split('\u0000', limit = 3)
            val password = parts.getOrElse(0) { "" }
            val salt = parts.getOrElse(1) { "" }.ifEmpty { "wavebits-pbkdf2-default-salt" }
            val iterations = parts.getOrNull(2)?.toIntOrNull()?.coerceIn(10_000, 600_000) ?: 100_000
            val spec = javax.crypto.spec.PBEKeySpec(
                password.toCharArray(),
                salt.toByteArray(Charsets.UTF_8),
                iterations,
                256
            )
            try {
                val skf = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                hex(skf.generateSecret(spec).encoded)
            } finally {
                spec.clearPassword()
            }
        }
        else -> throw error("unknown util $op")
    }

    @kotlinx.serialization.Serializable
    private data class Info(val id: String, val name: String, val version: String)

    /** Thrown inside the script's thread when its time is up; not catchable by the script. */
    private class TimeLimit : Error("time limit")

    private class LimitedContext(f: ContextFactory) : Context(f)

    companion object {
        const val EVENT_TRACK = "track"
        /** Start, progress, pause, resume, seek, skip, end of a song (docs/extensions/API.md §3). */
        const val EVENT_PLAYBACK = "playback"
        /** A sign-in page ended ([AuthResult]). */
        const val EVENT_AUTH = "auth"
        /** The network is there after [Host.whenOnline]: the time to send what waited. */
        const val EVENT_ONLINE = "online"
        /** One of its settings changed (`{ key }`), or a button setting was pressed (`{ key, pressed: true }`). */
        const val EVENT_SETTING = "setting"
        /** Each event, and the action an extension needs to receive it. */
        val EVENTS: Map<String, Action> = mapOf(
            EVENT_TRACK to Action.PLAYBACK_EVENTS,
            EVENT_PLAYBACK to Action.PLAYBACK_EVENTS,
            EVENT_AUTH to Action.WEBVIEW_AUTH,
            EVENT_ONLINE to Action.NETWORK,
            EVENT_SETTING to Action.SETTINGS_PAGE,
        )
        private const val RAW_REQUEST = "raw.request"
        private val METHOD_NAMES = MapSerializer(String.serializer(), ListSerializer(String.serializer()))
        private const val MAX_SPOTIFY_ARGS = 256 * 1024
        private const val MAX_PAGE_MESSAGE = 64 * 1024
        /** `http.all`: the most requests sent together. */
        const val MAX_PARALLEL = 4
        private val CONTROLS = setOf("play", "pause", "next", "previous", "seek")

        private val PRELUDE: String by lazy {
            runCatching {
                (ExtensionRuntime::class.java.getResourceAsStream("/app/wavebits/extensions/prelude.js")
                    ?: ExtensionRuntime::class.java.getResourceAsStream("prelude.js"))
                    ?.bufferedReader(Charsets.UTF_8)
                    ?.use { it.readText() }
            }.getOrNull()?.takeIf { it.isNotBlank() } ?: DEFAULT_PRELUDE
        }

        private val FACTORY: ContextFactory by lazy {
            object : ContextFactory() {
                override fun makeContext(): Context = LimitedContext(this).apply {
                    // Android cannot load generated classes, and instruction counting needs the interpreter.
                    isInterpretedMode = true
                    languageVersion = Context.VERSION_ES6
                    instructionObserverThreshold = 5_000
                    setMaximumInterpreterStackDepth(800)
                    // No Java at all: no class can be named, built or reached from a script.
                    setClassShutter { false }
                }

                override fun observeInstructionCount(cx: Context, instructionCount: Int) {
                    val runtime = RUNNING.get() ?: return
                    if (System.nanoTime() > runtime.deadline) throw TimeLimit()
                }
            }
        }

        /** The runtime whose script runs on this thread, for the instruction observer. */
        private val RUNNING = ThreadLocal<ExtensionRuntime>()

        private val DEFAULT_PRELUDE: String = """
(function (host, global) {
  'use strict';
  var hooks = {};
  var listeners = {};
  var parse = function (text) { return text == null ? undefined : JSON.parse(text); };

  function response(r) {
    r.ok = r.status >= 200 && r.status < 300;
    r.json = function () { return JSON.parse(r.body); };
    return r;
  }

  function shape(r) {
    if (r == null || typeof r.url !== 'string') throw new TypeError('http.request({ url, method, headers, body })');
    var body = r.body;
    var headers = r.headers || {};
    if (body != null && typeof body !== 'string') {
      body = JSON.stringify(body);
      if (!Object.keys(headers).some(function (k) { return k.toLowerCase() === 'content-type'; })) {
        headers = Object.assign({ 'Content-Type': 'application/json' }, headers);
      }
    }
    return { url: r.url, method: r.method || 'GET', headers: headers, body: body == null ? undefined : body, timeout: r.timeout };
  }

  function request(r) { return response(parse(host.http(JSON.stringify(shape(r))))); }

  function all(list) {
    if (!Array.isArray(list)) throw new TypeError('http.all([{ url, method, headers, body }, ...])');
    return parse(host.httpAll(JSON.stringify(list.map(shape)))).map(function (r) { return r.error != null ? r : response(r); });
  }

  var spotify = {};
  var methods = parse(host.spotifyMethods());
  Object.keys(methods).forEach(function (area) {
    var calls = {};
    methods[area].forEach(function (name) {
      calls[name] = function () {
        return parse(host.spotify(area, name, JSON.stringify(Array.prototype.slice.call(arguments))));
      };
    });
    spotify[area] = Object.freeze(calls);
  });
  if (spotify.player && spotify.player.media) spotify.media = spotify.player.media;

  var myspoti = {
    apiVersion: 1,
    extension: Object.freeze(parse(host.info())),

    provide: function (hook, fn) {
      if (typeof fn !== 'function') throw new TypeError('provide("' + hook + '", fn): fn must be a function');
      host.mayProvide(String(hook));
      hooks[hook] = fn;
    },

    on: function (event, fn) {
      if (typeof fn !== 'function') throw new TypeError('on("' + event + '", fn): fn must be a function');
      host.mayListen(String(event));
      (listeners[event] = listeners[event] || []).push(fn);
    },

    http: Object.freeze({
      request: request,
      all: all,
      get: function (url, options) { return request(Object.assign({}, options, { url: url, method: 'GET' })); },
      post: function (url, body, options) { return request(Object.assign({}, options, { url: url, method: 'POST', body: body })); },
    }),

    query: function (params) {
      return Object.keys(params || {}).filter(function (k) { return params[k] != null; })
        .map(function (k) { return encodeURIComponent(k) + '=' + encodeURIComponent(String(params[k])); }).join('&');
    },

    storage: Object.freeze({
      get: function (key) { return parse(host.storageGet(String(key))); },
      set: function (key, value) { host.storageSet(String(key), JSON.stringify(value === undefined ? null : value)); },
      remove: function (key) { host.storageRemove(String(key)); },
    }),

    settings: Object.freeze({
      get: function (key) { return parse(host.setting(String(key))); },
    }),

    player: Object.freeze({
      current: function () { return parse(host.nowPlaying()); },
      play: function () { return host.control('play', -1); },
      pause: function () { return host.control('pause', -1); },
      next: function () { return host.control('next', -1); },
      previous: function () { return host.control('previous', -1); },
      seek: function (ms) { return host.control('seek', Number(ms)); },
    }),

    spotify: Object.freeze(spotify),

    notify: function (text) { host.notify(String(text)); },

    features: Object.freeze(['audio.manifest', 'audio.expires', 'audio.quality', 'audio.source', 'audio.ahead', 'http.all', 'status', 'raw.base64', 'player.localMute']),

    auth: Object.freeze({
      open: function (r) {
        if (r == null || typeof r.url !== 'string' || typeof r.callback !== 'string') throw new TypeError('auth.open({ url, callback })');
        return host.authOpen(r.url, r.callback, r.hidden === true);
      },
    }),

    work: Object.freeze({
      whenOnline: function () { host.whenOnline(); },
    }),

    ui: Object.freeze({
      refresh: function (place) { host.refresh(String(place)); },
    }),

    page: Object.freeze({
      post: function (data) { host.pagePost(JSON.stringify(data === undefined ? null : data)); },
    }),

    log: function () {
      host.log(Array.prototype.map.call(arguments, function (a) {
        return typeof a === 'string' ? a : JSON.stringify(a);
      }).join(' '));
    },

    util: Object.freeze({
      base64Encode: function (text) { return host.util('base64Encode', String(text)); },
      base64Decode: function (text) { return host.util('base64Decode', String(text)); },
      md5: function (text) { return host.util('md5', String(text)); },
      sha1: function (text) { return host.util('sha1', String(text)); },
      sha256: function (text) { return host.util('sha256', String(text)); },
      sha512: function (text) { return host.util('sha512', String(text)); },
      hmacSha256: function (key, text) { return host.util('hmacSha256', String(key) + '\u0000' + String(text)); },
      pbkdf2Sha256: function (password, salt, iterations) {
        return host.util('pbkdf2Sha256', String(password) + '\u0000' + String(salt || '') + '\u0000' + String(iterations || 100000));
      },
    }),
  };

  function dispatch(kind, name, args) {
    var input = parse(args);
    if (kind === 'hook') {
      var fn = hooks[name];
      if (!fn) return null;
      var out = fn(input);
      return out == null ? null : JSON.stringify(out);
    }
    (listeners[name] || []).forEach(function (fn) { fn(input); });
    return null;
  }

  var console = Object.freeze({ log: myspoti.log, info: myspoti.log, warn: myspoti.log, error: myspoti.log });
  var fixed = { writable: false, configurable: false, enumerable: false };
  var frozenMyspoti = Object.freeze(myspoti);
  Object.defineProperty(global, 'myspoti', Object.assign({ value: frozenMyspoti }, fixed));
  Object.defineProperty(global, 'wavebits', Object.assign({ value: frozenMyspoti }, fixed));
  Object.defineProperty(global, 'console', Object.assign({ value: console }, fixed));
  Object.defineProperty(global, '__dispatch', Object.assign({ value: dispatch }, fixed));

  var refused = function Function() { throw new Error('code from strings is not allowed in extensions'); };
  refused.prototype = Function.prototype;
  Object.defineProperty(global, 'eval', Object.assign({ value: refused }, fixed));
  Object.defineProperty(Function.prototype, 'constructor', Object.assign({ value: refused }, fixed));
  Object.defineProperty(global, 'Function', Object.assign({ value: refused }, fixed));
  try {
    var generator = Object.getPrototypeOf(function* () {});
    Object.defineProperty(generator, 'constructor', Object.assign({ value: refused }, fixed));
  } catch (e) { /* no generators in this engine */ }
})(__host, this);
delete this.__host;
""".trimIndent()

        private fun error(message: String): RuntimeException = ScriptRuntime.constructError("Error", message)

        private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

        internal fun hostMatches(pattern: String, host: String): Boolean = when {
            pattern == "*" -> true
            pattern.startsWith("*.") -> host == pattern.removePrefix("*.") || host.endsWith(pattern.removePrefix("*"))
            else -> host == pattern
        }

        fun isPrivate(host: String): Boolean {
            if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local")) return true
            val v4 = host.split('.').mapNotNull { it.toIntOrNull() }.takeIf { it.size == 4 && host.count { c -> c == '.' } == 3 }
            if (v4 != null) {
                val (a, b) = v4
                return a == 10 || a == 127 || a == 0 ||
                    (a == 100 && b in 64..127) ||
                    (a == 172 && b in 16..31) ||
                    (a == 192 && b == 168) ||
                    (a == 169 && b == 254)
            }
            return host.startsWith("[") || host.contains(':')
        }

        /**
         * Validates a DNS-resolved [java.net.InetAddress] against private/non-routable IPv4 and IPv6
         * ranges without falsely rejecting legitimate public IPv6 addresses on dual-stack networks.
         */
        fun isPrivateAddress(addr: java.net.InetAddress): Boolean {
            if (addr.isAnyLocalAddress || addr.isLoopbackAddress || addr.isLinkLocalAddress ||
                addr.isSiteLocalAddress || addr.isMulticastAddress
            ) {
                return true
            }
            val bytes = addr.address
            if (bytes.size == 4) {
                val a = bytes[0].toInt() and 0xFF
                val b = bytes[1].toInt() and 0xFF
                return a == 0 || a == 10 || a == 127 ||
                    (a == 100 && b in 64..127) ||
                    (a == 169 && b == 254) ||
                    (a == 172 && b in 16..31) ||
                    (a == 192 && b == 168)
            }
            if (bytes.size == 16) {
                val b0 = bytes[0].toInt() and 0xFF
                val b1 = bytes[1].toInt() and 0xFF
                // Unique Local IPv6 (fc00::/7) and Link-Local (fe80::/10)
                if ((b0 and 0xFE) == 0xFC) return true
                if (b0 == 0xFE && (b1 and 0xC0) == 0x80) return true
                // IPv4-mapped IPv6 (::ffff:0:0/96): inspect embedded IPv4 address
                val isV4Mapped = (0..9).all { bytes[it].toInt() == 0 } &&
                    (bytes[10].toInt() and 0xFF) == 0xFF &&
                    (bytes[11].toInt() and 0xFF) == 0xFF
                if (isV4Mapped) {
                    val a = bytes[12].toInt() and 0xFF
                    val b = bytes[13].toInt() and 0xFF
                    return a == 0 || a == 10 || a == 127 ||
                        (a == 100 && b in 64..127) ||
                        (a == 169 && b == 254) ||
                        (a == 172 && b in 16..31) ||
                        (a == 192 && b == 168)
                }
            }
            return false
        }
    }

    init {
        // The instruction observer finds the runtime through its thread.
        thread.submit { RUNNING.set(this) }
    }
}
