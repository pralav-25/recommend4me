package io.github.vlsergey.recommend4me.matrix

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
    fun logisticRow(row: FloatArray, shift: FloatArray, n: Int, scale: Float, sums: DoubleArray) =
        logisticRow(MemorySegment.ofArray(row), 0, shift, n, scale, sums)

    /** [logisticRow] of the row of [n] scores at [at] of [m], in place: read and written where they lie, not copied out and back. */
    fun logisticRow(m: Floats, at: Int, shift: FloatArray, n: Int, scale: Float, sums: DoubleArray) =
        logisticRow(m.seg, at, shift, n, scale, sums)

    private fun logisticRow(seg: MemorySegment, at: Int, shift: FloatArray, n: Int, scale: Float, sums: DoubleArray) {
        val bytes = Float.SIZE_BYTES.toLong()
        var j = 0
        var loss = 0.0
        var slope = 0.0
        if (on) {
            val sp = FloatVector.SPECIES_PREFERRED
            val bound = sp.loopBound(n)
            val one = FloatVector.broadcast(sp, 1f)
            var lossAcc = FloatVector.zero(sp)
            var slopeAcc = FloatVector.zero(sp)
            while (j < bound) {
                val offset = (at + j) * bytes
                val z = FloatVector.fromMemorySegment(sp, seg, offset, ORDER).add(FloatVector.fromArray(sp, shift, j))
                // softplus and sigmoid from one exponent
                val e = z.abs().neg().lanewise(VectorOperators.EXP)
                val inverse = one.div(one.add(e))
                val sigmoid = e.mul(inverse).blend(inverse, z.compare(VectorOperators.GE, 0f))
                lossAcc = lossAcc.add(z.max(0f).add(e.lanewise(VectorOperators.LOG1P)))
                val d = sigmoid.mul(scale)
                d.intoMemorySegment(seg, offset, ORDER)
                slopeAcc = slopeAcc.add(d)
                j += sp.length()
            }
            loss = lossAcc.reduceLanes(VectorOperators.ADD).toDouble()
            slope = slopeAcc.reduceLanes(VectorOperators.ADD).toDouble()
        }
        while (j < n) {
            val z = seg.getAtIndex(ValueLayout.JAVA_FLOAT, (at + j).toLong()) + shift[j]
            val e = kotlin.math.exp(-kotlin.math.abs(z))
            loss += kotlin.math.max(z, 0f) + kotlin.math.ln1p(e)
            val d = (if (z >= 0) 1 / (1 + e) else e / (1 + e)) * scale
            seg.setAtIndex(ValueLayout.JAVA_FLOAT, (at + j).toLong(), d)
            slope += d
            j++
        }
        sums[0] += loss
        sums[1] += slope
    }
}

/**
 * The question «are the registers there» asked in a class of its own: without the module the
 * answer is a linkage error, which must be thrown inside a call to be caught.
 */
private object Probe {
    fun width(): Int = FloatVector.SPECIES_PREFERRED.length()
}
