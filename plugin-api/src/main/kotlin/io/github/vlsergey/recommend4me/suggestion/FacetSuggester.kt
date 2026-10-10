package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.matrix.Matrix
import java.util.Collections
import java.util.IdentityHashMap

/**
 * One input of the items, as the model of the grades reads them too: a vector per item, as it was
 * encoded — a text (the title, the description, the notes, a line of the site), a picture, a set
 * of vectors made one (the reviews, the windows of the chapters, the screenshots) — and whether the
 * item has it; a row of zeros for an item without. [name]: stable, the weights learnt of the input
 * are kept by it.
 */
class InputBlock(val name: String, val vectors: Matrix, val present: BooleanArray) {
    init {
        require(present.size == vectors.rows) { "$name: ${vectors.rows} rows, ${present.size} marks" }
    }
}

/**
 * A text that names a value, learnt from as a work would be: one of the names of a character, of a
 * universe (its own, its characters'), of a pairing — "Гермиона", "Hermione Granger" — read as a
 * text of a work, the work's every text, which names the [value] and none of the other values
 * [among] (the other characters of its universe). The works the model learns from are few; the
 * names of the values tell it how a text that names one looks. The name encoded as a text of a work
 * is, is its row of the task's [SuggestionTask.hintTexts].
 *
 * [among]: the values the name is judged among, its own with them, in ascending order — one array
 * for every name judged among the same values: the names of a universe's thousands of characters
 * share one, not thousands of copies of it.
 */
class NameHint(val value: Int, val among: IntArray)

/**
 * A way the values of a facet fall into groups alike in what goes with them — the pairings of two
 * men, of two women, of a man and a woman; the characters of either sex; the pairings of one
 * character. What is learnt of a group holds for every value in it, a value never seen with a
 * work's tags too. [groups]: the groups by their keys — a group is the same group in every task by
 * its key, whatever other groups a task has. [of]: the groups of every value of the task, indices
 * of [groups] — none for a value of no known group, several for a value of several: a pairing is
 * in the groups of both its characters.
 */
class ValueGrouping(val name: String, val groups: List<String>, val of: List<IntArray>)

/**
 * Everything a suggester knows of one facet of one source ([FacetDef.suggest][io.github.vlsergey.recommend4me.source.FacetDef.suggest],
 * [FacetDef.infer][io.github.vlsergey.recommend4me.source.FacetDef.infer]): every item — every input
 * of it, the facet's values it has, the values of its other facets — and every value that may be
 * suggested, with the vector of its name.
 *
 * Rows of the inputs and of the lists are items, rows of [values] values; [assigned], [confirmed]
 * and [rejected] hold indices of [values] ([valueKeys]), [context] indices of [contextKeys].
 */
class SuggestionTask(
    /** The id of every item, in the order of the rows: an item is held out of what is learnt by it, the same way every time. */
    val itemIds: List<String>,
    val inputs: List<InputBlock>,
    /** One row per value: the vector of its name as a query, of unit length; zeros when not encoded. */
    val values: Matrix,
    /** The key of every value, in the order of [values]: what is learnt of a value is kept by it. */
    val valueKeys: List<String>,
    /** The values each item has now: the site's, with the user's corrections applied. */
    val assigned: List<IntArray>,
    /** The values the user said the item has: added by hand or accepted when suggested. */
    val confirmed: List<IntArray>,
    /** The values the user said the item does not have: taken from it, or rejected when suggested. */
    val rejected: List<IntArray>,
    /** The values of the item's other facets — its fandom, its tags, its author: indices of [contextKeys]. */
    val context: List<IntArray>,
    /** Every value of the other facets, as "<facet>\u0001<value>": what is learnt of one is kept by it. */
    val contextKeys: List<String>,
    /**
     * How many times the item's own texts name each value — a character mentioned in the chapters,
     * two named in one paragraph: value index to count, the values not named left out; null for an
     * item whose texts were not counted, an empty map for one that names none.
     */
    val mentions: List<Map<Int, Int>?> = List(assigned.size) { null },
    /**
     * The values each item may have at all — a work's characters are of its universes — null for
     * an item that may have any. A value outside them is no example of the item: not one it lacks,
     * one it cannot have; its chance is still worked out.
     */
    val allowed: List<IntArray?> = List(assigned.size) { null },
    /** The groupings of the values, none for a facet whose values are not known to fall into groups. */
    val groupings: List<ValueGrouping> = emptyList(),
    /**
     * How much a value an item lacks counts as one it does not have, against 1 for one it has and
     * one the user rejected: 1 when an item's values are all it has; less when they are what a site
     * happened to write — a tag the site has raises the chance of the work's tag, its absence says
     * little of the work.
     */
    val lackWeight: Double = 1.0,
    /**
     * The values the site gives each item as the facet's examples, from a facet of its own
     * ([FacetDef.examplesFrom][io.github.vlsergey.recommend4me.source.FacetDef.examplesFrom]) —
     * among the [assigned] too. Of the item's own word this alone is a witness of its values: a
     * value the site gives the work is likelier the work's, however rare; empty for a facet without.
     */
    val examples: List<IntArray> = List(assigned.size) { IntArray(0) },
    /** The names of the values as texts that name them ([NameHint]); none for a facet whose values are not named in texts. */
    val hints: List<NameHint> = emptyList(),
    /**
     * One row per name of [hints], in their order: the name encoded as a text of a work is, of the
     * length of the task's text inputs. A matrix, not a vector of each: thousands of names of
     * thousands of numbers, read into the memory a suggester reads them from.
     */
    val hintTexts: Matrix = Matrix(0, 0),
) {
    val itemCount: Int get() = assigned.size
    val valueCount: Int get() = values.rows
    val contextCount: Int get() = contextKeys.size

    init {
        require(itemIds.size == itemCount) { "${itemIds.size} ids of $itemCount items" }
        require(inputs.all { it.vectors.rows == itemCount }) { "Inputs of ${inputs.map { "${it.name} ${it.vectors.rows}×${it.vectors.cols}" }}, $itemCount items" }
        require(inputs.map { it.name }.toSet().size == inputs.size) { "Inputs named twice: ${inputs.map { it.name }}" }
        require(valueKeys.size == valueCount) { "${valueKeys.size} keys of $valueCount values" }
        require(context.all { c -> c.all { it in 0 until contextCount } }) { "A context out of the $contextCount keys" }
        require(
            confirmed.size == itemCount && rejected.size == itemCount && context.size == itemCount && mentions.size == itemCount && allowed.size == itemCount,
        ) {
            "$itemCount assigned, ${confirmed.size} confirmed, ${rejected.size} rejected, ${context.size} contexts, ${mentions.size} mentions, ${allowed.size} allowed"
        }
        require(groupings.all { g -> g.of.size == valueCount && g.of.all { groups -> groups.all { it in g.groups.indices } } }) { "A grouping not of the $valueCount values" }
        require(lackWeight > 0 && lackWeight <= 1) { "A lacking value weighs $lackWeight" }
        require(examples.size == itemCount) { "$itemCount items, ${examples.size} lists of examples" }
        require(hintTexts.rows == hints.size) { "${hints.size} names, ${hintTexts.rows} texts of them" }
        // Every array of values checked once, however many names share it
        val among = hints.mapTo(Collections.newSetFromMap(IdentityHashMap())) { it.among }
        require(among.all { a -> a.all { it in 0 until valueCount } && (1 until a.size).all { a[it - 1] < a[it] } }) {
            "Names judged among values not of the task, or not in order"
        }
        require(hints.all { h -> h.value in 0 until valueCount && h.among.binarySearch(h.value) >= 0 }) { "A name judged among values without its own" }
    }
}

/**
 * A way of working out the values of a facet an item should have from its texts and its other
 * values. Learns from every item of the source: the values the site and the user gave them are
 * the examples. What the user said of a work is the application's to apply over the chances.
 */
interface FacetSuggester {
    /** Stable: kept with the fitted state. */
    val id: String

    /**
     * Learns from every item of [task]; null when there is nothing to learn from — no item has a
     * value, or every item has all. [previous]: the state fitted last on the facet, unpacked against
     * [task], to go on from — a few answers or works more move the fitted state a little, and the
     * fitting is a short walk from it rather than a search from scratch; null: from scratch.
     */
    fun fit(task: SuggestionTask, previous: FittedSuggester? = null): FittedSuggester?

    /** A state kept by [FittedSuggester.pack]; null when it does not fit tasks of [task]'s shape — it is fitted again. */
    fun unpack(bytes: ByteArray, task: SuggestionTask): FittedSuggester?
}

interface FittedSuggester {
    /**
     * Readies the scoring of the items of [task] — of the task it was fitted on, or of a later one
     * of the same facet: what all the items share is worked out once here.
     */
    fun on(task: SuggestionTask): SuggestionScores

    fun pack(): ByteArray
}

fun interface SuggestionScores {
    /**
     * For each of [rows] (items of the task), the chance of every value — an array as long as the
     * task has values. A value the item has is judged as if the item did not say so: a low chance
     * marks a value the site gives it that does not fit.
     */
    fun of(rows: IntArray): List<FloatArray>
}
