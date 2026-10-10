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
        val sums = FloatArray(2)
        Simd.logisticRow(row, shift, n, scale, sums)
        assertTrue(abs(sums[0] - loss) < 1e-4 * loss, "loss ${sums[0]} vs $loss")
        assertTrue(abs(sums[1] - slope) < 1e-4 * slope, "slope ${sums[1]} vs $slope")
        for (j in 0 until n) assertTrue(abs(row[j] - slopes[j]) < 1e-6, "slope $j: ${row[j]} vs ${slopes[j]}")
    }

    @Test
    fun `a weighted row with labels agrees with the plain loop, a short row all tail`() {
        val random = Random(3)
        for (n in listOf(5, 1003)) {
            val row = FloatArray(n) { (random.nextFloat() - 0.5f) * 20 }
            val shift = FloatArray(n) { random.nextFloat() - 0.5f }
            // Some pairs of weight nought: they add nothing
            val w = FloatArray(n) { if (it % 7 == 0) 0f else random.nextFloat() }
            val y = FloatArray(n) { if (random.nextBoolean()) 1f else 0f }
            val scale = 1f / 3
            var loss = 0.0
            var slope = 0.0
            val expected = DoubleArray(n) { j ->
                val z = (row[j] + shift[j]).toDouble()
                val e = exp(-abs(z))
                loss += w[j] * (max(z, 0.0) + ln1p(e) - y[j] * z)
                (w[j] * ((if (z >= 0) 1 / (1 + e) else e / (1 + e)) - y[j]) * scale).also { slope += it }
            }
            val slopes = FloatArray(n)
            val sums = FloatArray(2)
            Simd.weightedLogisticRow(row, shift, w, y, n, scale, slopes, sums)
            assertTrue(abs(sums[0] - loss) < 1e-4 * abs(loss) + 1e-5, "n=$n loss ${sums[0]} vs $loss")
            assertTrue(abs(sums[1] - slope) < 1e-4 * abs(slope) + 1e-5, "n=$n slope ${sums[1]} vs $slope")
            for (j in 0 until n) assertTrue(abs(slopes[j] - expected[j]) < 1e-6, "n=$n slope $j: ${slopes[j]} vs ${expected[j]}")
        }
    }

    @Test
    fun `dot and axpy agree with the plain loops`() {
        val random = Random(4)
        val a = DoubleArray(1027) { random.nextDouble() - 0.5 }
        val b = DoubleArray(1027) { random.nextDouble() - 0.5 }
        assertTrue(abs(Simd.dot(a, b) - a.indices.sumOf { a[it] * b[it] }) < 1e-12)
        val y = b.copyOf()
        Simd.axpy(0.3, a, y)
        for (k in a.indices) assertTrue(abs(y[k] - (b[k] + 0.3 * a[k])) < 1e-15)
        // And in single precision
        val af = FloatArray(a.size) { a[it].toFloat() }
        val bf = FloatArray(b.size) { b[it].toFloat() }
        assertTrue(abs(Simd.dot(af, bf) - af.indices.sumOf { (af[it] * bf[it]).toDouble() }) < 1e-4)
        val yf = bf.copyOf()
        Simd.axpy(0.3f, af, yf)
        for (k in af.indices) assertTrue(abs(yf[k] - (bf[k] + 0.3f * af[k])) < 1e-6)
    }
}
