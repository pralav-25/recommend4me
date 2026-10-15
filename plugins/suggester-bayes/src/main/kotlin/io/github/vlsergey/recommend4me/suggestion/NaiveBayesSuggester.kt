package io.github.vlsergey.recommend4me.suggestion

import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.concurrent.CancellationException
import kotlin.math.exp
import kotlin.math.ln

private val log = LoggerFactory.getLogger(NaiveBayesSuggester::class.java)

/**
 * NAIVE BAYES OVER THE WORK'S OTHER VALUES: the chance a work has a value from how often the works
 * with each of its other values — its fandom, the site's tags, its author — have it, each told apart
 * as if alone, with the value's own share among the works. Reads no text and no picture: fast, and
 * a measure of how much of a facet the other facets tell by themselves.
 *
 *     logit P(v | i) = ln((N₁ᵥ + α) / (N₀ᵥ + α)) + s · Σ_{c ∈ context(i)} ln( (n₁(c,v) + α·q_c) / (N₁ᵥ + α) · (N₀ᵥ + α) / (n₀(c,v) + α·q_c) )
 *
 * N₁ᵥ, N₀ᵥ: the weights of the pairs of v labelled 1 and 0 ([SuggestionPairs]); n₁(c,v), n₀(c,v)
 * of the works with c; q_c the share of the works with c. The values of the other facets are not
 * independent — a fandom and its pairings say the same twice — so their sum is scaled by [s]; [α]
 * smooths a rare one. Both chosen by the held-out loss of the five folds, the one-standard-error rule
 * preferring the stronger smoothing and the smaller scale.
 *
 * Its counts are the task's own: a work is scored by the counts of every other work — its own pairs
 * taken out — so nothing is packed but the two settings.
 */
class NaiveBayesSuggester : FacetSuggester {

    override val id = "naive-bayes-1"

    override fun fit(task: SuggestionTask, previous: FittedSuggester?, stop: () -> Boolean): FittedSuggester? {
        if (task.valueCount == 0 || task.assigned.all { it.isEmpty() }) return null
        val started = System.currentTimeMillis()
        val pairs = SuggestionPairs(task)
        val counts = Counts(task, pairs)
        val tried = ArrayList<Tried>()
        for (alpha in ALPHAS) for (scale in SCALES) {
            if (stop()) throw CancellationException("naive Bayes given up for newer changes")
            val at = System.currentTimeMillis()
            val held = pairs.heldOut { fold, items ->
                val train = counts.without { pairs.foldOf[it] == fold }
                items.map { train.chances(it, alpha, scale, exceptOwn = false) }
            }
            tried += Tried(alpha, scale, held, System.currentTimeMillis() - at)
        }
        val chosen = oneStandardError(tried, { it.held }, compareByDescending<Tried> { it.alpha }.thenBy { it.scale })
        log.info(
            "{} items × {} values, {} values of other facets: {}; chosen α={} s={} in {} ms",
            task.itemCount, task.valueCount, task.contextCount,
            tried.joinToString("; ") { "α=${it.alpha} s=${it.scale}: %.4f ± %.4f".format(it.held.mean, it.held.error) },
            chosen.alpha, chosen.scale, System.currentTimeMillis() - started,
        )
        val report = FitReport(
            settings(chosen), chosen.held.mean, chosen.held.error,
            tried.map { FitTry(settings(it), it.held.mean, it.held.error, it.millis) }, fromScratch = true,
        )
        return Fitted(chosen.alpha, chosen.scale, report)
    }

    override fun unpack(bytes: ByteArray, task: SuggestionTask): FittedSuggester? {
        val input = DataInputStream(ByteArrayInputStream(bytes))
        if (input.readInt() != FORMAT) return null
        return Fitted(input.readDouble(), input.readDouble())
    }

    private class Tried(val alpha: Double, val scale: Double, val held: HeldLoss, val millis: Long)

    private fun settings(t: Tried) = mapOf(ALPHA to t.alpha.toString(), SCALE to t.scale.toString())

    private class Fitted(val alpha: Double, val scale: Double, override val report: FitReport? = null) : FittedSuggester {
        override fun on(task: SuggestionTask): SuggestionScores {
            val counts = Counts(task, SuggestionPairs(task))
            return SuggestionScores { rows -> rows.map { counts.chances(it, alpha, scale, exceptOwn = true) } }
        }

        override fun pack(): ByteArray {
            val bytes = ByteArrayOutputStream()
            DataOutputStream(bytes).use { out ->
                out.writeInt(FORMAT)
                out.writeDouble(alpha)
                out.writeDouble(scale)
            }
            return bytes.toByteArray()
        }
    }

    /**
     * The weights of the pairs of every value, labelled 1 and 0 — of all the works, and of every
     * value of the other facets of the works with it. Every work's pairs are a uniform weight over
     * every value (its lacking ones, when it has any) and the few that differ: the uniform part is
     * summed apart, so a context's map holds only the values its works have, rejected or were given.
     */
    private class Counts private constructor(
        val task: SuggestionTask,
        val pairs: SuggestionPairs,
        /** Of every work, the uniform weight of every value it lacks, and its pairs that differ: value, weight of 0, weight of 1. */
        val uniform: FloatArray,
        val sparse: Array<Triple<IntArray, FloatArray, FloatArray>>,
        val included: (Int) -> Boolean,
    ) {
        constructor(task: SuggestionTask, pairs: SuggestionPairs) : this(task, pairs, uniformOf(task), sparseOf(task, pairs), { true })

        private val m = task.valueCount
        private val n = task.itemCount

        /** Of every value: the weights of 0 and 1 over every work counted. */
        val zero = DoubleArray(m)
        val one = DoubleArray(m)

        /** Of every context: the uniform weight of its works, and the sparse weights of 0 and 1 of the values that differ. */
        val contextUniform = DoubleArray(task.contextCount)
        val contextZero = HashMap<Int, HashMap<Int, Double>>()
        val contextOne = HashMap<Int, HashMap<Int, Double>>()
        val contextWorks = IntArray(task.contextCount)
        var works = 0

        init {
            var uniformTotal = 0.0
            for (i in 0 until n) {
                if (!included(i)) continue
                works++
                uniformTotal += uniform[i]
                val (values, zeros, ones) = sparse[i]
                values.forEachIndexed { k, v ->
                    zero[v] += zeros[k]
                    one[v] += ones[k]
                }
                task.context[i].forEach { c ->
                    contextWorks[c]++
                    contextUniform[c] += uniform[i].toDouble()
                    if (values.isEmpty()) return@forEach
                    val z = contextZero.getOrPut(c) { HashMap() }
                    val o = contextOne.getOrPut(c) { HashMap() }
                    values.forEachIndexed { k, v ->
                        if (zeros[k] != 0f) z.merge(v, zeros[k].toDouble(), Double::plus)
                        if (ones[k] != 0f) o.merge(v, ones[k].toDouble(), Double::plus)
                    }
                }
            }
            for (v in 0 until m) zero[v] += uniformTotal
        }

        /** The counts of the works [excluded] does not name: those of the other folds. */
        fun without(excluded: (Int) -> Boolean) = Counts(task, pairs, uniform, sparse) { !excluded(it) }

        /**
         * The chance of every value of the work [i], by these counts — its own pairs taken out of
         * them when [exceptOwn]: a work is not its own witness.
         */
        fun chances(i: Int, alpha: Double, scale: Double, exceptOwn: Boolean): FloatArray {
            val own = exceptOwn && included(i)
            val (values, zeros, ones) = sparse[i]
            val ownZero = HashMap<Int, Double>()
            val ownOne = HashMap<Int, Double>()
            if (own) values.forEachIndexed { k, v ->
                ownZero[v] = zeros[k].toDouble()
                ownOne[v] = ones[k].toDouble()
            }
            val u = if (own) uniform[i].toDouble() else 0.0
            val total = (works - if (own) 1 else 0).coerceAtLeast(1)
            val n0 = DoubleArray(m)
            val n1 = DoubleArray(m)
            // ln(N₀ᵥ + α) − ln(N₁ᵥ + α): the prior's log odds negated, and the part of every context's term that is the value's alone
            val a = DoubleArray(m)
            for (v in 0 until m) {
                n0[v] = (zero[v] - u - (ownZero[v] ?: 0.0)).coerceAtLeast(0.0)
                n1[v] = (one[v] - (ownOne[v] ?: 0.0)).coerceAtLeast(0.0)
                a[v] = ln(n0[v] + alpha) - ln(n1[v] + alpha)
            }
            val contexts = task.context[i]
            // A context's term of a value none of its works has, rejected or was given — most of them:
            //   ln(α·q) − ln(cu + α·q) + a[v]
            // summed over the contexts at once; the values its works touch corrected after
            val qs = DoubleArray(contexts.size)
            var constant = 0.0
            contexts.forEachIndexed { k, c ->
                val worksWith = (contextWorks[c] - if (own) 1 else 0).coerceAtLeast(0)
                qs[k] = (worksWith + 1.0) / (total + 2.0)
                val cu = (contextUniform[c] - u).coerceAtLeast(0.0)
                constant += ln(alpha * qs[k]) - ln(cu + alpha * qs[k])
            }
            val logit = DoubleArray(m) { v -> -a[v] + scale * (constant + contexts.size * a[v]) }
            contexts.forEachIndexed { k, c ->
                val q = qs[k]
                val cu = (contextUniform[c] - u).coerceAtLeast(0.0)
                val z = contextZero[c]
                val o = contextOne[c]
                val touched = HashSet<Int>()
                z?.keys?.let(touched::addAll)
                o?.keys?.let(touched::addAll)
                touched.addAll(ownZero.keys)
                touched.forEach { v ->
                    val c0 = (cu + (z?.get(v) ?: 0.0) - (ownZero[v] ?: 0.0)).coerceAtLeast(0.0)
                    val c1 = ((o?.get(v) ?: 0.0) - (ownOne[v] ?: 0.0)).coerceAtLeast(0.0)
                    val actual = ln((c1 + alpha * q) / (n1[v] + alpha)) - ln((c0 + alpha * q) / (n0[v] + alpha))
                    val default = ln(alpha * q) - ln(cu + alpha * q) + a[v]
                    logit[v] += scale * (actual - default)
                }
            }
            return FloatArray(m) { (1.0 / (1.0 + exp(-logit[it]))).toFloat() }
        }

        companion object {
            /** Of every work, the weight every value it lacks has: its lacking weight when it has any value and may have every one. */
            // Of a work that may have only some values, the others are counted as lacking too: an
            // approximation of the method's own — what it is judged by ([SuggestionPairs]) is exact
            fun uniformOf(task: SuggestionTask) = FloatArray(task.itemCount) { i ->
                if (task.assigned[i].isNotEmpty()) task.lackWeight.toFloat() else 0f
            }

            /** Of every work, its pairs that differ from the uniform weight: their weights of 0 and of 1. */
            fun sparseOf(task: SuggestionTask, pairs: SuggestionPairs): Array<Triple<IntArray, FloatArray, FloatArray>> {
                val uniform = uniformOf(task)
                return Array(task.itemCount) { i ->
                    val at = pairs.active[i]
                    val keep = at.indices.filter { k -> pairs.label[i][k] > 0f || pairs.weight[i][k] != uniform[i] }
                    val values = IntArray(keep.size) { at[keep[it]] }
                    // A pair labelled 1 weighs as 1, and no longer as one lacking; one labelled 0 weighs its own weight instead of the uniform
                    val zeros = FloatArray(keep.size) { val k = keep[it]; if (pairs.label[i][k] > 0f) -uniform[i] else pairs.weight[i][k] - uniform[i] }
                    val ones = FloatArray(keep.size) { val k = keep[it]; if (pairs.label[i][k] > 0f) pairs.weight[i][k] else 0f }
                    Triple(values, zeros, ones)
                }
            }
        }
    }

    companion object {
        private const val FORMAT = 1
        private const val ALPHA = "α"
        private const val SCALE = "s"

        /** The smoothing tried: a share of a work, one, a few. */
        private val ALPHAS = listOf(0.5, 2.0, 8.0)

        /** The scales of the other values' sum tried: they say much the same of a work many times over. */
        private val SCALES = listOf(0.1, 0.3, 1.0)
    }
}
