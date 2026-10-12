package io.github.vlsergey.recommend4me.interest

/**
 * WHETHER A WORK IS INTERESTING TO READ AT ALL — beside how good it is, not instead of it: a work
 * the model would grade 10 may be of no interest to the user. The list sorted by the prediction
 * puts the works by the prediction times the chance of interest.
 *
 * What the user said decides: the mark first; without one, a grade of 4 or 5 says the work was
 * interesting (the user's rule). A lower grade says nothing either way. Every other work has the
 * chance the model of interest gives it, or none without one.
 */
object Interest {
    /** The lowest grade that says the work was interesting. */
    const val MIN_GRADE = 4

    /** What the user said of the work: interesting, not, or nothing; [grade] the latest grade of it, of any version. */
    fun label(mark: Boolean?, grade: Int?): Boolean? = mark ?: if (grade != null && grade >= MIN_GRADE) true else null

    /** The chance of interest the list sorts by: 1 or 0 for what the user said, else the model's [modelChance]. */
    fun chance(mark: Boolean?, grade: Int?, modelChance: Double?): Double? =
        label(mark, grade)?.let { if (it) 1.0 else 0.0 } ?: modelChance
}

/** The user marked or unmarked a work of a content type as interesting. */
data class InterestChanged(val type: String, val source: String, val itemId: String)
