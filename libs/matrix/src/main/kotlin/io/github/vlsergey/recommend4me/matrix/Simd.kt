package io.github.vlsergey.recommend4me.matrix

import jdk.incubator.vector.DoubleVector
import jdk.incubator.vector.FloatVector
import jdk.incubator.vector.VectorOperators
import org.slf4j.LoggerFactory
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.nio.ByteOrder

private val log = LoggerFactory.getLogger(Simd::class.java)

/**
 * Inner loops written for the wide registers (Vector API), ported from local-ai-chroma-enf-lora's
 * `student/Simd.kt`. Multiplication and addition are fused: the results differ from the plain
 * loops in the last digits, which is accepted for speed.
 *
 * The module is incubating, so the JVM must load it (`--add-modules jdk.incubator.vector`, which
 * the launcher and the tests pass). Started without it, the plain loops run instead.
 */
object Simd {

    val on: Boolean = try {
        val width = Probe.width()
        log.info("wide registers are on: {} floats at a time", width)
        width > 1
    } catch (e: Throwable) {
        log.warn("no wide registers ({}), plain loops are used; start the JVM with --add-modules jdk.incubator.vector", e.javaClass.simpleName)
        false
    }

    private val ORDER = ByteOrder.nativeOrder()
    private const val SUMS = 4

    /**
     * `Σ a[i + k] · b[j + k]` for k under [n]. Four sums at once, added up at the end: a single
     * sum waits for its own last fused multiply-add on every step.
     */
    fun dot(a: Floats, i: Int, b: Floats, j: Int, n: Int): Float {
        var k = 0
        var s = 0f
        if (on) {
            val sp = FloatVector.SPECIES_PREFERRED
            val width = sp.length()
            val bytes = Float.SIZE_BYTES.toLong()
            val fourBound = n - n % (SUMS * width)
            var acc0 = FloatVector.zero(sp)
            var acc1 = FloatVector.zero(sp)
            var acc2 = FloatVector.zero(sp)
            var acc3 = FloatVector.zero(sp)
            fun lane(f: Floats, at: Int): FloatVector = FloatVector.fromMemorySegment(sp, f.seg, at * bytes, ORDER)
            while (k < fourBound) {
                acc0 = lane(a, i + k).fma(lane(b, j + k), acc0)
                acc1 = lane(a, i + k + width).fma(lane(b, j + k + width), acc1)
                acc2 = lane(a, i + k + 2 * width).fma(lane(b, j + k + 2 * width), acc2)
                acc3 = lane(a, i + k + 3 * width).fma(lane(b, j + k + 3 * width), acc3)
                k += SUMS * width
            }
            val bound = sp.loopBound(n)
            while (k < bound) {
                acc0 = lane(a, i + k).fma(lane(b, j + k), acc0)
                k += width
            }
            s = acc0.add(acc1).add(acc2.add(acc3)).reduceLanes(VectorOperators.ADD)
        }
        while (k < n) {
            s += a[i + k] * b[j + k]
            k++
        }
        return s
    }

    /**
     * The logistic loss of a row of scores, every one against the label nought: for each `z =
     * row[j] + shift[j]` under [n], `softplus(z)` is summed into `sums[0]`, and `σ(z)·scale` — the
     * slope of the loss, scaled — is written over `row[j]` and summed into `sums[1]`. In single
     * precision, the exponent and the logarithm of a lane's worth at once: a large universe makes
     * tens of millions of these a step of a fitting.
     */
    fun logisticRow(row: FloatArray, shift: FloatArray, n: Int, scale: Float, sums: DoubleArray) {
        val seg = MemorySegment.ofArray(row)
        logistic(seg, 0, shift, null, null, n, scale, seg, 0, sums)
    }

    /** [logisticRow] of the row of [n] scores at [at] of [m], in place: read and written where they lie, not copied out and back. */
    fun logisticRow(m: Floats, at: Int, shift: FloatArray, n: Int, scale: Float, sums: DoubleArray) =
        logistic(m.seg, at, shift, null, null, n, scale, m.seg, at, sums)

    /**
     * The weighted logistic loss of a row of scores against its labels: for each `z = row[j] +
     * shift[j]` under [n], `w[j]·(softplus(z) − y[j]·z)` is summed into `sums[0]`, and the slope
     * `w[j]·(σ(z) − y[j])·scale` is written into `slopes[j]` and summed into `sums[1]`. A pair of
     * weight nought adds nothing and has the slope nought.
     */
    fun weightedLogisticRow(row: FloatArray, shift: FloatArray, w: FloatArray, y: FloatArray, n: Int, scale: Float, slopes: FloatArray, sums: DoubleArray) =
        logistic(MemorySegment.ofArray(row), 0, shift, w, y, n, scale, MemorySegment.ofArray(slopes), 0, sums)

    private fun logistic(
        seg: MemorySegment, at: Int, shift: FloatArray, w: FloatArray?, y: FloatArray?, n: Int, scale: Float,
        out: MemorySegment, outAt: Int, sums: DoubleArray,
    ) {
        val bytes = Float.SIZE_BYTES.toLong()
        var loss = 0.0
        var slope = 0.0
        if (on) {
            val sp = FloatVector.SPECIES_PREFERRED
            val one = FloatVector.broadcast(sp, 1f)
            val zero = FloatVector.zero(sp)
            var lossAcc = zero
            var slopeAcc = zero
            var j = 0
            // The last lanes past the row masked off: a universe of a few characters is all tail
            while (j < n) {
                val mask = sp.indexInRange(j, n)
                val z = FloatVector.fromMemorySegment(sp, seg, (at + j) * bytes, ORDER, mask).add(FloatVector.fromArray(sp, shift, j, mask))
                // softplus and sigmoid from one exponent
                val e = z.abs().neg().lanewise(VectorOperators.EXP)
                val inverse = one.div(one.add(e))
                var sigmoid = e.mul(inverse).blend(inverse, z.compare(VectorOperators.GE, 0f))
                var l = z.max(0f).add(e.lanewise(VectorOperators.LOG1P))
                if (y != null) {
                    val label = FloatVector.fromArray(sp, y, j, mask)
                    l = l.sub(label.mul(z))
                    sigmoid = sigmoid.sub(label)
                }
                if (w != null) {
                    val weight = FloatVector.fromArray(sp, w, j, mask)
                    l = l.mul(weight)
                    sigmoid = sigmoid.mul(weight)
                }
                val d = sigmoid.mul(scale)
                d.intoMemorySegment(out, (outAt + j) * bytes, ORDER, mask)
                lossAcc = lossAcc.add(l, mask)
                slopeAcc = slopeAcc.add(d, mask)
                j += sp.length()
            }
            loss = lossAcc.reduceLanes(VectorOperators.ADD).toDouble()
            slope = slopeAcc.reduceLanes(VectorOperators.ADD).toDouble()
        } else for (j in 0 until n) {
            val z = seg.getAtIndex(ValueLayout.JAVA_FLOAT, (at + j).toLong()) + shift[j]
            val e = kotlin.math.exp(-kotlin.math.abs(z))
            val label = y?.get(j) ?: 0f
            val weight = w?.get(j) ?: 1f
            loss += weight * (kotlin.math.max(z, 0f) + kotlin.math.ln1p(e) - label * z)
            val d = weight * ((if (z >= 0) 1 / (1 + e) else e / (1 + e)) - label) * scale
            out.setAtIndex(ValueLayout.JAVA_FLOAT, (outAt + j).toLong(), d)
            slope += d
        }
        sums[0] += loss
        sums[1] += slope
    }

    /** `Σ a[k] · b[k]` in double precision, four sums at once in the wide registers. */
    fun dot(a: DoubleArray, b: DoubleArray): Double {
        var k = 0
        var s = 0.0
        if (on) {
            val sp = DoubleVector.SPECIES_PREFERRED
            val width = sp.length()
            val fourBound = a.size - a.size % (SUMS * width)
            var acc0 = DoubleVector.zero(sp)
            var acc1 = DoubleVector.zero(sp)
            var acc2 = DoubleVector.zero(sp)
            var acc3 = DoubleVector.zero(sp)
            while (k < fourBound) {
                acc0 = DoubleVector.fromArray(sp, a, k).fma(DoubleVector.fromArray(sp, b, k), acc0)
                acc1 = DoubleVector.fromArray(sp, a, k + width).fma(DoubleVector.fromArray(sp, b, k + width), acc1)
                acc2 = DoubleVector.fromArray(sp, a, k + 2 * width).fma(DoubleVector.fromArray(sp, b, k + 2 * width), acc2)
                acc3 = DoubleVector.fromArray(sp, a, k + 3 * width).fma(DoubleVector.fromArray(sp, b, k + 3 * width), acc3)
                k += SUMS * width
            }
            s = acc0.add(acc1).add(acc2.add(acc3)).reduceLanes(VectorOperators.ADD)
        }
        while (k < a.size) {
            s += a[k] * b[k]
            k++
        }
        return s
    }

    /** `y[k] += c · x[k]` in double precision, a lane's worth at once. */
    fun axpy(c: Double, x: DoubleArray, y: DoubleArray) {
        var k = 0
        if (on) {
            val sp = DoubleVector.SPECIES_PREFERRED
            val bound = sp.loopBound(x.size)
            val cv = DoubleVector.broadcast(sp, c)
            while (k < bound) {
                DoubleVector.fromArray(sp, x, k).fma(cv, DoubleVector.fromArray(sp, y, k)).intoArray(y, k)
                k += sp.length()
            }
        }
        while (k < x.size) {
            y[k] += c * x[k]
            k++
        }
    }
}

/**
 * The question «are the registers there» asked in a class of its own: without the module the
 * answer is a linkage error, which must be thrown inside a call to be caught.
 */
private object Probe {
    fun width(): Int = FloatVector.SPECIES_PREFERRED.length()
}
