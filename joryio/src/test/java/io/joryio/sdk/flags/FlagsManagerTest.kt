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
    }

    private class State {
        @Volatile var canSend = true
        @Volatile var userId: String? = null
        @Volatile var anonymousId = "anon-1"
        @Volatile var sessionId = "s-1"
        @Volatile var stored: String? = null
        @Volatile var now = 1_000_000L
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private fun manager(server: Server, state: State) = FlagsManager(
        scope = scope,
        post = { p, j -> server.post(p, j) },
        canSend = { state.canSend },
        userId = { state.userId },
        anonymousId = { state.anonymousId },
        sessionId = { state.sessionId },
        attributes = { mapOf("plan" to "pro") },
        context = { mapOf("platform" to "android") },
        load = { state.stored },
        save = { state.stored = it },
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

    @Test
    fun `the silent push value and the refresh interval are pinned`() {
        assertEquals("flags", FLAG_SYNC_PUSH_VALUE)
        assertEquals(60L, FLAGS_MIN_REFRESH_SECONDS)
    }

    data class Theme(val color: String)

    companion object {
        const val ANSWER = """{"etag":"e1","ttlSeconds":300,"flags":{
            "new_checkout":{"value":true,"variant":"on","valueType":"boolean","reason":"rule","version":1,"logExposure":true},
            "headline":{"value":"Hello","variant":"b","valueType":"string","reason":"rule","version":1,"logExposure":true},
            "limit":{"value":5,"variant":"on","valueType":"number","reason":"rule","version":1,"logExposure":true},
            "theme":{"value":{"color":"teal"},"variant":"on","valueType":"json","reason":"rule","version":1,"logExposure":true},
            "quiet":{"value":true,"variant":"on","valueType":"boolean","reason":"rule","version":1,"logExposure":false}}}"""
    }
}
