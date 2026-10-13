package io.github.vlsergey.recommend4me.source

import io.github.vlsergey.recommend4me.i18n.Label

/**
 * A running job of a source — a scrape — as the interface shows it: the phase, how far it got,
 * how many items are new; and the user's wish to stop it.
 */
interface Job {
    /** A new phase: the progress starts again. */
    fun phase(name: String, total: Int = 0, processed: Int = 0)

    fun progress(processed: Int, total: Int? = null)

    /** More items came in new or with a new version. */
    fun found(newItems: Int)

    /** One thing failed and the job goes on. */
    fun error(message: String)

    /** What the user should know at the end, in every language of the interface: "the cookie was not accepted". */
    fun message(text: Label?)

    /** Whether the site saw the user's session. */
    fun session(loggedIn: Boolean?)

    val cancelled: Boolean

    /** Throws [JobCancelled] when the user asked to stop. */
    fun checkCancelled() {
        if (cancelled) throw JobCancelled()
    }

    /**
     * Lets the application catch up in the middle of a long job: the new texts encoded, the model
     * retrained — so the recommendations grow with the download instead of waiting for its end.
     */
    fun checkpoint()

    /** A value the job keeps across restarts (the next page of a catalogue). */
    fun state(key: String): String?

    fun state(key: String, value: String?)
}

/** Thrown to stop a job the user cancelled. */
class JobCancelled : RuntimeException("cancelled")
