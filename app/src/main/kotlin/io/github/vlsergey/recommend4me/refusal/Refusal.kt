package io.github.vlsergey.recommend4me.refusal

import io.github.vlsergey.recommend4me.i18n.I18nKeys
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

private val log = LoggerFactory.getLogger(Refusal::class.java)

/**
 * WHY A REQUEST IS REFUSED, by the key of its words in the interface's standard namespace
 * ("standard:error.<code>"): the server words nothing for the user. The values the words are filled
 * with are named in [params]; the interface keeps the words of every one.
 */
enum class Refusal(val code: String, val params: List<String> = emptyList()) {
    /** The source has no such field (facet, text, number). */
    NO_FIELD("noField", listOf("field")),

    /** A field of the site, never corrected; [params] "field" — the key of its label. */
    SITE_FIELD("siteField", listOf("field")),
    NO_VALUE("noValue"),
    NO_SUCH_WORK("noSuchWork"),
    JOIN_TYPES("joinTypes"),
    JOIN_SELF("joinSelf"),
    BAD_RANGE("badRange", listOf("range")),
    NO_DOWNLOADS("noDownloads"),
    SITE_SILENT("siteSilent", listOf("error")),
    REFRESH_FAILED("refreshFailed"),
    BAD_GRADE("badGrade", listOf("min", "max")),
    BAD_MARK("badMark"),
    BAD_ANSWER("badAnswer"),
    NO_UNIVERSES("noUniverses"),
    NOT_IN_CATALOGUE("notInCatalogue", listOf("universe")),
    CATALOGUE_SILENT("catalogueSilent", listOf("error")),
    NO_SUCH_UNIVERSE("noSuchUniverse"),
    NO_SUCH_TYPE("noSuchType"),
    NO_SITE_TAGS("noSiteTags"),
    TAG_SELF("tagSelf"),
    TAG_ABOVE("tagAbove"),
    TAG_BELOW("tagBelow"),

    /** A tag above or under another through others: not said of the two, of the tags between. */
    TAG_THROUGH("tagThrough"),

    /** A name typed for a facet of the dictionary's entries alone — a universe, a pairing — that is no entry of it; [params] "value". */
    NOT_IN_DICTIONARY("notInDictionary", listOf("value")),

    /** A tag the work has by a tag under it the user confirmed, said not to be the work's; [params] "tag" — that tag's name. */
    TAG_IMPLIED("tagImplied", listOf("tag")),
    NO_SETTING("noSetting", listOf("setting")),
    NO_SCORER("noScorer", listOf("scorer")),
    ACCESS_REFUSED("accessRefused"),
    NO_EXTENSION_FOLDER("noExtensionFolder"),
    ;

    /** The key of its words: "standard:error.noField". */
    val key: String get() = I18nKeys.qualified(I18nKeys.STANDARD, "error.$code")

    /**
     * The request refused with [status] for this reason; [detail] tells it in English for the log,
     * [values] fill the words, by their names ([params]).
     */
    fun refuse(status: HttpStatus, detail: String, vararg values: Pair<String, Any?>): Refused {
        val filled = values.associate { (name, value) -> name to value.toString() }
        check(filled.keys == params.toSet()) { "$code is filled with ${params}, not ${filled.keys}" }
        return Refused(status, this, filled, detail)
    }

    /** The refusal as an RFC 9457 problem: [key] its "code", the values its "params", [detail] for the log. */
    fun problem(status: HttpStatus, detail: String, params: Map<String, String> = emptyMap()): ProblemDetail =
        ProblemDetail.forStatusAndDetail(status, detail).apply {
            setProperty("code", key)
            setProperty("params", params)
        }
}

/** A request refused for [refusal], answered as a problem ([RefusalAnswers]). */
class Refused(val status: HttpStatus, val refusal: Refusal, val params: Map<String, String>, detail: String) : RuntimeException(detail)

/** A refusal answered as application/problem+json with its key and values. */
@RestControllerAdvice
class RefusalAnswers {

    @ExceptionHandler(Refused::class)
    fun refused(e: Refused): ResponseEntity<ProblemDetail> {
        log.info("refused ({}): {}", e.refusal.code, e.message)
        return ResponseEntity.status(e.status).body(e.refusal.problem(e.status, e.message.orEmpty(), e.params))
    }
}
