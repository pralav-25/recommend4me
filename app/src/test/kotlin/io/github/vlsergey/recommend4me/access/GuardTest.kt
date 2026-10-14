package io.github.vlsergey.recommend4me.access

import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.io.ByteArrayResource
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Who may ask the application: an address of this machine or of a network it is opened to, by a
 * name of ours — never a foreign name pointed at our address — and a write only from our page or
 * an extension.
 */
class GuardTest {

    private val guard = Guard(
        AccessSettings(
            StandardEnvironment().apply {
                val yaml = "recommend4me:\n  access:\n    networks: [100.64.0.0/10]\n    hosts: [.ts.net, my-box]\n"
                YamlPropertySourceLoader().load("personal", ByteArrayResource(yaml.toByteArray())).forEach { propertySources.addFirst(it) }
            },
        ),
    )

    /** The status the guard answers [method] [host] from [from] with, the Origin [origin] sent; 200 when it lets it pass. */
    private fun status(method: String = "GET", host: String = "localhost", from: String = "127.0.0.1", origin: String? = null): Int {
        val request = MockHttpServletRequest(method, "/api/types").apply {
            remoteAddr = from
            serverName = host
            origin?.let { addHeader("Origin", it) }
        }
        val response = MockHttpServletResponse()
        guard.doFilter(request, response, MockFilterChain())
        return response.status
    }

    @Test
    fun `the addresses — this machine and the networks opened to, nothing else`() {
        assertEquals(200, status(from = "127.0.0.1"))
        assertEquals(200, status(from = "0:0:0:0:0:0:0:1"))
        assertEquals(200, status(from = "100.100.1.2", host = "my-box"))
        assertEquals(403, status(from = "192.168.1.5"))
    }

    @Test
    fun `the names — loopback, any address written out, the hosts of the configuration — never a foreign name at our address`() {
        assertEquals(200, status(host = "localhost"))
        assertEquals(200, status(host = "127.0.0.1"))
        // An address needs no name: rebinding needs a NAME
        assertEquals(200, status(host = "100.100.1.2"))
        assertEquals(200, status(host = "[::1]"))
        assertEquals(200, status(host = "my-box"))
        // ".ts.net" is every name ending so
        assertEquals(200, status(host = "pc.tail1234.ts.net"))
        assertEquals(200, status(host = "PC.Tail1234.TS.NET"))
        // A page's own domain pointed at our address (DNS rebinding): refused, though the connection is local
        assertEquals(403, status(host = "evil.example"))
        assertEquals(403, status(host = "ts.net.evil.example"))
    }

    @Test
    fun `a write only from our page or an extension, a read from anywhere`() {
        assertEquals(200, status("POST", origin = "http://localhost:8095"))
        assertEquals(200, status("PUT", origin = "moz-extension://4a2b1c"))
        assertEquals(200, status("DELETE", origin = "chrome-extension://abc"))
        assertEquals(200, status("PUT", origin = "safari-web-extension://abc"))
        // Not a browser's: no Origin
        assertEquals(200, status("POST"))
        // A foreign tab writing to us
        assertEquals(403, status("POST", origin = "https://evil.example"))
        assertEquals(403, status("DELETE", origin = "http://evil.example:8095"))
        // A read carries no harm: its answer is the browser's to withhold
        assertEquals(200, status("GET", origin = "https://evil.example"))
    }

    @Test
    fun `the host is compared without its port, in lower case, an address of the sixth family with its brackets`() {
        assertEquals("localhost", Guard.hostname("LocalHost:8095"))
        assertEquals("[::1]", Guard.hostname("[::1]:8095"))
        assertEquals("fd7a:115c::1", Guard.hostname("fd7a:115c::1"))
        assertEquals("", Guard.hostname(null))
    }
}
