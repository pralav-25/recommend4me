package io.github.vlsergey.recommend4me.matrix

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln1p
import kotlin.math.max
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

class SimdTest {

    @Test
    fun `a row of logistic losses agrees with the plain loop`() {
        assertTrue(Simd.on, "the Vector API module must be loaded in tests")
        val random = Random(2)
        // Not a whole number of lanes: the tail goes the plain way
        val n = 1003
        val row = FloatArray(n) { (random.nextFloat() - 0.5f) * 40 }
        val shift = FloatArray(n) { random.nextFloat() - 0.5f }
        val scale = 1f / 7
        var loss = 0.0
        var slope = 0.0
        val slopes = DoubleArray(n) { j ->
            val z = (row[j] + shift[j]).toDouble()
            val e = exp(-abs(z))
            loss += max(z, 0.0) + ln1p(e)
            ((if (z >= 0) 1 / (1 + e) else e / (1 + e)) * scale).also { slope += it }
        }
        val sums = DoubleArray(2)
        Simd.logisticRow(row, shift, n, scale, sums)
        assertTrue(abs(sums[0] - loss) < 1e-4 * loss, "loss ${sums[0]} vs $loss")
        assertTrue(abs(sums[1] - slope) < 1e-4 * slope, "slope ${sums[1]} vs $slope")
        for (j in 0 until n) assertTrue(abs(row[j] - slopes[j]) < 1e-6, "slope $j: ${row[j]} vs ${slopes[j]}")
    }
}
