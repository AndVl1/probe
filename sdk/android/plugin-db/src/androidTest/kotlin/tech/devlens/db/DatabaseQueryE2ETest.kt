package tech.devlens.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import tech.devlens.Probe
import tech.devlens.transport.WebSocketTransport
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Full-pipeline instrumented E2E tests for the database query protocol.
 *
 * Mirrors `plugin-network/FullPipelineE2ETest`. A fake CLI (MockWebServer)
 * sends a `query` frame; the full SDK path runs:
 *
 *   MockWebServer (CLI)
 *     → WebSocketTransport.onMessage parses the query
 *     → Probe.handleInbound routes it to DatabasePlugin
 *     → DatabasePlugin inspects SQLite on `Probe-Db` and calls host.send()
 *     → WebSocketTransport enqueues + sends the Event envelope
 *     → MockWebServer receives the `queryResult` event
 *
 * Requires cleartext traffic to localhost (the androidTest manifest's
 * networkSecurityConfig permits it) — without it MockWebServer's
 * `ws://localhost` upgrade is blocked on API 28+.
 */
@RunWith(AndroidJUnit4::class)
class DatabaseQueryE2ETest {

    private val gson = Gson()
    private lateinit var wsServer: MockWebServer
    private val wsClosedLatch = CountDownLatch(1)

    @Before
    fun setup() {
        wsServer = MockWebServer()
        // Ensure an inspectable DB exists in the target context before the test.
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        E2EFixtureDb(context).writableDatabase.use { /* force create + seed */ }
    }

    @After
    fun teardown() {
        Probe.uninstall()
        wsClosedLatch.await(3, TimeUnit.SECONDS)
        wsServer.close()
    }

    @Test
    fun queryFrameFlowsThroughFullPipelineAndBackAsQueryResult() {
        val queryFrame = gson.toJson(mapOf(
            "type" to "query",
            "requestId" to "e2e-q-1",
            "plugin" to "database",
            "method" to "listTables",
            "params" to mapOf("database" to E2EFixtureDb.NAME)
        ))

        val eventMessage = exchangeForQueryResultEvent(queryFrame)
        assertNotNull("queryResult event must reach the fake CLI", eventMessage)

        @Suppress("UNCHECKED_CAST")
        val event = gson.fromJson(eventMessage, Map::class.java) as Map<String, Any>
        assertEquals("event", event["type"])
        assertEquals("database", event["plugin"])

        @Suppress("UNCHECKED_CAST")
        val payload = event["payload"] as Map<String, Any>
        assertEquals("queryResult", payload["op"])
        assertEquals("e2e-q-1", payload["requestId"])
        assertEquals(true, payload["ok"])

        @Suppress("UNCHECKED_CAST")
        val result = payload["result"] as Map<String, Any>
        assertEquals(E2EFixtureDb.NAME, result["database"])
        assertNotNull(result["tables"])
    }

    /**
     * M2 wire proof: a NULL SQL cell must serialize as an explicit JSON null
     * (`"note":null`), not be dropped by Gson's default null-suppression. This
     * exercises the full transport serialization path (the scoped
     * `serializeNulls` gson in [WebSocketTransport]) end-to-end.
     */
    @Test
    fun queryResultSerializesNullCellsAsExplicitJsonNull() {
        val queryFrame = gson.toJson(mapOf(
            "type" to "query",
            "requestId" to "e2e-null",
            "plugin" to "database",
            "method" to "inspectTable",
            "params" to mapOf(
                "database" to E2EFixtureDb.NAME,
                "table" to E2EFixtureDb.TABLE
            )
        ))

        val eventMessage = exchangeForQueryResultEvent(queryFrame)
        assertNotNull("queryResult event must reach the fake CLI", eventMessage)

        // The raw on-wire JSON must carry the null cell as `"note":null`.
        // Gson's default (serializeNulls=false) would omit the key entirely —
        // this assertion proves the scoped serializeNulls path is in effect.
        assertTrue(
            "raw event JSON must contain \"note\":null for a NULL cell — got: $eventMessage",
            eventMessage!!.contains("\"note\":null")
        )
    }

    // ------------------------------------------------------------------
    // Helpers / fixture
    // ------------------------------------------------------------------

    /**
     * Boots the transport against the MockWebServer CLI, pushes [queryFrame]
     * on open, and returns the first `queryResult` event JSON received by the
     * fake CLI (or null on timeout).
     */
    private fun exchangeForQueryResultEvent(queryFrame: String): String? {
        val receivedEvents = Collections.synchronizedList(mutableListOf<String>())
        val connectLatch = CountDownLatch(1)

        wsServer.enqueue(
            MockResponse.Builder()
                .webSocketUpgrade(object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        connectLatch.countDown()
                        // Fake CLI pushes a query frame to the SDK.
                        webSocket.send(queryFrame)
                    }
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        receivedEvents.add(text)
                    }
                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        // Echo close so MockWebServer.close() does not time out.
                        webSocket.close(1000, null)
                    }
                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        wsClosedLatch.countDown()
                    }
                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        wsClosedLatch.countDown()
                    }
                })
                .build()
        )
        wsServer.start()

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val wsUrl = wsServer.url("/").toString().replace("http://", "ws://")
        val transport = WebSocketTransport(
            serverUrl = wsUrl,
            appPackage = context.packageName
        )

        Probe.install(
            Probe.Builder(context)
                .transport(transport)
                .plugin(DatabasePlugin(context))
                .build()
        )

        assertTrue(
            "WebSocket transport must connect within 5s",
            connectLatch.await(5, TimeUnit.SECONDS)
        )

        return waitForMessage(receivedEvents, timeoutMs = 5000) { msg ->
            @Suppress("UNCHECKED_CAST")
            val parsed = gson.fromJson(msg, Map::class.java) as Map<String, Any>
            parsed["type"] == "event" &&
                parsed["plugin"] == "database" &&
                (parsed["payload"] as Map<String, Any>)["op"] == "queryResult"
        }
    }

    private fun waitForMessage(
        messages: List<String>,
        timeoutMs: Long,
        predicate: (String) -> Boolean
    ): String? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            messages.find(predicate)?.let { return it }
            Thread.sleep(100)
        }
        return messages.find(predicate)
    }

    /**
     * Minimal inspectable fixture DB. Includes a nullable `note` column seeded
     * with a NULL value so the NULL→JSON-null wire contract (M2) is exercisable.
     */
    private class E2EFixtureDb(context: Context) :
        SQLiteOpenHelper(context, NAME, null, VERSION) {

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE $TABLE (id INTEGER PRIMARY KEY, label TEXT NOT NULL, note TEXT)"
            )
            db.insert(
                TABLE,
                null,
                ContentValues().apply {
                    put("label", "has-note")
                    put("note", "hello")
                }
            )
            // Row with a NULL note — exercises NULL→"note":null on the wire.
            db.insert(
                TABLE,
                null,
                ContentValues().apply {
                    put("label", "null-note")
                    putNull("note")
                }
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            db.execSQL("DROP TABLE IF EXISTS $TABLE")
            onCreate(db)
        }

        companion object {
            const val NAME = "probe_e2e.db"
            const val VERSION = 1
            const val TABLE = "e2e_items"
        }
    }
}
