package io.github.vlsergey.recommend4me.capture

import io.github.vlsergey.recommend4me.i18n.Label
import io.github.vlsergey.recommend4me.i18n.text
import io.github.vlsergey.recommend4me.source.Stores
import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

/** What the script says it does, in the language of the request it is installed by; %s — the application's address. */
private val DESCRIPTION = Label(
    "Sends the open pages of books and games to the recommend4me application (%s) and shows the prediction, the grade, the tags and the hints on them",
    "Отправляет открытые страницы книг и игр в приложение recommend4me (%s) и показывает на них прогноз, оценку, теги и подсказки",
    "Šalje otvorene stranice knjiga i igara aplikaciji recommend4me (%s) i na njima prikazuje predviđanje, ocenu, tagove i savete",
)

/**
 * THE USERSCRIPT FOR SAFARI ON THE IPAD — the browser tracking and the site as the application's
 * interface where an extension of the browser cannot be installed without the App Store: a file
 * the Userscripts app runs on the pages of the sources. It is put together of the extension's own
 * scripts (watch.js, page.js, page.css and userscript.js in place of the background script), so
 * the two never part.
 *
 * The script calls the application at the address it was asked for at: the iPad's address of it
 * — through `tailscale serve`, the name and the https of the proxy (X-Forwarded-Host and -Proto).
 * Not a part of the JSON API: a script, served as the pages of the interface are.
 */
@RestController
class UserScriptController(
    private val stores: Stores,
    private val json: JsonMapper,
    @Value("\${recommend4me.extension-dir:}") private val extensionDir: String,
) {

    @GetMapping("/userscript/recommend4me.user.js", produces = ["application/javascript;charset=utf-8"])
    fun userScript(request: HttpServletRequest): ResponseEntity<String> {
        val folder = extensionDir.takeIf { it.isNotBlank() }?.let { Path.of(it).toAbsolutePath().normalize() }
            ?.takeIf { Files.isRegularFile(it.resolve("page.js")) }
            ?: return ResponseEntity.status(HttpStatus.NOT_FOUND).contentType(MediaType.TEXT_PLAIN).body(Label("There is no extension folder", "Нет папки расширения", "Nema fascikle proširenja").text())
        // Every page of the hosts of the sources: the script asks the application which it wants
        val hosts = stores.sources.mapNotNull { s -> runCatching { URI(s.source.homepage).host?.removePrefix("www.") }.getOrNull() }.distinct()
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(assemble(folder, serverOf(request), hosts, json))
    }

    /** The address the script was asked for at: the proxy's name and scheme when there is one. */
    private fun serverOf(request: HttpServletRequest): String {
        val proto = request.getHeader("X-Forwarded-Proto")?.substringBefore(',')?.trim()?.takeIf { it.isNotEmpty() } ?: request.scheme
        val host = request.getHeader("X-Forwarded-Host")?.substringBefore(',')?.trim()?.takeIf { it.isNotEmpty() } ?: request.getHeader("Host")
            ?: "${request.serverName}:${request.serverPort}"
        return "$proto://$host"
    }

    companion object {
        /** The script of the extension's [folder] calling the application at [server], run on the pages of [hosts]. */
        fun assemble(folder: Path, server: String, hosts: List<String>, json: JsonMapper): String {
            fun read(name: String) = Files.readString(folder.resolve(name))
            val version = runCatching { json.readTree(folder.resolve("manifest.json").toFile()).get("version")?.asString() }.getOrNull() ?: "0"
            return buildString {
                appendLine("// ==UserScript==")
                appendLine("// @name         recommend4me")
                appendLine("// @namespace    https://github.com/vlsergey/recommend4me")
                appendLine("// @version      $version")
                appendLine("// @description  " + DESCRIPTION.text().format(server))
                hosts.forEach { host ->
                    appendLine("// @match        *://$host/*")
                    appendLine("// @match        *://*.$host/*")
                }
                appendLine("// @grant        GM.xmlHttpRequest")
                appendLine("// @run-at       document-end")
                appendLine("// @noframes")
                appendLine("// @downloadURL  $server/userscript/recommend4me.user.js")
                appendLine("// @updateURL    $server/userscript/recommend4me.user.js")
                appendLine("// ==/UserScript==")
                appendLine("(() => {")
                appendLine("const R4M_SERVER = ${json.writeValueAsString(server)};")
                appendLine("const R4M_CSS = ${json.writeValueAsString(read("page.css"))};")
                appendLine("function runPage() {")
                appendLine(read("page.js"))
                appendLine("}")
                appendLine(read("watch.js"))
                appendLine(read("userscript.js"))
                appendLine("})();")
            }
        }
    }
}
