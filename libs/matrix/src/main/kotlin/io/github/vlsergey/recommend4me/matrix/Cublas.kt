package io.github.vlsergey.recommend4me.matrix

import org.slf4j.LoggerFactory
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle
import java.nio.file.Path
import kotlin.math.abs

private val log = LoggerFactory.getLogger(Cublas::class.java)

/**
 * THE MATRIX PRODUCTS ON THE NVIDIA CARD: cuBLAS, called straight through the foreign function
 * interface of the JDK — the CUDA runtime's own libraries ([open] is handed their folder, the very
 * ones ONNX Runtime has loaded), no binding of a library besides. What OpenBLAS is to the processor:
 * `sgemm` of row-major matrices, here kept in the card's memory ([DeviceFloats]) — a fitting puts its
 * gigabyte and a half of the items' features there once and multiplies them there every step.
 *
 * One handle for the process, used by one thread at a time: the fittings run one at a time (the
 * queue of the computations). Opened once; a card the libraries cannot use — no card, the libraries
 * missing, a product that comes out wrong — leaves it closed, and the products are the processor's.
 */
class Cublas private constructor(
    private val cudaMalloc: MethodHandle,
    private val cudaFree: MethodHandle,
    private val cudaMemcpy: MethodHandle,
    private val cublasSgemm: MethodHandle,
    private val handle: MemorySegment,
) {

    /** Floats in the card's memory: [count] of them. Freed by [close]. */
    inner class DeviceFloats(val count: Long) : AutoCloseable {
        val address: MemorySegment = Arena.ofConfined().use { a ->
            val out = a.allocate(ValueLayout.ADDRESS)
            check((cudaMalloc.invokeExact(out, count * Float.SIZE_BYTES) as Int) == 0) { "the card has no room for $count floats" }
            out.get(ValueLayout.ADDRESS, 0)
        }
        private var closed = false

        /** [n] numbers of [from] at [fromAt] written at [at]: from the main memory to the card. */
        fun upload(from: Floats, fromAt: Int, at: Long, n: Int) =
            copy(at(at), from.seg.asSlice(fromAt.toLong() * Float.SIZE_BYTES), n.toLong(), HOST_TO_DEVICE)

        /** [n] numbers at [at] written into [into] at [intoAt]: from the card to the main memory. */
        fun download(into: Floats, intoAt: Int, at: Long, n: Int) =
            copy(into.seg.asSlice(intoAt.toLong() * Float.SIZE_BYTES), at(at), n.toLong(), DEVICE_TO_HOST)

        fun at(k: Long): MemorySegment = MemorySegment.ofAddress(address.address() + k * Float.SIZE_BYTES)

        override fun close() {
            if (closed) return
            closed = true
            cudaFree.invokeExact(address) as Int
        }
    }

    /** Room for [count] floats on the card; null when it has none left. */
    fun allocate(count: Long): DeviceFloats? = runCatching { DeviceFloats(count) }.getOrNull()

    private fun copy(dst: MemorySegment, src: MemorySegment, n: Long, kind: Int) {
        val status = cudaMemcpy.invokeExact(dst, src, n * Float.SIZE_BYTES, kind) as Int
        check(status == 0) { "a copy to or from the card failed: CUDA error $status" }
    }

    /**
     * `c = op(a) · op(b) + beta · c` of row-major matrices on the card, as [Blas.gemm]: op(a) m × k,
     * op(b) k × n, c m × n. cuBLAS reads column-major, where the row-major c is cᵀ = op(b)ᵀ op(a)ᵀ:
     * the two sides handed over swapped, each with its own leading dimension.
     */
    fun gemm(
        transA: Boolean, transB: Boolean, m: Int, n: Int, k: Int,
        a: DeviceFloats, aAt: Long, lda: Int, b: DeviceFloats, bAt: Long, ldb: Int, c: DeviceFloats, cAt: Long, ldc: Int, beta: Float = 0f,
    ) {
        if (m == 0 || n == 0) return
        Arena.ofConfined().use { arena ->
            val alpha = arena.allocateFrom(ValueLayout.JAVA_FLOAT, 1f)
            val b0 = arena.allocateFrom(ValueLayout.JAVA_FLOAT, beta)
            val status = cublasSgemm.invokeExact(
                handle, if (transB) OP_T else OP_N, if (transA) OP_T else OP_N, n, m, k,
                alpha, b.at(bAt), ldb, a.at(aAt), lda, b0, c.at(cAt), ldc,
            ) as Int
            check(status == 0) { "a product on the card failed: cuBLAS status $status" }
        }
    }

    companion object {
        private const val OP_N = 0
        private const val OP_T = 1
        private const val HOST_TO_DEVICE = 1
        private const val DEVICE_TO_HOST = 2

        private var opened: Cublas? = null
        private var tried = false

        /**
         * cuBLAS of the libraries in [dir] — cudart64_12 and cublas64_12 — opened once for the
         * process and checked by a product of its own against the processor's; null when it cannot
         * be, said once in the log.
         */
        @Synchronized
        fun open(dir: Path): Cublas? {
            if (tried) return opened
            tried = true
            opened = try {
                val linker = Linker.nativeLinker()
                val runtime = SymbolLookup.libraryLookup(dir.resolve("cudart64_12.dll"), Arena.global())
                val blas = SymbolLookup.libraryLookup(dir.resolve("cublas64_12.dll"), Arena.global())
                fun handleOf(lookup: SymbolLookup, name: String, descriptor: FunctionDescriptor) =
                    linker.downcallHandle(lookup.find(name).orElseThrow { IllegalStateException("no $name") }, descriptor)
                val int = ValueLayout.JAVA_INT
                val address = ValueLayout.ADDRESS
                val cublasCreate = handleOf(blas, "cublasCreate_v2", FunctionDescriptor.of(int, address))
                val handle = Arena.ofConfined().use { a ->
                    val out = a.allocate(address)
                    check((cublasCreate.invokeExact(out) as Int) == 0) { "cuBLAS was not opened" }
                    out.get(address, 0)
                }
                Cublas(
                    cudaMalloc = handleOf(runtime, "cudaMalloc", FunctionDescriptor.of(int, address, ValueLayout.JAVA_LONG)),
                    cudaFree = handleOf(runtime, "cudaFree", FunctionDescriptor.of(int, address)),
                    cudaMemcpy = handleOf(runtime, "cudaMemcpy", FunctionDescriptor.of(int, address, address, ValueLayout.JAVA_LONG, int)),
                    cublasSgemm = handleOf(
                        blas, "cublasSgemm_v2",
                        FunctionDescriptor.of(int, address, int, int, int, int, int, address, address, int, address, int, address, address, int),
                    ),
                    handle = handle,
                ).takeIf { it.checked() }.also { if (it == null) log.warn("cuBLAS in {} computes a product wrong: the products stay on the processor", dir) }
            } catch (e: Throwable) {
                log.warn("no cuBLAS in {} ({}): the products stay on the processor", dir, e.message ?: e.javaClass.simpleName)
                null
            }
            if (opened != null) log.info("matrix products of the fittings go through cuBLAS of {}", dir)
            return opened
        }
    }

    /** A product of its own, on the card and by the processor's plain loops: the same to the float's last digits. */
    private fun checked(): Boolean {
        val m = 3
        val n = 4
        val k = 5
        val a = Floats(m * k).also { f -> for (i in 0 until f.count) f[i] = (i % 7 - 3) / 2f }
        val b = Floats(n * k).also { f -> for (i in 0 until f.count) f[i] = (i % 5 - 2) / 3f }
        val expected = Floats(m * n)
        val got = Floats(m * n)
        // a · bᵀ: one side transposed, as the fittings' products are
        for (i in 0 until m) for (j in 0 until n) {
            var s = 0f
            for (q in 0 until k) s += a[i * k + q] * b[j * k + q]
            expected[i * n + j] = s
        }
        DeviceFloats(a.count.toLong()).use { da ->
            DeviceFloats(b.count.toLong()).use { db ->
                DeviceFloats(got.count.toLong()).use { dc ->
                    da.upload(a, 0, 0, a.count)
                    db.upload(b, 0, 0, b.count)
                    gemm(false, true, m, n, k, da, 0, k, db, 0, k, dc, 0, n)
                    dc.download(got, 0, 0, got.count)
                }
            }
        }
        return (0 until m * n).all { abs(got[it] - expected[it]) < 1e-4f }
    }
}
