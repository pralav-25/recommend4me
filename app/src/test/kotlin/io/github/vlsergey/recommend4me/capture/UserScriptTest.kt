package io.github.vlsergey.recommend4me.capture

import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

class UserScriptTest {

    /** The extension of this repository, beside the module. */
    private val folder = Path.of("../browser-extension/extension").toAbsolutePath().normalize()

    @Test
    fun `the userscript is the extension's scripts with the address of the application`() {
        val script = UserScriptController.assemble(folder, "https://pc.example.ts.net", listOf("author.today", "ficbook.net"), JsonMapper.builder().build())
        assertTrue(script.startsWith("// ==UserScript=="))
        assertTrue("// @match        *://author.today/*" in script && "// @match        *://*.ficbook.net/*" in script, script.lines().take(20).joinToString("\n"))
        assertTrue("// @downloadURL  https://pc.example.ts.net/userscript/recommend4me.user.js" in script)
        assertTrue("const R4M_SERVER = \"https://pc.example.ts.net\";" in script)
        // page.js runs on demand, watch.js and the stand-in of the background script beside it
        assertTrue("function runPage() {" in script && "function watchPage()" in script && "GM.xmlHttpRequest" in script)
        assertTrue(Files.readString(folder.resolve("page.js")).trim() in script)
        // The extension's words before page.js, which calls them
        val words = Files.readString(folder.resolve("i18n.js")).trim()
        assertTrue(words in script && script.indexOf(words) < script.indexOf("function runPage() {"))
    }
}
