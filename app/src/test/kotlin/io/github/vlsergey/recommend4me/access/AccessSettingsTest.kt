package io.github.vlsergey.recommend4me.access

import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.io.ByteArrayResource
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import tools.jackson.databind.json.JsonMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AccessSettingsTest {

    private fun environment(yaml: String) = StandardEnvironment().apply {
        YamlPropertySourceLoader().load("personal", ByteArrayResource(yaml.toByteArray())).forEach { propertySources.addFirst(it) }
    }

    @Test
    fun `the lists of the personal configuration are read as YAML writes them`() {
        val settings = AccessSettings(
            environment(
                """
                recommend4me:
                  access:
                    networks: [100.64.0.0/10, "fd7a:115c:a1b2::/48"]
                    hosts: [.ts.net]
                """.trimIndent(),
            ),
        )
        assertEquals(listOf("100.64.0.0/10", "fd7a:115c:a1b2::/48"), settings.networks)
        assertEquals(listOf(".ts.net"), settings.hosts)
        assertTrue(Guard(settings).ours("my-pc.tail1234.ts.net"))
    }

    @Test
    fun `a request refused is answered as a problem by the key of its words`() {
        val guard = Guard(AccessSettings(environment("recommend4me:\n  access:\n    networks: []\n")))
        val request = MockHttpServletRequest("GET", "/api/types").apply { remoteAddr = "8.8.8.8" }
        val response = MockHttpServletResponse()
        guard.doFilter(request, response, MockFilterChain())
        assertEquals(403, response.status)
        assertEquals("application/problem+json", response.contentType?.substringBefore(';'))
        val problem = JsonMapper.builder().build().readTree(response.contentAsString)
        assertEquals("standard:error.accessRefused", problem.get("code").asString())
        assertEquals(403, problem.get("status").asInt())
        assertTrue(problem.get("params").isEmpty)
    }

    @Test
    fun `without a personal configuration the application is open to nothing beyond itself`() {
        val settings = AccessSettings(environment("recommend4me:\n  access:\n    networks: []\n"))
        assertTrue(settings.networks.isEmpty() && settings.hosts.isEmpty())
    }
}
