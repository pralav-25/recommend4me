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
    fun logisticRow(row: FloatArray, shift: FloatArray, n: Int, scale: Float, sums: FloatArray) {
        val seg = MemorySegment.ofArray(row)
        logistic(seg, 0, shift, null, null, n, scale, seg, 0, sums)
    }

    /** [logisticRow] of the row of [n] scores at [at] of [m], in place: read and written where they lie, not copied out and back. */
    fun logisticRow(m: Floats, at: Int, shift: FloatArray, n: Int, scale: Float, sums: FloatArray) =
        logistic(m.seg, at, shift, null, null, n, scale, m.seg, at, sums)

    /**
     * The weighted logistic loss of a row of scores against its labels: for each `z = row[j] +
     * shift[j]` under [n], `w[j]·(softplus(z) − y[j]·z)` is summed into `sums[0]`, and the slope
     * `w[j]·(σ(z) − y[j])·scale` is written into `slopes[j]` and summed into `sums[1]`. A pair of
     * weight nought adds nothing and has the slope nought.
     */
    fun weightedLogisticRow(row: FloatArray, shift: FloatArray, w: FloatArray, y: FloatArray, n: Int, scale: Float, slopes: FloatArray, sums: FloatArray) =
        logistic(MemorySegment.ofArray(row), 0, shift, w, y, n, scale, MemorySegment.ofArray(slopes), 0, sums)

    private fun logistic(
        seg: MemorySegment, at: Int, shift: FloatArray, w: FloatArray?, y: FloatArray?, n: Int, scale: Float,
        out: MemorySegment, outAt: Int, sums: FloatArray,
    ) {
        val bytes = Float.SIZE_BYTES.toLong()
        var loss = 0f
        var slope = 0f
        if (on) {
            val sp = FloatVector.SPECIES_PREFERRED
            val width = sp.length()
            val one = FloatVector.broadcast(sp, 1f)
            var lossAcc = FloatVector.zero(sp)
            var slopeAcc = FloatVector.zero(sp)
            val full = sp.loopBound(n)
            var j = 0
            while (j < full) {
                val z = FloatVector.fromMemorySegment(sp, seg, (at + j) * bytes, ORDER).add(FloatVector.fromArray(sp, shift, j))
                val label = y?.let { FloatVector.fromArray(sp, it, j) }
                val weight = w?.let { FloatVector.fromArray(sp, it, j) }
                lane(z, label, weight, one, scale) { l, d ->
                    d.intoMemorySegment(out, (outAt + j) * bytes, ORDER)
                    lossAcc = lossAcc.add(l)
                    slopeAcc = slopeAcc.add(d)
                }
                j += width
            }
            loss = lossAcc.reduceLanes(VectorOperators.ADD)
            slope = slopeAcc.reduceLanes(VectorOperators.ADD)
            // The last lanes one at a time: no scratch made for them — a row of a few values is all
            // tail, and a fitting takes millions of rows a step
            for (k in full until n) {
                loss += scalar(seg, at, k, shift, w, y, scale, out, outAt)
                slope += out.getAtIndex(ValueLayout.JAVA_FLOAT, (outAt + k).toLong())
            }
        } else for (j in 0 until n) {
            loss += scalar(seg, at, j, shift, w, y, scale, out, outAt)
            slope += out.getAtIndex(ValueLayout.JAVA_FLOAT, (outAt + j).toLong())
        }
        sums[0] += loss
        sums[1] += slope
    }

    /**
     * The score at `at + j` one number at a time — [logistic] without the wide registers: its slope
     * written at `outAt + j` (read in place first: the score and the slope may be one number), its loss answered.
     */
    private fun scalar(seg: MemorySegment, at: Int, j: Int, shift: FloatArray, w: FloatArray?, y: FloatArray?, scale: Float, out: MemorySegment, outAt: Int): Float {
        val z = seg.getAtIndex(ValueLayout.JAVA_FLOAT, (at + j).toLong()) + shift[j]
        val e = kotlin.math.exp(-kotlin.math.abs(z))
        val label = y?.get(j) ?: 0f
        val weight = w?.get(j) ?: 1f
        out.setAtIndex(ValueLayout.JAVA_FLOAT, (outAt + j).toLong(), weight * ((if (z >= 0) 1 / (1 + e) else e / (1 + e)) - label) * scale)
        return weight * (kotlin.math.max(z, 0f) + kotlin.math.ln1p(e) - label * z)
    }

    /**
     * The loss and the slope of a lane's worth of scores [z] against the [label]s (nought when
     * none), each pair weighed by its [weight] (one when none): softplus and sigmoid from one
     * exponent, handed to [take].
     */
    private inline fun lane(
        z: FloatVector, label: FloatVector?, weight: FloatVector?, one: FloatVector, scale: Float,
        take: (loss: FloatVector, slope: FloatVector) -> Unit,
    ) {
        val e = z.abs().neg().lanewise(VectorOperators.EXP)
        val inverse = one.div(one.add(e))
        var sigmoid = e.mul(inverse).blend(inverse, z.compare(VectorOperators.GE, 0f))
        var l = z.max(0f).add(e.lanewise(VectorOperators.LOG1P))
        if (label != null) {
            l = l.sub(label.mul(z))
            sigmoid = sigmoid.sub(label)
        }
        if (weight != null) {
            l = l.mul(weight)
            sigmoid = sigmoid.mul(weight)
        }
        take(l, sigmoid.mul(scale))
    }

    /** `Σ a[k] · b[k]` in single precision, four sums at once in the wide registers. */
    fun dot(a: FloatArray, b: FloatArray): Float {
        var k = 0
        var s = 0f
        if (on) {
            val sp = FloatVector.SPECIES_PREFERRED
            val width = sp.length()
            val fourBound = a.size - a.size % (SUMS * width)
            var acc0 = FloatVector.zero(sp)
            var acc1 = FloatVector.zero(sp)
            var acc2 = FloatVector.zero(sp)
            var acc3 = FloatVector.zero(sp)
            while (k < fourBound) {
                acc0 = FloatVector.fromArray(sp, a, k).fma(FloatVector.fromArray(sp, b, k), acc0)
                acc1 = FloatVector.fromArray(sp, a, k + width).fma(FloatVector.fromArray(sp, b, k + width), acc1)
                acc2 = FloatVector.fromArray(sp, a, k + 2 * width).fma(FloatVector.fromArray(sp, b, k + 2 * width), acc2)
                acc3 = FloatVector.fromArray(sp, a, k + 3 * width).fma(FloatVector.fromArray(sp, b, k + 3 * width), acc3)
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

    /** `y[k] += c · x[k]` in single precision, a lane's worth at once. */
    fun axpy(c: Float, x: FloatArray, y: FloatArray) {
        var k = 0
        if (on) {
            val sp = FloatVector.SPECIES_PREFERRED
            val bound = sp.loopBound(x.size)
            val cv = FloatVector.broadcast(sp, c)
            while (k < bound) {
                FloatVector.fromArray(sp, x, k).fma(cv, FloatVector.fromArray(sp, y, k)).intoArray(y, k)
                k += sp.length()
            }
        }
        while (k < x.size) {
            y[k] += c * x[k]
            k++
        }
    }

    /** `y[yAt + k] += c · x[xAt + k]` for k under [n], where the numbers lie: a lane's worth at once. */
    fun axpy(c: Float, x: Floats, xAt: Int, y: Floats, yAt: Int, n: Int) {
        var k = 0
        val bytes = Float.SIZE_BYTES.toLong()
        if (on) {
            val sp = FloatVector.SPECIES_PREFERRED
            val bound = sp.loopBound(n)
            val cv = FloatVector.broadcast(sp, c)
            while (k < bound) {
                FloatVector.fromMemorySegment(sp, x.seg, (xAt + k) * bytes, ORDER)
                    .fma(cv, FloatVector.fromMemorySegment(sp, y.seg, (yAt + k) * bytes, ORDER))
                    .intoMemorySegment(y.seg, (yAt + k) * bytes, ORDER)
                k += sp.length()
            }
        }
        while (k < n) {
            y[yAt + k] = y[yAt + k] + c * x[xAt + k]
            k++
        }
    }

    /** `Σ f[at + k]` for k under [n], where the numbers lie. */
    fun sum(f: Floats, at: Int, n: Int): Float {
        var k = 0
        var s = 0f
        val bytes = Float.SIZE_BYTES.toLong()
        if (on) {
            val sp = FloatVector.SPECIES_PREFERRED
            val bound = sp.loopBound(n)
            var acc = FloatVector.zero(sp)
            while (k < bound) {
                acc = acc.add(FloatVector.fromMemorySegment(sp, f.seg, (at + k) * bytes, ORDER))
                k += sp.length()
            }
            s = acc.reduceLanes(VectorOperators.ADD)
        }
        while (k < n) {
            s += f[at + k]
            k++
        }
        return s
    }

    /** `f[at + k] += c` for k under [n], where the numbers lie. */
    fun add(c: Float, f: Floats, at: Int, n: Int) {
        var k = 0
        val bytes = Float.SIZE_BYTES.toLong()
        if (on) {
            val sp = FloatVector.SPECIES_PREFERRED
            val bound = sp.loopBound(n)
            val cv = FloatVector.broadcast(sp, c)
            while (k < bound) {
                FloatVector.fromMemorySegment(sp, f.seg, (at + k) * bytes, ORDER).add(cv).intoMemorySegment(f.seg, (at + k) * bytes, ORDER)
                k += sp.length()
            }
        }
        while (k < n) {
            f[at + k] = f[at + k] + c
            k++
        }
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
