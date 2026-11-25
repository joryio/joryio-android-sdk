package io.joryio.sdk.flags

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/** Feature flags (BACKLOG D446): the same behaviour as the web SDK's flags.spec.ts and iOS's FlagsTests. */
class FlagsManagerTest {

    private class Server {
        val calls = CopyOnWriteArrayList<Pair<String, JsonObject>>()
        @Volatile var answer: String = ANSWER
        @Volatile var delayMs = 0L

        suspend fun post(path: String, json: String): String? {
            calls.add(path to JsonParser.parseString(json).asJsonObject)
            if (delayMs > 0) delay(delayMs)
            return if (path == "v1/flags/evaluate") answer else """{"success":true}"""
        }

        fun calls(path: String) = calls.filter { it.first == path }.map { it.second }

        /** What the next exposures requests answer: an HTTP status, or [NETWORK] for no answer. Then 200. */
        val exposureAnswers = java.util.concurrent.ConcurrentLinkedQueue<Int>()

        /** The request whose first exposure is this flag answers 503 once. */
        @Volatile var failOnceStartingAt: String? = null

        suspend fun postExposures(json: String): FlagsPostStatus {
            val body = JsonParser.parseString(json).asJsonObject
            calls.add("v1/flags/exposures" to body)
            if (delayMs > 0) delay(delayMs)
            val first = body["exposures"].asJsonArray.firstOrNull()?.asJsonObject?.get("key")?.asString
            if (first != null && first == failOnceStartingAt) {
                failOnceStartingAt = null
                return FlagsPostStatus(503)
            }
            val status = exposureAnswers.poll() ?: 200
            if (status == NETWORK) throw java.io.IOException("unexpected end of stream")
            return FlagsPostStatus(status)
        }
    }

    private class State {
        @Volatile var canSend = true
        @Volatile var userId: String? = null
        @Volatile var anonymousId = "anon-1"
        @Volatile var sessionId = "s-1"
        @Volatile var stored: String? = null
        @Volatile var exposures: String? = null
        @Volatile var now = 1_000_000L

        /** The fake write outbox: the bodies handed to it. */
        val outbox = CopyOnWriteArrayList<String>()
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private fun manager(server: Server, state: State) = FlagsManager(
        scope = scope,
        post = { p, j -> server.post(p, j) },
        postExposures = { j -> server.postExposures(j) },
        enqueueExposures = { j -> state.outbox.add(j) },
        pendingExposures = { state.outbox.toList() },
        canSend = { state.canSend },
        userId = { state.userId },
        anonymousId = { state.anonymousId },
        sessionId = { state.sessionId },
        attributes = { mapOf("plan" to "pro") },
        context = { mapOf("platform" to "android") },
        load = { state.stored },
        save = { state.stored = it },
        loadExposures = { state.exposures },
        saveExposures = { state.exposures = it },
        postMain = { it.run() },
        now = { state.now },
        exposureFlushMs = 50,
    )

    private fun ready(server: Server = Server(), state: State = State()): Triple<FlagsManager, Server, State> {
        val m = manager(server, state)
        runBlocking { m.refreshFlags() }
        return Triple(m, server, state)
    }

    private fun waitUntil(timeoutMs: Long = 3000, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (cond()) return true
            Thread.sleep(10)
        }
        return cond()
    }

    @Test
    fun `nothing is fetched until the app uses flags`() {
        val server = Server()
        val m = manager(server, State())
        m.start()
        m.onForeground()
        Thread.sleep(100)
        assertTrue(server.calls.isEmpty())
        m.getBoolFlag("new_checkout", false)
        assertTrue(waitUntil { server.calls("v1/flags/evaluate").size == 1 })
    }

    @Test
    fun `the getters return the server value or the default`() {
        val (m, server) = ready()
        assertEquals(true, m.getBoolFlag("new_checkout", false))
        assertEquals("Hello", m.getStringFlag("headline", "x"))
        assertEquals(5.0, m.getNumberFlag("limit", 1.0), 0.0)
        assertEquals(mapOf("color" to "teal"), m.getJsonFlag("theme", null))
        assertEquals("Hello", m.getFlag("headline", "x"))
        assertEquals(true, m.getBoolFlag("missing", true))
        assertEquals("a string is not a boolean", false, m.getBoolFlag("headline", false))
        assertEquals("a boolean is not a number", 7.0, m.getNumberFlag("new_checkout", 7.0), 0.0)
        assertEquals("teal", m.getJsonFlag("theme", Theme::class.java, Theme("x")).color)
        val body = server.calls("v1/flags/evaluate")[0]
        assertEquals("anon-1", body["anonymousId"].asString)
        assertEquals("s-1", body["sessionId"].asString)
        assertEquals("android", body["context"].asJsonObject["platform"].asString)
        assertEquals("pro", body["attributes"].asJsonObject["plan"].asString)
        assertNull(body["etag"])
        assertNull(body["userId"])
    }

    @Test
    fun `the etag is sent back and notModified keeps the flags`() {
        val (m, server) = ready()
        server.answer = """{"notModified":true,"etag":"e1","flags":{}}"""
        runBlocking { m.refreshFlags() }
        assertEquals("e1", server.calls("v1/flags/evaluate").last()["etag"].asString)
        assertEquals("Hello", m.getStringFlag("headline", "x"))
    }

    @Test
    fun `foreground refreshes at most once per minute`() {
        val (m, server, state) = ready()
        runBlocking { m.refresh(force = false) }
        assertEquals(1, server.calls("v1/flags/evaluate").size)
        state.now += FLAGS_MIN_REFRESH_SECONDS * 1000 + 1
        runBlocking { m.refresh(force = false) }
        assertEquals(2, server.calls("v1/flags/evaluate").size)
    }

    @Test
    fun `one exposure per flag and variant per session, honouring logExposure`() {
        val (m, server, state) = ready()
        m.getBoolFlag("new_checkout", false)
        m.getBoolFlag("new_checkout", false)
        m.getStringFlag("headline", "")
        m.getBoolFlag("quiet", false) // logExposure: false
        m.getAllFlags() // logs nothing
        assertTrue(waitUntil { server.calls("v1/flags/exposures").size == 1 })
        val body = server.calls("v1/flags/exposures")[0]
        val exposures = body["exposures"].asJsonArray.map { it.asJsonObject }
        assertEquals(listOf("headline", "new_checkout"), exposures.map { it["key"].asString }.sorted())
        assertEquals("b", exposures.first { it["key"].asString == "headline" }["variant"].asString)
        assertEquals("anon-1", body["anonymousId"].asString)
        state.sessionId = "s-2"
        m.getBoolFlag("new_checkout", false)
        assertTrue(waitUntil { server.calls("v1/flags/exposures").size == 2 })
        // 1.4.0-beta.12: every batch carries its own idempotencyKey (a UUID), the
        // same in every copy of that request, so a re-sent batch is recorded once.
        val keys = server.calls("v1/flags/exposures").map { it["idempotencyKey"].asString }
        keys.forEach { assertTrue(it, Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$").matches(it)) }
        assertEquals("one key per batch", 2, keys.toSet().size)
    }

    @Test
    fun `getAllFlags logs no exposure`() {
        val (m, server) = ready()
        val all = m.getAllFlags()
        assertEquals(5, all.size)
        assertEquals("Hello", all["headline"])
        Thread.sleep(200)
        assertTrue(server.calls("v1/flags/exposures").isEmpty())
    }

    @Test
    fun `an exposure batch is sent as the person who read it`() {
        val (m, server, state) = ready()
        m.getBoolFlag("new_checkout", false) // read as anon-1
        state.userId = "user-b"
        state.anonymousId = "anon-2"
        m.onIdentityChanged()
        assertTrue(waitUntil { server.calls("v1/flags/exposures").size == 1 })
        val body = server.calls("v1/flags/exposures")[0]
        assertNull("never sent as the next person", body["userId"])
        assertEquals("anon-1", body["anonymousId"].asString)
        assertTrue(waitUntil { server.calls("v1/flags/evaluate").size == 2 })
        assertEquals("user-b", server.calls("v1/flags/evaluate")[1]["userId"].asString)
    }

    @Test
    fun `a new person in the same session is reported again`() {
        val (m, server, state) = ready()
        m.getBoolFlag("new_checkout", false)
        state.userId = "user-b"
        m.onIdentityChanged()
        runBlocking { m.refreshFlags() }
        m.getBoolFlag("new_checkout", false)
        assertTrue(waitUntil { server.calls("v1/flags/exposures").size == 2 })
        assertEquals("user-b", server.calls("v1/flags/exposures")[1]["userId"].asString)
    }

    @Test
    fun `withdrawn consent drops the batch and the getters return the default`() {
        val (m, server, state) = ready()
        m.getBoolFlag("new_checkout", false)
        state.canSend = false
        m.onIdentityChanged()
        Thread.sleep(200)
        assertTrue(server.calls("v1/flags/exposures").isEmpty())
        assertFalse(m.getBoolFlag("new_checkout", false))
        assertTrue(m.getAllFlags().isEmpty())
        runBlocking { m.refreshFlags() }
        assertEquals("nothing is sent without consent", 1, server.calls("v1/flags/evaluate").size)
    }

    @Test
    fun `the cache is kept on disk for the same person only`() {
        val (_, server, state) = ready()
        assertNotNull(state.stored)
        val again = manager(server, state)
        assertEquals("Hello", again.getStringFlag("headline", "x"))
        state.anonymousId = "someone-else"
        // The fake server answers "Hello" for anyone; hold its answer so this
        // reads what the new person found on disk, not a fetch that raced in.
        server.delayMs = 5_000
        val other = manager(server, state)
        assertEquals("x", other.getStringFlag("headline", "x"))
    }

    @Test
    fun `an answer for a previous person is discarded and the new person is fetched`() {
        val server = Server().apply { delayMs = 300 }
        val state = State()
        val m = manager(server, state)
        runBlocking {
            val first = async { m.refreshFlags() }
            delay(50)
            state.userId = "user-b"
            m.onIdentityChanged()
            first.await()
        }
        assertTrue(waitUntil { server.calls("v1/flags/evaluate").size == 2 })
        assertTrue(waitUntil { m.getStringFlag("headline", "x") == "Hello" })
        assertEquals("user-b", server.calls("v1/flags/evaluate")[1]["userId"].asString)
    }

    @Test
    fun `onFlagsUpdated gets the changed keys and close stops it`() {
        val server = Server()
        val m = manager(server, State())
        val received = CopyOnWriteArrayList<List<String>>()
        val sub = m.onFlagsUpdated { received.add(it) }
        assertTrue(waitUntil { received.size == 1 })
        assertEquals(5, received[0].size)
        server.answer = ANSWER.replace("\"Hello\"", "\"Bye\"").replace("\"e1\"", "\"e2\"")
        runBlocking { m.refreshFlags() }
        assertTrue(waitUntil { received.size == 2 })
        assertEquals(listOf("headline"), received[1])
        sub.close()
        server.answer = ANSWER
        runBlocking { m.refreshFlags() }
        Thread.sleep(200)
        assertEquals(2, received.size)
    }

    @Test
    fun `wipe forgets the answer on disk`() {
        val (m, server, state) = ready()
        server.delayMs = 1000 // the refetch the next read starts is still on the wire
        m.wipe()
        assertNull(state.stored)
        assertEquals("x", m.getStringFlag("headline", "x"))
    }

    // ── BACKLOG D479: the marks survive a relaunch inside the session ──

    /** A relaunch: a new manager over the same stores, the session resumed. */
    private fun relaunch(server: Server, state: State): FlagsManager {
        val m = manager(server, state)
        runBlocking { m.refreshFlags() }
        return m
    }

    @Test
    fun `a relaunch in the same session sends no exposure again`() {
        val (m, server, state) = ready()
        m.getBoolFlag("new_checkout", false)
        assertTrue(waitUntil { server.calls("v1/flags/exposures").size == 1 })
        val stored = JsonParser.parseString(state.exposures).asJsonObject
        assertEquals("s-1", stored["sessionId"].asString)
        assertEquals(listOf("s-1||anon-1|new_checkout|on"), stored["marks"].asJsonArray.map { it.asString })

        val again = relaunch(server, state)
        again.getBoolFlag("new_checkout", false)
        Thread.sleep(300)
        assertEquals("no duplicate after the relaunch", 1, server.calls("v1/flags/exposures").size)
        // A flag not read before the relaunch is still reported.
        again.getStringFlag("headline", "")
        assertTrue(waitUntil { server.calls("v1/flags/exposures").size == 2 })
        assertEquals(listOf("headline"), server.calls("v1/flags/exposures")[1]["exposures"].asJsonArray.map { it.asJsonObject["key"].asString })
    }

    @Test
    fun `a new session sends again and keeps only its own marks`() {
        val (m, server, state) = ready()
        m.getBoolFlag("new_checkout", false)
        assertTrue(waitUntil { server.calls("v1/flags/exposures").size == 1 })
        state.sessionId = "s-2" // the session expired before the relaunch
        val again = relaunch(server, state)
        again.getBoolFlag("new_checkout", false)
        assertTrue(waitUntil { server.calls("v1/flags/exposures").size == 2 })
        val stored = JsonParser.parseString(state.exposures).asJsonObject
        assertEquals("s-2", stored["sessionId"].asString)
        assertEquals(listOf("s-2||anon-1|new_checkout|on"), stored["marks"].asJsonArray.map { it.asString })
    }

    @Test
    fun `reset and identify of another user clear the marks`() {
        val (m, server, state) = ready()
        m.getBoolFlag("new_checkout", false)
        assertTrue(waitUntil { server.calls("v1/flags/exposures").size == 1 })
        assertNotNull(state.exposures)
        m.clearExposureMarks() // what reset() and identify() of another user call
        assertNull(state.exposures)
        m.getBoolFlag("new_checkout", false)
        assertTrue("cleared in memory too", waitUntil { server.calls("v1/flags/exposures").size == 2 })
        assertTrue(waitUntil { state.exposures != null })
        m.clearExposureMarks()
        relaunch(server, state).getBoolFlag("new_checkout", false)
        assertTrue("cleared on disk", waitUntil { server.calls("v1/flags/exposures").size == 3 })
        m.wipe()
        assertNull(state.exposures)
    }

    @Test
    fun `at most FLAG_EXPOSURE_MARKS_MAX marks are kept, the oldest go first`() {
        assertEquals(500, FLAG_EXPOSURE_MARKS_MAX)
        val many = (0..FLAG_EXPOSURE_MARKS_MAX).joinToString(",") { i ->
            """
            "f$i":{"value":true,"variant":"on","valueType":"boolean","reason":"rule","version":1,"logExposure":true}
            """.trim()
        }
        val server = Server().apply { answer = """{"etag":"m","ttlSeconds":300,"flags":{$many}}""" }
        val (m, _, state) = ready(server)
        for (i in 0..FLAG_EXPOSURE_MARKS_MAX) m.getBoolFlag("f$i", false)
        // Stored once the server took each request of at most 100 (1.4.0-beta.13).
        assertTrue(waitUntil { state.exposures?.contains("|f$FLAG_EXPOSURE_MARKS_MAX|on") == true })
        val marks = JsonParser.parseString(state.exposures).asJsonObject["marks"].asJsonArray.map { it.asString }
        assertEquals(FLAG_EXPOSURE_MARKS_MAX, marks.size)
        assertFalse("the oldest went first", marks.contains("s-1||anon-1|f0|on"))
        assertTrue(marks.contains("s-1||anon-1|f$FLAG_EXPOSURE_MARKS_MAX|on"))
    }

    // ── 1.4.0-beta.13: a mark is "sent" only once the server took its batch;
    // one it did not take goes to the write outbox (here a fake one) ──

    private fun marks(state: State): List<String> =
        state.exposures?.let { JsonParser.parseString(it).asJsonObject["marks"].asJsonArray.map { m -> m.asString } } ?: emptyList()

    @Test
    fun `the rule - 2xx and a 4xx other than 408 or 429 settle a batch, the rest go to the outbox`() {
        listOf(200, 202, 400, 401, 403, 404, 413, 422).forEach { assertTrue("$it", exposureBatchSettled(it)) }
        listOf(0, 408, 429, 500, 502, 503).forEach { assertFalse("$it", exposureBatchSettled(it)) }
    }

    private fun failedGoesToTheOutbox(failure: Int) {
        val (m, server, state) = ready()
        server.exposureAnswers.add(failure)
        m.getBoolFlag("new_checkout", false)
        assertTrue(waitUntil { state.outbox.size == 1 })
        val sent = server.calls("v1/flags/exposures")
        assertEquals(1, sent.size)
        assertEquals("the same body and key", sent[0], JsonParser.parseString(state.outbox[0]).asJsonObject)
        assertTrue("not marked before the server took it", marks(state).isEmpty())
        // A read meanwhile, and a relaunch with it still waiting: no second copy.
        m.getBoolFlag("new_checkout", false)
        val again = relaunch(server, state)
        again.getBoolFlag("new_checkout", false)
        Thread.sleep(300)
        assertEquals(1, server.calls("v1/flags/exposures").size)
        assertEquals(1, state.outbox.size)
        // The outbox delivered it.
        again.exposuresSettled(state.outbox[0])
        assertEquals(listOf("s-1||anon-1|new_checkout|on"), marks(state))
    }

    @Test
    fun `after a network error the batch goes to the outbox as sent, counts as exposed across a relaunch, and is marked when settled`() = failedGoesToTheOutbox(NETWORK)

    @Test
    fun `after a 503 the batch goes to the outbox as sent, counts as exposed across a relaunch, and is marked when settled`() = failedGoesToTheOutbox(503)

    @Test
    fun `after a 429 the batch goes to the outbox as sent, counts as exposed across a relaunch, and is marked when settled`() = failedGoesToTheOutbox(429)

    @Test
    fun `a 2xx marks the batch and nothing goes to the outbox`() {
        val (m, server, state) = ready()
        m.getBoolFlag("new_checkout", false)
        assertTrue(waitUntil { state.exposures != null })
        Thread.sleep(200)
        assertEquals(1, server.calls("v1/flags/exposures").size)
        assertTrue(state.outbox.isEmpty())
    }

    @Test
    fun `a permanent 4xx marks the batch - nothing goes to the outbox`() {
        val (m, server, state) = ready()
        server.exposureAnswers.add(400)
        m.getBoolFlag("new_checkout", false)
        assertTrue(waitUntil { state.exposures != null })
        Thread.sleep(200)
        assertEquals(1, server.calls("v1/flags/exposures").size)
        assertTrue(state.outbox.isEmpty())
    }

    @Test
    fun `150 exposures go as two requests of at most 100, a failed second one alone goes to the outbox`() {
        val many = (0 until 150).joinToString(",") { i ->
            """"f$i":{"value":true,"variant":"on","valueType":"boolean","reason":"rule","version":1,"logExposure":true}"""
        }
        val server = Server().apply {
            answer = """{"etag":"m","ttlSeconds":300,"flags":{$many}}"""
            failOnceStartingAt = "f100"
        }
        val (m, _, state) = ready(server)
        for (i in 0 until 150) m.getBoolFlag("f$i", false)
        assertTrue(waitUntil { server.calls("v1/flags/exposures").size == 2 && state.outbox.size == 1 })
        val sent = server.calls("v1/flags/exposures")
        assertEquals(listOf(100, 50), sent.map { it["exposures"].asJsonArray.size() })
        assertTrue(sent[0]["idempotencyKey"].asString != sent[1]["idempotencyKey"].asString)
        assertEquals((0 until 150).map { "f$it" }, sent.flatMap { b -> b["exposures"].asJsonArray.map { it.asJsonObject["key"].asString } })
        assertEquals(sent[1], JsonParser.parseString(state.outbox[0]).asJsonObject)
        assertTrue(waitUntil { marks(state).size == 100 })
        m.exposuresSettled(state.outbox[0])
        assertEquals(150, marks(state).size)
    }

    @Test
    fun `wipe - a queued batch is never sent, and one whose failed answer arrives after it is not handed to the outbox`() {
        val (m, server, state) = ready()
        m.getBoolFlag("new_checkout", false)
        m.wipe() // wipeData()
        Thread.sleep(300)
        assertEquals(0, server.calls("v1/flags/exposures").size)
        val again = manager(server, state).also { runBlocking { it.refreshFlags() } }
        server.delayMs = 200
        server.exposureAnswers.add(503)
        again.getStringFlag("headline", "")
        assertTrue(waitUntil { server.calls("v1/flags/exposures").size == 1 })
        again.wipe()
        Thread.sleep(500)
        assertTrue(state.outbox.isEmpty())
        assertNull(state.exposures)
    }

    @Test
    fun `a failed batch is not handed to the outbox once sending stopped (opt-out, consent)`() {
        val (m, server, state) = ready()
        server.delayMs = 200
        server.exposureAnswers.add(503)
        m.getBoolFlag("new_checkout", false)
        assertTrue(waitUntil { server.calls("v1/flags/exposures").size == 1 })
        state.canSend = false // opted out while it was on the wire
        Thread.sleep(500)
        assertTrue(state.outbox.isEmpty())
    }

    @Test
    fun `the silent push value and the refresh interval are pinned`() {
        assertEquals("flags", FLAG_SYNC_PUSH_VALUE)
        assertEquals(60L, FLAGS_MIN_REFRESH_SECONDS)
    }

    data class Theme(val color: String)

    companion object {
        /** A [Server.exposureAnswers] entry: no answer (a dropped connection). */
        const val NETWORK = -1

        const val ANSWER = """{"etag":"e1","ttlSeconds":300,"flags":{
            "new_checkout":{"value":true,"variant":"on","valueType":"boolean","reason":"rule","version":1,"logExposure":true},
            "headline":{"value":"Hello","variant":"b","valueType":"string","reason":"rule","version":1,"logExposure":true},
            "limit":{"value":5,"variant":"on","valueType":"number","reason":"rule","version":1,"logExposure":true},
            "theme":{"value":{"color":"teal"},"variant":"on","valueType":"json","reason":"rule","version":1,"logExposure":true},
            "quiet":{"value":true,"variant":"on","valueType":"boolean","reason":"rule","version":1,"logExposure":false}}}"""
    }
}
