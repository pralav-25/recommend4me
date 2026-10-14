package io.github.vlsergey.recommend4me.capture

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * THE FIELDS THE EXTENSION PUTS INTO A SITE'S PAGE ARE READ WHATEVER THE SITE'S SKIN: a site's rules
 * for fields — a dark skin's light text over our light field, Safari's fill of the text — win over
 * ours unless ours are !important. Every kind of field page.js makes (an input, a text area, a
 * select) has a rule of page.css that sets its background, its text, the fill of its text and its
 * caret so, and its hint too.
 */
class ExtensionFieldsTest {

    private val folder = Path.of("../browser-extension/extension").toAbsolutePath().normalize()

    /** The rules of the style sheet: the selectors, then the declarations; the comments left out. */
    private fun rules(css: String): List<Pair<String, String>> =
        Regex("""([^{}]+)\{([^{}]*)}""").findAll(css.replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), ""))
            .map { it.groupValues[1].trim() to it.groupValues[2] }.toList()

    private fun important(declarations: String, property: String) =
        Regex("""(^|[;\s])${Regex.escape(property)}\s*:[^;]*!important""").containsMatchIn(declarations)

    @Test
    fun `every kind of field the extension makes is in its own colours whatever the site says`() {
        val script = Files.readString(folder.resolve("page.js"))
        val css = rules(Files.readString(folder.resolve("page.css")))
        val kinds = Regex("""el\("(input|textarea|select)"""").findAll(script).map { it.groupValues[1] }.toSet()
        assertTrue(kinds.isNotEmpty(), "page.js makes no field: the test reads it wrong")
        kinds.forEach { kind ->
            fun ruled(property: String, pseudo: String = "") = css.any { (selector, declarations) ->
                selector.split(',').map { it.trim() }.any { s ->
                    // Of the field itself, not of its hint, unless the hint is asked for
                    (pseudo.isNotEmpty() || "::" !in s) && Regex("""(^|[\s>])$kind([.\[:][^\s]*)?$pseudo$""").containsMatchIn(s)
                } && important(declarations, property)
            }
            listOf("background", "color", "-webkit-text-fill-color", "caret-color").forEach { property ->
                assertTrue(ruled(property), "a $kind of the extension has no $property of its own marked !important in page.css")
            }
            if (kind != "select") {
                listOf("color", "-webkit-text-fill-color").forEach { property ->
                    assertTrue(ruled(property, "::placeholder"), "the hint of a $kind of the extension has no $property marked !important in page.css")
                }
            }
        }
    }
}
