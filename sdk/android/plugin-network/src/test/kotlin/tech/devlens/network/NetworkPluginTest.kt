package tech.devlens.network

import tech.devlens.ProbeHost
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkPluginTest {

    private fun makeTransaction(id: String = "id-${System.nanoTime()}"): HttpTransaction =
        HttpTransaction(
            id = id,
            timestamp = System.currentTimeMillis(),
            method = "GET",
            url = "https://example.com/$id",
            requestHeaders = emptyMap(),
            requestBody = null,
            requestSizeBytes = 0L,
            responseCode = 200,
            responseMessage = "OK",
            responseHeaders = emptyMap(),
            responseBody = "body",
            responseSizeBytes = 4L,
            durationMs = 50L,
            error = null
        )

    @Test
    fun `record adds transaction to buffer`() {
        val plugin = NetworkPlugin()

        plugin.record(makeTransaction("t1"))

        val dump = plugin.dump()
        assertEquals(1, dump.size)
        assertEquals("t1", dump[0].id)
    }

    @Test
    fun `record calls host send with plugin id network`() {
        val host = mockk<ProbeHost>(relaxed = true)
        val plugin = NetworkPlugin()
        plugin.onAttach(host)

        plugin.record(makeTransaction())

        val pluginIdSlot = slot<String>()
        verify { host.send(capture(pluginIdSlot), any()) }
        assertEquals("network", pluginIdSlot.captured)
    }

    @Test
    fun `record sends transaction payload to host`() {
        val host = mockk<ProbeHost>(relaxed = true)
        val plugin = NetworkPlugin()
        plugin.onAttach(host)

        val tx = makeTransaction("payload-test")
        plugin.record(tx)

        val payloadSlot = slot<Map<String, Any?>>()
        verify { host.send(any(), capture(payloadSlot)) }
        assertEquals("payload-test", payloadSlot.captured["id"])
        assertEquals("GET", payloadSlot.captured["method"])
    }

    @Test
    fun `record without attached host does not crash and still buffers`() {
        val plugin = NetworkPlugin()
        // host is null — no onAttach called

        plugin.record(makeTransaction("safe"))

        assertEquals(1, plugin.dump().size)
        assertEquals("safe", plugin.dump()[0].id)
    }

    @Test
    fun `record buffers transaction even when detached after attach`() {
        val host = mockk<ProbeHost>(relaxed = true)
        val plugin = NetworkPlugin()
        plugin.onAttach(host)
        plugin.onDetach()

        plugin.record(makeTransaction("detached"))

        assertEquals(1, plugin.dump().size)
        assertEquals("detached", plugin.dump()[0].id)
    }

    @Test
    fun `dump last=3 returns last 3 transactions in order`() {
        val plugin = NetworkPlugin()
        repeat(5) { i -> plugin.record(makeTransaction("t$i")) }

        val dump = plugin.dump(last = 3)

        assertEquals(3, dump.size)
        assertEquals("t2", dump[0].id)
        assertEquals("t3", dump[1].id)
        assertEquals("t4", dump[2].id)
    }

    @Test
    fun `dump last=100 when buffer has 5 returns all 5`() {
        val plugin = NetworkPlugin()
        repeat(5) { i -> plugin.record(makeTransaction("t$i")) }

        val dump = plugin.dump(last = 100)

        assertEquals(5, dump.size)
    }

    @Test
    fun `dump default returns up to 100 most recent`() {
        val plugin = NetworkPlugin()
        repeat(150) { i -> plugin.record(makeTransaction("t$i")) }

        val dump = plugin.dump()

        assertEquals(100, dump.size)
        assertEquals("t50", dump[0].id)
        assertEquals("t149", dump[99].id)
    }

    @Test
    fun `clearBuffer empties the buffer`() {
        val plugin = NetworkPlugin()
        repeat(3) { plugin.record(makeTransaction()) }

        plugin.clearBuffer()

        assertTrue(plugin.dump().isEmpty())
    }

    @Test
    fun `ring buffer evicts oldest transaction when bufferSize exceeded`() {
        val plugin = NetworkPlugin(bufferSize = 3)
        repeat(4) { i -> plugin.record(makeTransaction("t$i")) }

        val dump = plugin.dump(last = Int.MAX_VALUE)

        assertEquals(3, dump.size)
        assertEquals("t1", dump[0].id)
        assertEquals("t2", dump[1].id)
        assertEquals("t3", dump[2].id)
    }

    @Test
    fun `ring buffer with bufferSize=1 keeps only the latest transaction`() {
        val plugin = NetworkPlugin(bufferSize = 1)
        plugin.record(makeTransaction("old"))
        plugin.record(makeTransaction("new"))

        val dump = plugin.dump(last = Int.MAX_VALUE)

        assertEquals(1, dump.size)
        assertEquals("new", dump[0].id)
    }

    @Test
    fun `interceptor returns non-null Interceptor`() {
        val plugin = NetworkPlugin()
        val interceptor = plugin.interceptor()
        assertNotNull(interceptor)
    }

    @Test
    fun `plugin id is network`() {
        val plugin = NetworkPlugin()
        assertEquals("network", plugin.id)
    }
}
