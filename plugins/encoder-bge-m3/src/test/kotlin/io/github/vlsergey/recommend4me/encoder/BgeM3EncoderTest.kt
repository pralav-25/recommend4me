package io.github.vlsergey.recommend4me.encoder

import io.github.vlsergey.recommend4me.onnx.Onnx
import io.github.vlsergey.recommend4me.onnx.defaultModelsDir
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BgeM3EncoderTest {

    private val encoder = BgeM3Encoder(Onnx(defaultModelsDir()))

    private fun cos(a: FloatArray, b: FloatArray) = a.indices.sumOf { (a[it] * b[it]).toDouble() }

    @Test
    fun `a Russian query finds the English text about the same`() {
        assumeTrue(encoder.ready(), "bge-m3 files are not in the models folder")
        val (dragons, college) = encoder.encode(
            listOf(
                "An epic fantasy adventure: you ride dragons, fight knights and explore a magic kingdom.",
                "A romance at college: you date your classmates, attend lectures and go to parties.",
            ),
        ).map { it!! }
        val dragonsQuery = encoder.encode(listOf("фэнтези с драконами"), TextKind.QUERY).single()!!
        val collegeQuery = encoder.encode(listOf("студенческая жизнь в университете"), TextKind.QUERY).single()!!
        assertEquals(BgeM3Encoder.DIM, dragons.size)
        assertTrue(cos(dragonsQuery, dragons) > cos(dragonsQuery, college))
        assertTrue(cos(collegeQuery, college) > cos(collegeQuery, dragons))
    }

    @Test
    fun `a detective in Victorian London is not a Victor in London`() {
        assumeTrue(encoder.ready(), "bge-m3 files are not in the models folder")
        val (holmes, victor) = encoder.encode(
            listOf(
                "Шерлок Холмс расследует загадочное убийство в туманном Лондоне конца девятнадцатого века.",
                "Виктор переезжает в Лондон и открывает кофейню на окраине.",
            ),
        ).map { it!! }
        val q = encoder.encode(listOf("детектив в викторианском Лондоне"), TextKind.QUERY).single()!!
        assertTrue(cos(q, holmes) > cos(q, victor), "${cos(q, holmes)} vs ${cos(q, victor)}")
    }

    @Test
    fun `a long text gives a vector for every window, each where it lies, and empty ones none`() {
        assumeTrue(encoder.ready(), "bge-m3 files are not in the models folder")
        val long = (1..400).joinToString(" ") { "Глава $it: герой идёт дальше." }
        val (windows, empty) = encoder.encodeWindows(listOf(long, ""))
        assertTrue(windows.size > 1)
        windows.forEach { assertEquals(1.0, cos(it, it), 1e-3) }
        assertTrue(empty.isEmpty())
        val spans = encoder.windowSpans(long)
        assertEquals(windows.size, spans.size)
        assertEquals(0, spans.first().first)
        assertEquals(long.length, spans.last().last + 1)
        assertNull(encoder.encode(listOf("   ")).single())
    }
}
