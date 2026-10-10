package io.github.vlsergey.recommend4me.signal

import io.github.vlsergey.recommend4me.database.ratings.tables.references.SITE_SIGNAL
import io.github.vlsergey.recommend4me.source.SiteSignals
import org.jooq.DSLContext
import java.time.Instant

/** The user's own actions on a site as its pages show them, kept with the user's grades; shown, never read by the model. */
class SignalRepository(private val db: DSLContext) : SiteSignals {

    override fun set(itemId: String, signal: String, value: String?) {
        val known = db.select(SITE_SIGNAL.CONTENT).from(SITE_SIGNAL).where(SITE_SIGNAL.ITEM_ID.eq(itemId), SITE_SIGNAL.SIGNAL.eq(signal)).fetchOne(SITE_SIGNAL.CONTENT)
        if (known == value) return
        if (value == null) {
            db.deleteFrom(SITE_SIGNAL).where(SITE_SIGNAL.ITEM_ID.eq(itemId), SITE_SIGNAL.SIGNAL.eq(signal)).execute()
        } else {
            db.insertInto(SITE_SIGNAL)
                .set(SITE_SIGNAL.ITEM_ID, itemId).set(SITE_SIGNAL.SIGNAL, signal).set(SITE_SIGNAL.CONTENT, value.take(500))
                .set(SITE_SIGNAL.SEEN_AT, Instant.now())
                .onDuplicateKeyUpdate().set(SITE_SIGNAL.CONTENT, value.take(500)).set(SITE_SIGNAL.SEEN_AT, Instant.now())
                .execute()
        }
    }

    override fun withSignal(signal: String): Map<String, String> =
        db.select(SITE_SIGNAL.ITEM_ID, SITE_SIGNAL.CONTENT).from(SITE_SIGNAL).where(SITE_SIGNAL.SIGNAL.eq(signal))
            .fetch().associate { it.value1()!! to it.value2()!! }

    /** Every signal of every item: item to signal to value. */
    fun all(): Map<String, Map<String, String>> =
        db.select(SITE_SIGNAL.ITEM_ID, SITE_SIGNAL.SIGNAL, SITE_SIGNAL.CONTENT).from(SITE_SIGNAL).fetch()
            .groupBy({ it.value1()!! }, { it.value2()!! to it.value3()!! }).mapValues { it.value.toMap() }

    /** Removes every value of a signal not among [signals]; how many were removed of each. */
    fun keepOnly(signals: Collection<String>): Map<String, Int> {
        val gone = db.select(SITE_SIGNAL.SIGNAL).from(SITE_SIGNAL).where(SITE_SIGNAL.SIGNAL.notIn(signals)).fetch(SITE_SIGNAL.SIGNAL)
            .groupingBy { it!! }.eachCount()
        if (gone.isNotEmpty()) db.deleteFrom(SITE_SIGNAL).where(SITE_SIGNAL.SIGNAL.notIn(signals)).execute()
        return gone
    }

    fun ofItem(itemId: String): Map<String, String> =
        db.select(SITE_SIGNAL.SIGNAL, SITE_SIGNAL.CONTENT).from(SITE_SIGNAL).where(SITE_SIGNAL.ITEM_ID.eq(itemId))
            .fetch().associate { it.value1()!! to it.value2()!! }

    /** Every signal of the item removed from the library. */
    fun forget(itemId: String) {
        db.deleteFrom(SITE_SIGNAL).where(SITE_SIGNAL.ITEM_ID.eq(itemId)).execute()
    }
}
