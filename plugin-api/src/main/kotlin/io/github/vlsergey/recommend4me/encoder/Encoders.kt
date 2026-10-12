package io.github.vlsergey.recommend4me.encoder

import java.awt.image.BufferedImage

/** What a text is to an encoder trained with prefixes: a query searched for, or a passage searched in. */
enum class TextKind { QUERY, PASSAGE }

/**
 * A text encoder: texts of any language to unit vectors in one space. Every vector is stored with
 * the encoder's [id]; vectors of another encoder are of another space and are made again.
 */
interface TextEncoder {
    val id: String
    val dim: Int

    /** Whether its files are in place; an encoder that is not ready is skipped. */
    fun ready(): Boolean

    /** Vectors of texts of any length, of unit length; a blank text gets null. */
    fun encode(texts: List<String>, kind: TextKind = TextKind.PASSAGE): List<FloatArray?>

    /**
     * A vector for every window of every text — the pieces the encoder reads whole — so a long
     * text (a chapter) is a set of vectors rather than their average.
     */
    fun encodeWindows(texts: List<String>): List<List<FloatArray>>
}

/**
 * A CROSS-ENCODER (a reranker): reads two texts TOGETHER and says how much the second answers the
 * first. Two vectors compared by their cosine are two texts each read alone, squeezed into a few
 * hundred numbers before they meet; read together, a trope, a kind of hero, a turn of the plot told
 * in other words is seen. Its scores are the model's own numbers: only their order means anything,
 * and only among the scores of one first text.
 */
interface TextReranker {
    /** Stable: kept with every score; scores of another reranker are made again. */
    val id: String

    /** Whether its files are in place; a reranker that is not ready is skipped. */
    fun ready(): Boolean

    /**
     * The texts cut into the reranker's pieces, on every core: the costly part done on the CPU, once
     * for every pair a text is in — a pass reads every work against every graded one.
     */
    fun prepare(texts: List<String>): PreparedTexts

    /** The score of every pair of the [texts] prepared, the first of pair k at [first]`[k]`, the second at [second]`[k]`. */
    fun score(texts: PreparedTexts, first: IntArray, second: IntArray): FloatArray

    /**
     * The score of every text of [texts] answering the [query] — a line searched for against the
     * texts of works: the line is short, and a text is read as far as what the line leaves of the
     * reranker's reach, not cut to an equal share of it as a pair of two texts is.
     */
    fun rank(query: String, texts: List<String>): FloatArray = score(texts.map { query to it })

    /** The score of every pair (first, second) of texts. */
    fun score(pairs: List<Pair<String, String>>): FloatArray {
        val distinct = pairs.flatMap { it.toList() }.distinct()
        val at = distinct.withIndex().associate { (i, t) -> t to i }
        return score(prepare(distinct), IntArray(pairs.size) { at.getValue(pairs[it].first) }, IntArray(pairs.size) { at.getValue(pairs[it].second) })
    }
}

/** Texts made ready for a reranker by [TextReranker.prepare]: what it reads of them, by their order. */
interface PreparedTexts {
    val size: Int
}

/** A picture made ready for an encoder: decoded, resized, cut — the costly part, done off the encoder's thread. */
interface PreparedPicture

/** A picture encoder: pictures to unit vectors. */
interface ImageEncoder {
    val id: String
    val dim: Int

    fun ready(): Boolean

    /** Runs on the download threads, in parallel. */
    fun prepare(image: BufferedImage): PreparedPicture

    fun encode(pictures: List<PreparedPicture>): List<FloatArray>
}
