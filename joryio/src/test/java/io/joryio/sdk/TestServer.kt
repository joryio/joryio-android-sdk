package io.joryio.sdk

import android.os.Looper
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.CopyOnWriteArrayList

/** One request as the server saw it. */
data class Seen(val method: String, val path: String, val body: String, val headers: okhttp3.Headers)

/**
 * A local Joryio backend: records every request and answers with [answers]
 * (by path suffix), defaulting to `{"success":true}`.
 */
class TestServer {
    val server = MockWebServer()
    val seen = CopyOnWriteArrayList<Seen>()
    val answers = java.util.concurrent.ConcurrentHashMap<String, () -> MockResponse>()

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                seen.add(Seen(request.method ?: "", path, request.body.readUtf8(), request.headers))
                answers.entries.firstOrNull { path.endsWith(it.key) || path.contains(it.key) }?.let { return it.value() }
                if (path.endsWith("/v1/in-app/sync")) {
                    return json("""{"success":true,"campaigns":[],"evaluatedAt":"2026-10-06T00:00:00.000Z"}""")
                }
                if (path.endsWith("/v1/track/batch")) return json("""{"success":true,"processed":1}""")
                return json("""{"success":true}""")
            }
        }
        server.start()
    }

    /** The apiHost an app would pass: `http://127.0.0.1:<port>/api`. */
    val apiHost: String get() = server.url("/api").toString()

    fun paths(): List<String> = seen.map { it.path }

    /**
     * Stop the server - without failing a test whose body passed (scan 8,
     * Android NICE 4). MockWebServer waits 5 s for its request threads and
     * then throws "Gave up waiting for queue to shut down"; an SDK request
     * still in flight on a loaded machine made a green test red in @After.
     * The wait is retried, and a server that still has a straggler is left
     * to it: its socket is already closed and every test starts its own.
     */
    fun shutdown() {
        repeat(SHUTDOWN_ATTEMPTS) { attempt ->
            try {
                server.shutdown()
                return
            } catch (e: java.io.IOException) {
                if (attempt == SHUTDOWN_ATTEMPTS - 1) {
                    System.err.println("TestServer: shutdown gave up waiting (${e.message}); left to finish on its own")
                }
            }
        }
    }

    companion object {
        private const val SHUTDOWN_ATTEMPTS = 3

        fun json(body: String, code: Int = 200) =
            MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)
    }
}

/**
 * Robolectric reports an active network with NO capabilities, which the SDK
 * reads as offline. Give it the internet capability.
 */
fun allowNetwork(context: android.content.Context) {
    val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
    val caps = org.robolectric.shadows.ShadowNetworkCapabilities.newInstance()
    shadowOf(caps).addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    shadowOf(cm).setNetworkCapabilities(cm.activeNetwork, caps)
}

/**
 * Run the main looper (where the SDK's coroutines resume) and let the network
 * threads work, until [done] or [timeoutMs].
 */
// 20 s of headroom for a loaded CI machine; a passing wait returns at once.
fun pumpUntil(timeoutMs: Long = 20_000, done: () -> Boolean): Boolean {
    val end = System.currentTimeMillis() + timeoutMs
    var last = System.currentTimeMillis()
    while (System.currentTimeMillis() < end) {
        // idleFor, not idle: the main looper's clock is Robolectric's, and it
        // only moves when told to - a coroutine delay() (the attribute
        // debounce, a retry timer) never fired under plain idle().
        val now = System.currentTimeMillis()
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(now - last))
        last = now
        if (done()) return true
        Thread.sleep(15)
    }
    shadowOf(Looper.getMainLooper()).idle()
    return done()
}

/** Pump for a fixed time - for asserting that something did NOT happen. */
fun pumpFor(ms: Long) {
    pumpUntil(ms) { false }
}

/**
 * Load Robolectric's native SQLite on the TEST thread before the SDK touches
 * Room. Loaded first from Room's background executor, it never finished: a
 * test that was the first in its JVM to open the queue hung on its first
 * insert (reproduced on the 1.3.0 base - it passed only when another test had
 * opened a database earlier in the same run).
 */
fun warmUpSqlite() {
    android.database.sqlite.SQLiteDatabase.create(null).close()
}

