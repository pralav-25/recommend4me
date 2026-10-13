package io.github.vlsergey.recommend4me.access

import io.github.vlsergey.recommend4me.i18n.Label
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.net.InetAddress
import java.net.URI

private val log = LoggerFactory.getLogger(Guard::class.java)

private val REFUSED = Label(
    "Access only from this computer and from the allowed networks",
    "Доступ только с этого компьютера и из разрешённых сетей",
    "Pristup samo sa ovog računara i iz dozvoljenih mreža",
)

/**
 * THE GUARD OF ACCESS, three questions before anything else is done.
 *
 * WHO IS ASKING — by the address the request came from: this machine, or a network the personal
 * configuration opens the application to (`recommend4me.access.networks`). No password; the
 * listeners take no other address anyway, this is the second lock.
 *
 * WHETHER THE NAME IS OURS — the Host header. The browser on this machine walks the internet, and a
 * page there can point its own domain at our address (DNS rebinding) and read our answers from that
 * browser: the connection comes from a trusted address, and only the name gives it away. Ours are
 * the loopback names, any address written out as an address (rebinding needs a NAME), this
 * machine's own name and the names of `recommend4me.access.hosts` (".example" for every name
 * ending so).
 *
 * WHETHER A WRITE COMES FROM OUR PAGE — a browser sends Origin with every POST, PUT and DELETE, and
 * a foreign tab can send one without a preflight. A write whose Origin is not one of our names is
 * refused — unless it is a browser extension's (`moz-extension:`, `safari-web-extension:` of the userscripts of Safari), which no page can pretend to be:
 * the browser writes the header, and a page carries http(s). A request without Origin is not a
 * browser's, and passes.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class Guard(settings: AccessSettings) : OncePerRequestFilter() {

    private val addresses = Addresses(settings.networks.map(::Network))

    private val names: Set<String> = buildSet {
        addAll(LOCAL_NAMES)
        runCatching { InetAddress.getLocalHost().hostName.lowercase() }.getOrNull()?.let(::add)
        settings.hosts.map { hostname(it) }.filter { it.isNotEmpty() }.forEach(::add)
    }

    fun ours(host: String): Boolean {
        val h = hostname(host)
        return h in names || Addresses.parse(h) != null || names.any { it.startsWith(".") && h.endsWith(it) }
    }

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val from = request.remoteAddr
        val origin = request.getHeader("Origin")
        val refusal = when {
            !addresses.allowed(from) -> "the address $from is neither this machine nor a network it is opened to"
            !ours(request.serverName) -> "the name ${hostname(request.serverName)} is not ours (recommend4me.access.hosts), from $from"
            request.method !in SAFE && origin != null && !fromExtension(origin) && !ours(originHost(origin)) ->
                "a write from the page $origin, from $from"
            else -> null
        }
        if (refusal == null) {
            chain.doFilter(request, response)
            return
        }
        log.info("refused {} {}: {}", request.method, request.requestURI, refusal)
        response.status = HttpServletResponse.SC_FORBIDDEN
        response.contentType = "text/plain;charset=utf-8"
        // Before the dispatcher: the language read off the request itself
        response.writer.write(REFUSED.of(request.locale.language))
    }

    private fun fromExtension(origin: String): Boolean = EXTENSION_SCHEMES.any { origin.startsWith("$it://") }

    private fun originHost(origin: String): String = runCatching { URI(origin).host }.getOrNull().orEmpty()

    companion object {
        private val LOCAL_NAMES = setOf("localhost", "127.0.0.1", "::1", "[::1]")
        private val SAFE = setOf("GET", "HEAD", "OPTIONS")
        private val EXTENSION_SCHEMES = listOf("moz-extension", "chrome-extension", "safari-web-extension")

        /** The host without the port, lower case; an address of the sixth family keeps its brackets. */
        fun hostname(value: String?): String {
            val v = value.orEmpty().trim().lowercase()
            if (v.startsWith("[")) return v.substringBefore("]") + "]"
            // An address of the sixth family without brackets (Tomcat reports the Host so) has no port to cut
            return if (v.count { it == ':' } > 1) v else v.substringBefore(":")
        }
    }
}
