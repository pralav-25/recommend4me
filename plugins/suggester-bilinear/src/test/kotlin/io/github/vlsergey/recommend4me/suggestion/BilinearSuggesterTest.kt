package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.matrix.Matrix
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BilinearSuggesterTest {

    private val d = 8

    /** A task: every item has a value when its text points the value's way, the value's name pointing so too. */
    private fun task(n: Int, wrong: Int = -1, random: Random = Random(7)): SuggestionTask {
        val directions = List(4) { FloatArray(d) { random.nextGaussian().toFloat() }.normalized() }
        val texts = Matrix(n, d)
        val assigned = ArrayList<IntArray>()
        repeat(n) { i ->
            val v = FloatArray(d) { random.nextGaussian().toFloat() }.normalized()
            texts.held.put(v, 0, texts.row(i), d)
            val has = directions.indices.filter { k -> directions[k].indices.sumOf { (directions[k][it] * v[it]).toDouble() } > 0.3 }
            // One item said to have what its text does not point to: the site's word alone
            assigned += if (i == wrong) intArrayOf(directions.indices.first { it !in has }) else has.toIntArray()
        }
        val names = Matrix(directions.size, d)
        directions.forEachIndexed { k, v -> names.held.put(v, 0, names.row(k), d) }
        return SuggestionTask(
            itemIds = List(n) { "item$it" },
            inputs = listOf(InputBlock("text:annotation", texts, BooleanArray(n) { true })),
            values = names,
            valueKeys = directions.indices.map { "v$it" },
            assigned = assigned,
            confirmed = List(n) { IntArray(0) },
            rejected = List(n) { IntArray(0) },
            context = List(n) { IntArray(0) },
            contextKeys = emptyList(),
        )
    }

    private fun FloatArray.normalized(): FloatArray {
        val l = kotlin.math.sqrt(sumOf { (it * it).toDouble() }).toFloat()
        return FloatArray(size) { this[it] / l }
    }

    @Test
    fun `a value goes with the texts that point its way, every item judged by a model that did not learn from it`() {
        val task = task(300, wrong = 5)
        val fitted = assertNotNull(BilinearSuggester().fit(task))
        val chances = fitted.on(task).of(IntArray(task.itemCount) { it })
        var right = 0
        var all = 0
        for (i in 0 until task.itemCount) {
            if (i == 5) continue
            for (v in 0 until task.valueCount) {
                val has = v in task.assigned[i]
                if (has == chances[i][v] > 0.5f) right++
                all++
            }
        }
        assertTrue(right > 0.85 * all, "$right of $all right")
        // The value the site alone gave item 5 comes out unlikely: the item does not vouch for itself
        assertTrue(chances[5][task.assigned[5].single()] < 0.5f, "${chances[5].toList()}")
    }

    @Test
    fun `a work the site gave no examples is judged by its text, the examples of the others taught it`() {
        val random = Random(3)
        val space = FloatArray(d) { if (it < d / 2) 1f else 0f }.normalized()
        val dragons = FloatArray(d) { if (it >= d / 2) 1f else 0f }.normalized()
        val n = 61
        val texts = Matrix(n, d)
        val assigned = ArrayList<IntArray>()
        val examples = ArrayList<IntArray>()
        repeat(n) { i ->
            val kind = if (i == n - 1) 0 else i % 2
            val base = if (kind == 0) space else dragons
            val v = FloatArray(d) { base[it] + 0.3f * random.nextGaussian().toFloat() }.normalized()
            texts.held.put(v, 0, texts.row(i), d)
            val own = if (kind == 0) intArrayOf(0, 1) else intArrayOf(2, 3)
            when {
                i == n - 1 -> { assigned += IntArray(0); examples += IntArray(0) }
                // Some works the site left untagged, tagged by the user
                i % 5 == 0 -> { assigned += own; examples += IntArray(0) }
                else -> { assigned += own; examples += own }
            }
        }
        val names = Matrix(4, d)
        val task = SuggestionTask(
            itemIds = List(n) { "w$it" },
            inputs = listOf(InputBlock("text:annotation", texts, BooleanArray(n) { true })),
            values = names,
            valueKeys = listOf("space", "ships", "dragons", "magic"),
            assigned = assigned,
            confirmed = List(n) { IntArray(0) },
            rejected = List(n) { IntArray(0) },
            context = List(n) { IntArray(0) },
            contextKeys = emptyList(),
            // A value the site lacks says little: the user added many and rejected none
            lackWeight = 1.0 / 14,
            examples = examples,
        )
        val fitted = assertNotNull(BilinearSuggester().fit(task))
        val lost = fitted.on(task).of(intArrayOf(n - 1)).single()
        assertTrue(lost[0] > 0.5f && lost[1] > 0.5f && lost[2] < 0.5f && lost[3] < 0.5f, "a space work: ${lost.toList()}")
    }

    /** The site's catalogue of the application's test: two kinds, tags and pairings as the site writes them, a few works tagged by the user. */
    @Test
    fun `a work with nothing but its text, among works the site tagged, is judged by its text`() {
        val random = Random(11)
        val words = 64
        fun text(kind: Int) = FloatArray(words) { q -> if ((q < words / 2) == (kind == 0)) 1f + 0.3f * random.nextGaussian().toFloat() else 0.1f * random.nextFloat() }.normalized()
        val contextKeys = listOf("tag\u0001space", "tag\u0001ships", "tag\u0001dragons", "tag\u0001magic", "pairing\u0001pc", "pairing\u0001kp")
        val rows = ArrayList<FloatArray>()
        val assigned = ArrayList<IntArray>()
        val examples = ArrayList<IntArray>()
        val context = ArrayList<IntArray>()
        repeat(20) { k ->
            listOf(0, 1).forEach { kind ->
                rows += text(kind)
                val own = if (kind == 0) intArrayOf(3, 2) else intArrayOf(0, 1)
                val site = when { k % 5 == 0 -> IntArray(0); k % 3 == 0 -> intArrayOf(own[0]); else -> own }
                examples += site
                val user = if (k % 5 == 0 && k > 0) own else IntArray(0)
                assigned += (site + user).distinct().toIntArray()
                val tagContext = site.map { v -> mapOf(3 to 0, 2 to 1, 0 to 2, 1 to 3).getValue(v) }
                val pairing = if (k % 4 == 0) emptyList() else listOf(if (kind == 0) 4 else 5)
                context += (tagContext + pairing).toIntArray()
            }
        }
        // The work with its text alone
        rows += text(0); assigned += IntArray(0); examples += IntArray(0); context += IntArray(0)
        val n = rows.size
        val texts = Matrix(n, words)
        rows.forEachIndexed { i, v -> texts.held.put(v, 0, texts.row(i), words) }
        // The values' names, encoded in the space of the texts: each one word of its kind
        val names = Matrix(4, words)
        listOf(1, 0, 0, 1).forEachIndexed { v, kind ->
            val name = FloatArray(words).also { it[if (kind == 0) v else words / 2 + v] = 1f }
            names.held.put(name, 0, names.row(v), words)
        }
        // The site's line of pairings, as a text, of the works that have one
        val lines = Matrix(n, words)
        val hasLine = BooleanArray(n) { i -> context[i].any { it >= 4 } }
        (0 until n).filter { hasLine[it] }.forEach { i ->
            val v = FloatArray(words).also { it[if (4 in context[i]) 3 else words - 3] = 1f }
            lines.held.put(v, 0, lines.row(i), words)
        }
        val task = SuggestionTask(
            itemIds = List(n) { "w$it" },
            inputs = listOf(InputBlock("text:annotation", texts, BooleanArray(n) { true }), InputBlock("text:pairings", lines, hasLine)),
            values = names,
            valueKeys = listOf("dragons", "magic", "ships", "space"),
            assigned = assigned,
            confirmed = List(n) { IntArray(0) },
            rejected = List(n) { IntArray(0) },
            context = context,
            contextKeys = contextKeys,
            lackWeight = 1.0 / 14,
            examples = examples,
        )
        val fitted = assertNotNull(BilinearSuggester().fit(task))
        val lost = fitted.on(task).of(intArrayOf(n - 1)).single()
        assertTrue(lost[3] > 0.5f && lost[2] > 0.5f && lost[0] < 0.5f && lost[1] < 0.5f, "a space work by its text: ${lost.toList()}")
    }

    @Test
    fun `kept and read again, the model judges the same`() {
        val task = task(120)
        val suggester = BilinearSuggester()
        val fitted = assertNotNull(suggester.fit(task))
        val again = assertNotNull(suggester.unpack(fitted.pack(), task))
        val rows = IntArray(task.itemCount) { it }
        val a = fitted.on(task).of(rows)
        val b = again.on(task).of(rows)
        rows.forEach { i -> a[i].indices.forEach { v -> assertEquals(a[i][v], b[i][v], 1e-6f) } }
    }

    @Test
    fun `gone on from the last state with more items, the model judges as one fitted from scratch`() {
        val small = task(240)
        val suggester = BilinearSuggester()
        val last = assertNotNull(suggester.fit(small))
        // Sixty items more, the same values: the refit starts where the last one stopped
        val task = task(300)
        val again = assertNotNull(suggester.fit(task, last))
        val scratch = assertNotNull(suggester.fit(task))
        val rows = IntArray(task.itemCount) { it }
        val a = again.on(task).of(rows)
        val b = scratch.on(task).of(rows)
        // The two agree on which side of a half every pair is
        var agree = 0
        var all = 0
        rows.forEach { i -> a[i].indices.forEach { v -> if ((a[i][v] > 0.5f) == (b[i][v] > 0.5f)) agree++; all++ } }
        assertTrue(agree >= all * 0.95, "$agree of $all pairs agree")
    }

    @Test
    fun `nothing to learn from, nothing is fitted`() {
        val task = task(20)
        val empty = SuggestionTask(
            task.itemIds, task.inputs, task.values, task.valueKeys, List(20) { IntArray(0) }, task.confirmed, task.rejected, task.context, task.contextKeys,
        )
        assertEquals(null, BilinearSuggester().fit(empty))
    }
}
