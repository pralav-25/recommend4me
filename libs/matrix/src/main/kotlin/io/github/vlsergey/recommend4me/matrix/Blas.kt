package io.github.vlsergey.recommend4me.matrix

import org.bytedeco.javacpp.FloatPointer
import org.bytedeco.openblas.global.openblas
import org.bytedeco.openblas.global.openblas_full
import org.slf4j.LoggerFactory
import java.util.stream.IntStream

private val log = LoggerFactory.getLogger(Blas::class.java)

/**
 * Matrix products done by OpenBLAS, ported from local-ai-chroma-enf-lora's `embeddings/Blas.kt`.
 *
 * ONE THREAD FOR THE LIBRARY, THE ROWS SHARED OUT AMONG OURS: OpenBLAS splits a product among
 * its own threads badly at these shapes, and our threads also know what else runs — the
 * cross-validation trains many models at once and fills the cores by itself.
 *
 * Absent (an operating system nobody built the natives for), the plain loops answer instead —
 * slower, never wrong — and the log says once which path is in force.
 */
object Blas {

    private const val THREADS = 1

    /** One part per physical core: products at these shapes wait for memory, not for arithmetic. */
    private val PARTS = maxOf(1, Runtime.getRuntime().availableProcessors() / 2)

    /** The fewest rows a part is worth having. */
    private const val LEAST_ROWS = 64

    /**
     * Whether the library is there. Its own setter is called by its own name: the binding's
     * `blas_set_num_threads` silently does nothing (found out in local-ai-chroma-enf-lora).
     */
    val on: Boolean = try {
        openblas_full.openblas_set_num_threads(THREADS)
        log.info(
            "matrix products go through OpenBLAS {} ({}), {} thread(s)",
            openblas.OPENBLAS_VERSION.trim(),
            openblas_full.openblas_get_corename()?.string?.trim(),
            openblas_full.openblas_get_num_threads(),
        )
        true
    } catch (e: Throwable) {
        log.warn("no OpenBLAS ({}), plain loops are used", e.javaClass.simpleName)
        false
    }

    /**
     * `out[i, j] = Σ_q a[i, q] · b[q, j]` over the first [m] rows of [a]: a batch through a layer.
     * [a] is m × k with row stride [lda], [b] is k × n, [out] is m × n.
     */
    fun product(a: Floats, lda: Int, m: Int, k: Int, b: Floats, n: Int, out: Floats, parts: Int = PARTS) {
        val pa = a.p
        val pb = b.p
        val pc = out.p
        if (pa == null || pb == null || pc == null) {
            out.clear(0, m * n)
            for (i in 0 until m) for (q in 0 until k) {
                val v = a[i * lda + q]
                if (v == 0f) continue
                for (j in 0 until n) out[i * n + j] = out[i * n + j] + v * b[q * n + j]
            }
            return
        }
        split(m, parts) { from, upTo ->
            openblas_full.cblas_sgemm(
                openblas_full.CblasRowMajor, openblas_full.CblasNoTrans, openblas_full.CblasNoTrans,
                upTo - from, n, k, 1.0f, FloatPointer(pa).position(from.toLong() * lda), lda, pb, n,
                0.0f, FloatPointer(pc).position(from.toLong() * n), n,
            )
        }
    }

    /**
     * `out[q, j] = Σ_i a[i, q] · deltas[i, j]` over the first [m] rows of both: the gradient of a
     * layer, written once a batch instead of once per example. [a] is m × k with stride [lda].
     */
    fun transposedProduct(a: Floats, lda: Int, m: Int, k: Int, deltas: Floats, n: Int, out: Floats, parts: Int = PARTS) {
        val pa = a.p
        val pb = deltas.p
        val pc = out.p
        if (pa == null || pb == null || pc == null) {
            out.clear(0, k * n)
            for (i in 0 until m) for (q in 0 until k) {
                val v = a[i * lda + q]
                if (v == 0f) continue
                for (j in 0 until n) out[q * n + j] = out[q * n + j] + v * deltas[i * n + j]
            }
            return
        }
        // Shared out by the rows of the gradient, so the parts write where they do not meet
        split(k, parts) { from, upTo ->
            openblas_full.cblas_sgemm(
                openblas_full.CblasRowMajor, openblas_full.CblasTrans, openblas_full.CblasNoTrans,
                upTo - from, n, m, 1.0f, FloatPointer(pa).position(from.toLong()), lda, pb, n,
                0.0f, FloatPointer(pc).position(from.toLong() * n), n,
            )
        }
    }

    /** `out[i] = Σ_q a[i, q] · x[q]` for the first [m] rows of [a] (stride [lda]). */
    fun times(a: Floats, lda: Int, m: Int, k: Int, x: Floats, out: Floats, parts: Int = PARTS) {
        val pa = a.p
        val px = x.p
        val py = out.p
        if (pa == null || px == null || py == null) {
            for (i in 0 until m) out[i] = Simd.dot(a, i * lda, x, 0, k)
            return
        }
        split(m, parts) { from, upTo ->
            openblas_full.cblas_sgemv(
                openblas_full.CblasRowMajor, openblas_full.CblasNoTrans, upTo - from, k,
                1.0f, FloatPointer(pa).position(from.toLong() * lda), lda, px, 1,
                0.0f, FloatPointer(py).position(from.toLong()), 1,
            )
        }
    }

    /** `out[q] = Σ_i a[i, q] · r[i]` over the first [m] rows: the gradient of a linear model. */
    fun transposedTimes(a: Floats, lda: Int, m: Int, k: Int, r: Floats, out: Floats) {
        val pa = a.p
        val pr = r.p
        val py = out.p
        if (pa == null || pr == null || py == null) {
            out.clear(0, k)
            for (i in 0 until m) {
                val ri = r[i]
                if (ri != 0f) for (q in 0 until k) out[q] = out[q] + ri * a[i * lda + q]
            }
            return
        }
        openblas_full.cblas_sgemv(
            openblas_full.CblasRowMajor, openblas_full.CblasTrans, m, k,
            1.0f, pa, lda, pr, 1, 0.0f, py, 1,
        )
    }

    /**
     * `c = op(a) · op(b) + beta · c`, row-major, ONE CALL OF THE WHOLE: the library shares it out
     * among its own threads when a caller asks for them ([wide]), none of ours. Every side
     * addressed where it lies — [aAt], [bAt], [cAt] the numbers it starts at — nothing copied.
     * op(a) is m × k (a is k × m when [transA]), op(b) k × n, c m × n.
     */
    fun gemm(
        transA: Boolean, transB: Boolean, m: Int, n: Int, k: Int,
        a: Floats, aAt: Int, lda: Int, b: Floats, bAt: Int, ldb: Int, c: Floats, cAt: Int, ldc: Int, beta: Float = 0f,
    ) {
        if (m == 0 || n == 0) return
        val pa = a.p
        val pb = b.p
        val pc = c.p
        if (pa == null || pb == null || pc == null) {
            for (i in 0 until m) for (j in 0 until n) {
                var s = 0f
                for (q in 0 until k) {
                    val x = if (transA) a[aAt + q * lda + i] else a[aAt + i * lda + q]
                    val y = if (transB) b[bAt + j * ldb + q] else b[bAt + q * ldb + j]
                    s += x * y
                }
                c[cAt + i * ldc + j] = s + (if (beta == 0f) 0f else beta * c[cAt + i * ldc + j])
            }
            return
        }
        openblas_full.cblas_sgemm(
            openblas_full.CblasRowMajor, if (transA) openblas_full.CblasTrans else openblas_full.CblasNoTrans,
            if (transB) openblas_full.CblasTrans else openblas_full.CblasNoTrans,
            m, n, k, 1.0f, FloatPointer(pa).position(aAt.toLong()), lda, FloatPointer(pb).position(bAt.toLong()), ldb,
            beta, FloatPointer(pc).position(cAt.toLong()), ldc,
        )
    }

    /**
     * `y = op(a) · x + beta · y` — op(a) of [m] × [n] numbers, a row-major of [lda]; transposed
     * when [trans] (a is then m × n and op(a) n × m) — one call of the whole, as [gemm].
     */
    fun gemv(trans: Boolean, m: Int, n: Int, a: Floats, aAt: Int, lda: Int, x: Floats, xAt: Int, y: Floats, yAt: Int, beta: Float = 0f) {
        if (m == 0 || n == 0) return
        val pa = a.p
        val px = x.p
        val py = y.p
        if (pa == null || px == null || py == null) {
            val rows = if (trans) n else m
            val cols = if (trans) m else n
            for (i in 0 until rows) {
                var s = 0f
                for (q in 0 until cols) s += (if (trans) a[aAt + q * lda + i] else a[aAt + i * lda + q]) * x[xAt + q]
                y[yAt + i] = s + (if (beta == 0f) 0f else beta * y[yAt + i])
            }
            return
        }
        openblas_full.cblas_sgemv(
            openblas_full.CblasRowMajor, if (trans) openblas_full.CblasTrans else openblas_full.CblasNoTrans, m, n,
            1.0f, FloatPointer(pa).position(aAt.toLong()), lda, FloatPointer(px).position(xAt.toLong()), 1,
            beta, FloatPointer(py).position(yAt.toLong()), 1,
        )
    }

    /** The callers inside [wide] now; the library's threads go back to one when the last leaves. */
    private var wideCallers = 0
    private val wideLock = Any()

    /**
     * [body] run with THE LIBRARY SHARING ITS PRODUCTS OUT AMONG ITS OWN THREADS, one a physical
     * core: for a caller of one thread whose products are of the whole — the fitting of a facet's
     * models, ten of them in one product ([gemm]). The setting is the library's, of the whole
     * process: a product another thread makes meanwhile goes on more threads too — the queue of the
     * computations lets one heavy work run at a time, so none but a page's small ones.
     */
    fun <T> wide(body: () -> T): T {
        if (!on) return body()
        synchronized(wideLock) {
            if (wideCallers++ == 0) openblas_full.openblas_set_num_threads(PARTS)
        }
        try {
            return body()
        } finally {
            synchronized(wideLock) {
                if (--wideCallers == 0) openblas_full.openblas_set_num_threads(THREADS)
            }
        }
    }

    /** `body(from, upTo)` over [count] rows in parts on the common pool; one part when there are few rows. */
    private inline fun split(count: Int, want: Int, crossinline body: (Int, Int) -> Unit) {
        val parts = minOf(want, maxOf(1, count / LEAST_ROWS))
        if (parts == 1) {
            body(0, count)
            return
        }
        IntStream.range(0, parts).parallel().forEach { p ->
            val from = count * p / parts
            val upTo = count * (p + 1) / parts
            if (upTo > from) body(from, upTo)
        }
    }
}
