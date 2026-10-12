package io.github.vlsergey.recommend4me.encoder

import io.github.vlsergey.recommend4me.onnx.Onnx
import io.github.vlsergey.recommend4me.onnx.defaultModelsDir
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class E5EncoderTest {

    private val textVec = E5Encoder(Onnx(defaultModelsDir()))

    private fun cos(a: FloatArray, b: FloatArray) = a.indices.sumOf { (a[it] * b[it]).toDouble() }

    @Test
    fun `a Russian query finds the English text about the same`() {
        assumeTrue(textVec.ready(), "multilingual-e5 files are not in the models folder")
        val (dragons, college) = textVec.encode(
            listOf(
                "An epic fantasy adventure: you ride dragons, fight knights and explore a magic kingdom.",
                "A romance at college: you date your classmates, attend lectures and go to parties.",
            )
        ).map { it!! }
        val dragonsQuery = textVec.encode(listOf("фэнтези с драконами"), TextKind.QUERY).single()!!
        val collegeQuery = textVec.encode(listOf("студенческая жизнь в университете"), TextKind.QUERY).single()!!

        assertTrue(cos(dragonsQuery, dragons) > cos(dragonsQuery, college))
        assertTrue(cos(collegeQuery, college) > cos(collegeQuery, dragons))
    }

    @Test
    fun `long texts are read in windows and empty ones have no vector`() {
        assumeTrue(textVec.ready(), "multilingual-e5 files are not in the models folder")
        val long = "A sandbox game about a photographer in a big city. ".repeat(80)
        val v = textVec.encode(listOf(long, "", "   "))
        assertEquals(E5Encoder.DIM, v[0]!!.size)
        assertEquals(1.0, cos(v[0]!!, v[0]!!), 1e-4)
        assertTrue(textVec.pieces(long).size > 256, "longer than one window")
        assertNull(v[1])
        assertNull(v[2])
    }

    @Test
    fun `a long text gives a vector for every window`() {
        assumeTrue(textVec.ready(), "multilingual-e5 files are not in the models folder")
        val long = "A sandbox game about a photographer in a big city. ".repeat(80)
        val (windows, empty) = textVec.encodeWindows(listOf(long, ""))
        assertTrue(windows.size > 1)
        windows.forEach { assertEquals(1.0, cos(it, it), 1e-4) }
        assertTrue(empty.isEmpty())
    }

    @Test
    fun `every window lies where it is in the text, one after another`() {
        assumeTrue(textVec.ready(), "multilingual-e5 files are not in the models folder")
        val long = (1..120).joinToString(" ") { "Глава $it: герой идёт дальше." }
        val spans = textVec.windowSpans(long)
        assertEquals(textVec.encodeWindows(listOf(long)).single().size, spans.size)
        assertEquals(0, spans.first().first)
        assertEquals(long.length, spans.last().last + 1)
        spans.zipWithNext().forEach { (a, b) -> assertTrue(a.last < b.first, "$a before $b") }
        assertTrue(textVec.windowSpans("   ").isEmpty())
    }
}
