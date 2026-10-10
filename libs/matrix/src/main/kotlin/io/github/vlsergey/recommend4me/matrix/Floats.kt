package io.github.vlsergey.recommend4me.matrix

import org.bytedeco.javacpp.FloatPointer
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.nio.ByteOrder

/**
 * Floats in ONE COPY read from both sides: our own Vector API loops read the segment,
 * OpenBLAS reads the pointer, and both are the same off-heap memory. Copying an array into
 * native memory for every product costs more than the product itself (the same approach and
 * its measurements come from local-ai-chroma-enf-lora's `embeddings/Floats.kt`).
 *
 * The memory belongs to the garbage collector (`Arena.ofAuto`): nothing to close, and nothing
 * freed under a reader still holding it. The pointer lives in this object for that reason — it
 * carries an address and does not keep the memory alive by itself.
 */
class Floats(val count: Int) {

    init {
        // OpenBLAS is handed a buffer, and a buffer is indexed by int
        require(count.toLong() * Float.SIZE_BYTES <= Int.MAX_VALUE) { "$count floats do not fit one block" }
    }

    internal val seg: MemorySegment = Arena.ofAuto().allocate(ValueLayout.JAVA_FLOAT, count.toLong().coerceAtLeast(1))

    /** The same memory as OpenBLAS sees it; null when the library is not available. */
    internal val p: FloatPointer? =
        if (Blas.on) FloatPointer(seg.asByteBuffer().order(ByteOrder.nativeOrder()).asFloatBuffer()) else null

    operator fun get(at: Int): Float = seg.getAtIndex(ValueLayout.JAVA_FLOAT, at.toLong())

    operator fun set(at: Int, v: Float) = seg.setAtIndex(ValueLayout.JAVA_FLOAT, at.toLong(), v)

    /** [n] numbers of [src] from [from] on, written at [at]. */
    fun put(src: FloatArray, from: Int, at: Int, n: Int) =
        MemorySegment.copy(src, from, seg, ValueLayout.JAVA_FLOAT, at.toLong() * Float.SIZE_BYTES, n)

    /** [n] numbers from [at] on, copied into [into] at [to]. */
    fun take(into: FloatArray, to: Int, at: Int, n: Int) =
        MemorySegment.copy(seg, ValueLayout.JAVA_FLOAT, at.toLong() * Float.SIZE_BYTES, into, to, n)

    /** [n] numbers of [from] starting at [fromAt], written at [at], without passing through the Java heap. */
    fun copy(from: Floats, fromAt: Int, at: Int, n: Int) = MemorySegment.copy(
        from.seg, fromAt.toLong() * Float.SIZE_BYTES, seg, at.toLong() * Float.SIZE_BYTES, n.toLong() * Float.SIZE_BYTES,
    )

    /** The numbers at [at] + every one of [indices], into [into] in their order: a sparse row read. */
    fun gather(into: FloatArray, at: Int, indices: IntArray) {
        for (k in indices.indices) into[k] = this[at + indices[k]]
    }

    /** [src] written at [at] + every one of [indices], in their order: a sparse row written. */
    fun scatter(src: FloatArray, at: Int, indices: IntArray) {
        for (k in indices.indices) this[at + indices[k]] = src[k]
    }

    fun clear(at: Int = 0, n: Int = count) {
        seg.asSlice(at.toLong() * Float.SIZE_BYTES, n.toLong() * Float.SIZE_BYTES).fill(0)
    }

    fun toArray(): FloatArray = FloatArray(count).also { take(it, 0, 0, count) }

    companion object {
        fun of(src: FloatArray): Floats = Floats(src.size).also { it.put(src, 0, 0, src.size) }
    }
}

/** Row-major rows of equal width in one off-heap block. */
class Matrix(val rows: Int, val cols: Int) {
    val held = Floats(rows * cols)

    fun row(i: Int): Int = i * cols

    /** The rows [indices] of this matrix gathered into a new one, without passing through the Java heap. */
    fun gather(indices: IntArray): Matrix {
        val out = Matrix(indices.size, cols)
        indices.forEachIndexed { k, i -> out.held.copy(held, i * cols, k * cols, cols) }
        return out
    }
}
