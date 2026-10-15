package io.github.vlsergey.recommend4me.model

import io.github.vlsergey.recommend4me.interest.InterestChanged
import io.github.vlsergey.recommend4me.i18n.I18nKeys
import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.likeness.Likenesses
import io.github.vlsergey.recommend4me.likeness.MarkLikeness
import io.github.vlsergey.recommend4me.likeness.TypeLikeness
import io.github.vlsergey.recommend4me.likeness.WorkLikeness
import io.github.vlsergey.recommend4me.likeness.WorkLikenessChanged
import io.github.vlsergey.recommend4me.mark.MarksChanged
import io.github.vlsergey.recommend4me.matrix.Matrix
import io.github.vlsergey.recommend4me.picture.PictureRepository
import io.github.vlsergey.recommend4me.plugin.Plugins
import io.github.vlsergey.recommend4me.rating.Grades
import io.github.vlsergey.recommend4me.rating.GradesChanged
import io.github.vlsergey.recommend4me.review.ReviewRepository
import io.github.vlsergey.recommend4me.review.StoredReview
import io.github.vlsergey.recommend4me.scorer.FittedScorer
import io.github.vlsergey.recommend4me.scorer.RankingTask
import io.github.vlsergey.recommend4me.scorer.Scorer
import io.github.vlsergey.recommend4me.settings.Settings
import io.github.vlsergey.recommend4me.setvector.SetKind
import io.github.vlsergey.recommend4me.setvector.SetVectorsChanged
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.source.TypeStore
import io.github.vlsergey.recommend4me.correction.FacetAnswered
import io.github.vlsergey.recommend4me.suggestion.FacetChancesChanged
import io.github.vlsergey.recommend4me.tag.TagsChanged
import io.github.vlsergey.recommend4me.textvector.PhraseVectors
import io.github.vlsergey.recommend4me.workload.Work
import io.github.vlsergey.recommend4me.workload.Workload
import io.github.vlsergey.recommend4me.vector.Vectors
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.readValue
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.abs

private val log = LoggerFactory.getLogger(Recommendations::class.java)

/** A trained model as kept: its description and its scorer, read for one operation. */
class TrainedModel(val meta: ModelMeta, val scorer: FittedScorer) {
    val layout = meta.layout()
    val scale = meta.scale()

    fun scores(m: Matrix): FloatArray = scorer.scores(m)

    fun score(input: ItemInput): Double = scale.score(scorer.scores(layout.matrix(listOf(input)))[0].toDouble())
}

/** A trained model of interest as kept, read for one operation. */
class TrainedInterest(val meta: InterestMeta, val scorer: FittedScorer) {
    val layout = meta.layout()

    /** The chance of interest of every one of [inputs], 0..1. */
    fun chances(inputs: List<ItemInput>): List<Double> = scorer.scores(layout.matrix(inputs)).map { meta.chance(it.toDouble()) }
}

/**
 * Trains the model of every content type on the user's grades and keeps a score of 0..10 for every
 * item. Training is cheap enough — a few thousand ratings of a few thousand numbers — to be redone
 * from scratch after the grades change and after every sync rather than updated incrementally.
 *
 * NOTHING IS KEPT IN MEMORY between operations: the model is read from its database when it is
 * needed, the catalogue loaded at the start of a training and dropped at its end.
 */
@Service
class Recommendations(
    private val stores: Stores,
    private val plugins: Plugins,
    private val reader: CatalogueReader,
    private val likenesses: Likenesses,
    private val phrases: PhraseVectors,
    private val settings: Settings,
    private val json: JsonMapper,
    private val workload: Workload,
) {
    private val trainingNow = ConcurrentHashMap.newKeySet<String>()
    private val pending = ConcurrentHashMap<String, PendingTraining>()

    /** How long the last training of every type took, ms: how long a stream of background changes is let to settle. */
    private val lastTraining = ConcurrentHashMap<String, Long>()
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "model-training").apply { isDaemon = true } }
    private val heldOutWorker = Executors.newSingleThreadExecutor { r -> Thread(r, "held-out").apply { isDaemon = true } }

    private fun type(id: String): TypeStore = stores.type(id) ?: error("No such content type: $id")

    /**
     * Training is running now. One planned after a change, or waiting its turn in the queue of the
     * computations, is not: the queue tells it ([Workload]), and the model shows no spinner meanwhile.
     */
    fun training(type: String): Boolean = type in trainingNow

    /**
     * Retrains after a change. A change of the user's ([byUser]: a grade, a mark, a tag, an answer)
     * is learnt shortly after the last of a quick series of them. A change the background work makes
     * — the suggester's chances of every facet of every source, the reranker's likeness chunk after
     * chunk, the pictures — comes in long streams: learnt after a minute of quiet, or twice the last
     * training, never later than [MAX_WAIT_MS] after the first of the stream; a training the user
     * waits for is never put off by it. The [reason] — a key the interface words — is told in the
     * queue of the works ([Workload.plan]) till the training begins.
     */
    fun scheduleRetrain(type: String, reason: String, byUser: Boolean = true, quietMs: Long = USER_QUIET_MS) {
        val now = System.currentTimeMillis()
        pending.compute(type) { _, old ->
            val waiting = old?.takeIf { !it.future.isDone }
            if (!byUser && waiting?.byUser == true) {
                workload.plan(trainingWork(type), Instant.ofEpochMilli(waiting.due), reason)
                return@compute waiting
            }
            waiting?.future?.cancel(false)
            val first = waiting?.firstAt ?: now
            val due = if (byUser) now + quietMs else minOf(now + maxOf(MACHINE_QUIET_MS, 2 * (lastTraining[type] ?: 0L)), first + MAX_WAIT_MS)
            workload.plan(trainingWork(type), Instant.ofEpochMilli(due), reason)
            val future = scheduler.schedule({
                try {
                    retrainAndScore(type)
                } catch (e: Exception) {
                    log.error("{}: background training failed", type, e)
                }
            }, (due - now).coerceAtLeast(0), TimeUnit.MILLISECONDS)
            PendingTraining(future, first, due, byUser)
        }
    }

    /**
     * The training the user asked for — by the button, or by choosing a scorer — planned to begin at
     * once, after the works before it; the request that asked does not wait for it ([Requests]).
     */
    fun trainSoon(type: String) = scheduleRetrain(type, "asked", quietMs = 0)

    /** A training scheduled: since the first change it waits on, when it is due, and whether the user waits for it. */
    private class PendingTraining(val future: ScheduledFuture<*>, val firstAt: Long, val due: Long, val byUser: Boolean)

    private fun trainingWork(typeId: String) = Work("train", type = typeId)

    @EventListener
    fun gradesChanged(event: GradesChanged) {
        settings.set("model.${event.type}.gradesChangedAt", System.currentTimeMillis().toString())
        heldOutChanged(event.source, event.itemId)
        scheduleRetrain(event.type, "grade")
    }

    @EventListener
    fun marksChanged(event: MarksChanged) = scheduleRetrain(event.type, "mark")

    @EventListener
    fun interestChanged(event: InterestChanged) = scheduleRetrain(event.type, "interest")

    /** Tags merged, or put above and under others: the works' tags, features of the works, changed. */
    @EventListener
    fun tagsChanged(event: TagsChanged) = scheduleRetrain(event.type, "tags")

    /** The reranker read more works against the graded ones: features of the works. */
    @EventListener
    fun workLikenessChanged(event: WorkLikenessChanged) = scheduleRetrain(event.type, "likeness", byUser = false)

    /** The user said what a work has or has not: its features, the user's word over the model's. */
    @EventListener
    fun answered(event: FacetAnswered) {
        heldOutChanged(event.source, event.itemId)
        scheduleRetrain(event.type, "answer")
    }

    /** The model gave works other pairings, characters: features of the works. */
    @EventListener
    fun chancesChanged(event: FacetChancesChanged) = scheduleRetrain(stores.typeOf(event.source).id, "chances", byUser = false)

    @EventListener
    fun setVectorsChanged(event: SetVectorsChanged) {
        log.info("{}: {} items have new set vectors: retraining", event.type, event.items)
        scheduleRetrain(event.type, "setVectors", byUser = false)
    }

    /** A model that could not be read, or none, is trained on start where there are grades. */
    @EventListener(ApplicationReadyEvent::class)
    fun trainIfMissing() {
        stores.types.forEach { t ->
            if (load(t) == null && t.sources.any { it.ratings.count() > 0 }) scheduleRetrain(t.id, "noModel")
        }
    }

    fun retrainAndScore(typeId: String): ModelSummary = workload.run(trainingWork(typeId)) {
        val type = type(typeId)
        trainingNow += typeId
        try {
            val started = System.currentTimeMillis()
            val catalogue = reader.read(type)
            val loaded = System.currentTimeMillis()
            val model = retrain(catalogue)
            // Of no use without the scores it multiplies
            val interest = if (model != null) retrainInterest(catalogue) else null
            val trained = System.currentTimeMillis()
            if (model != null) scoreAll(catalogue, model, interest)
            lastTraining[typeId] = System.currentTimeMillis() - started
            log.info(
                "{}: retrained and every item scored in {} ms: read {}, trained {}, scored {}",
                typeId, System.currentTimeMillis() - started, loaded - started, trained - loaded, System.currentTimeMillis() - trained,
            )
        } finally {
            trainingNow -= typeId
        }
        summary(typeId)
    }

    /** Re-scores every item of the type with its current model. */
    fun scoreAll(typeId: String) = workload.run(Work("score", type = typeId)) {
        val type = type(typeId)
        val model = load(type) ?: return@run
        scoreAll(reader.read(type, withLikeness = false), model, loadInterest(type))
    }

    /** The current model of a type, read now; null without one, or when it cannot be read. */
    fun load(type: TypeStore): TrainedModel? = try {
        type.models.load()?.let { stored ->
            val scorer = plugins.scorers.firstOrNull { it.id == stored.scorer } ?: return null
            TrainedModel(json.readValue<ModelMeta>(stored.meta), scorer.unpack(stored.weights))
        }
    } catch (e: Exception) {
        log.warn("{}: the stored model cannot be read and will be retrained: {}", type.id, e.message)
        null
    }

    /** The current model of interest of a type, read now; null without one, or when it cannot be read. */
    fun loadInterest(type: TypeStore): TrainedInterest? = try {
        type.models.loadInterest()?.let { stored ->
            val scorer = plugins.scorers.firstOrNull { it.id == stored.scorer } ?: return null
            TrainedInterest(json.readValue<InterestMeta>(stored.meta), scorer.unpack(stored.weights))
        }
    } catch (e: Exception) {
        log.warn("{}: the stored model of interest cannot be read and will be retrained: {}", type.id, e.message)
        null
    }

    // --- Training ---

    /**
     * The graded items as the model sees them, a row a grade — then the ungraded items of linked
     * works, rows without a grade; null when there are too few grades to train on.
     */
    private class Prepared(
        val used: List<TypedRating>,
        val grades: IntArray,
        val works: LongArray,
        /** The item of every row: two items of one work are compared as equal. */
        val items: LongArray,
        val perItem: List<ItemInput>,
        val layout: FeatureLayout,
        val x: Matrix,
        /** The input of every row, as [x] holds it. */
        val rows: List<ItemInput>,
        /** The items of every work among the rows. */
        val keysOfWork: Map<Long, List<ItemKey>>,
        val spreads: Map<String, VectorSpread>,
        val numbers: Map<String, NumberSpread>,
        /** The rows as a fold sees them: the marks of the works under test not counted; null when nothing depends on them. */
        val xFor: ((Set<Long>) -> Matrix)?,
    ) {
        val everyRow = IntArray(used.size) { it }
        val task = RankingTask(x, grades, works, items)
    }

    /** The rows of the user's grades, a row a grade, each with the grade of an earlier version before it. */
    private fun prepare(catalogue: Catalogue): Prepared? {
        val previousForRating = CatalogueReader.previousGradesOfRatings(catalogue.ratings)
        return prepare(catalogue, catalogue.ratings.filter { it.key in catalogue.byKey }, "grades") { previousForRating[it] }
    }

    /**
     * The rows of [used] — the user's grades, or the works labelled interesting or not as grades
     * [INTERESTING] and [NOT_INTERESTING] — each with the grade of an earlier version [previousOf]
     * gives it, and the rows of the linked items without one.
     */
    private fun prepare(catalogue: Catalogue, used: List<TypedRating>, what: String, previousOf: (TypedRating) -> Int?): Prepared? {
        val likeness = catalogue.likeness
        val previousForCurrent = CatalogueReader.previousGradesOfCurrentVersions(catalogue.ratings, catalogue::versionOf)
        val clusters = catalogue.works
        val grades = IntArray(used.size) { used[it].grade }
        if (used.size < MIN_RATINGS || grades.distinct().size < 2) {
            log.info("{}: not enough {} to train: {} of {} values", catalogue.type.id, what, used.size, grades.distinct().size)
            return null
        }
        // An item of a linked work the user did not grade has no grade, but it is the same work as
        // the others: a row without a grade, compared to them as an equal — and so put in order with them
        val gradedKeys = used.map { it.key }.toSet()
        val ungraded = catalogue.items.filter { clusters.linked(it.key) && it.key !in gradedKeys }
        val rows = used.map { r ->
            CatalogueReader.inputOf(catalogue.byKey.getValue(r.key), catalogue.vectorsOf(r.key), previousOf(r))
        } +ungraded.map { CatalogueReader.inputOf(it, catalogue.vectorsOf(it.key), previousForCurrent[it.key]) }
        // The current input of every graded item: what the vocabulary and the importances are counted on
        val perItem = used.map { it.key }.distinct().map { key ->
            CatalogueReader.inputOf(catalogue.byKey.getValue(key), catalogue.vectorsOf(key), previousForCurrent[key])
        }
        // Of every work: its blocks' as counted when the catalogue was read, the likeness to the marks' counted here
        val spreads = catalogue.spreads + VectorSpread.of { action ->
            likeness.pictures.forEach { _, v -> action(MarkLikeness.PICTURES, v) }
            likeness.reviews.forEach { _, v -> action(MarkLikeness.REVIEWS, v) }
            likeness.works.forEach { _, v -> action(WorkLikeness.WORKS, v) }
        }
        val numbers = FeatureLayout.numberSpreads(catalogue.items.map { ItemInput(it.key, emptyMap(), emptySet(), it.numeric) })
        val layout = FeatureLayout.of(perItem, spreads, numbers)
        val x = layout.matrix(rows)
        val works = LongArray(rows.size) { clusters.work(rows[it].key) }
        val items = LongArray(rows.size) { Vectors.keyOf(rows[it].key.toString()) }
        val keysOfWork = rows.indices.groupBy({ works[it] }, { rows[it].key })
        // The likeness of a graded item to the marks of the works under test would tell the fold what it is tested on
        val ofFold = HashMap<Set<Long>, Matrix>()
        val xFor = if (likeness.empty) null else { underTest: Set<Long> ->
            synchronized(ofFold) {
                ofFold.getOrPut(underTest) {
                    val excluded = underTest.flatMap { keysOfWork[it].orEmpty() }.toSet()
                    layout.matrix(rows.map { input ->
                        input.withVectors(input.vectors - MARK_KEYS + likeness.vectors(input.key, excluded))
                    })
                }
            }
        }
        return Prepared(used, grades, works, items, perItem, layout, x, rows, keysOfWork, spreads, numbers, xFor)
    }

    /** The scorer fitted on all grades with [parameter], and its scale: the ladder from [measured]'s out-of-fold scores. */
    private fun scaled(scorer: Scorer, p: Prepared, parameter: Double, measured: RankingCandidate?): Pair<FittedScorer, Scale> {
        val fitted = scorer.fit(p.task, p.everyRow, parameter)
        val own = fitted.scores(p.x, p.grades.size).map { it.toDouble() }
        val (mean, sd) = CrossValidation.standardisation(own)
        // Too few works for folds: the ladder of the training scores, too wide but the only one there is
        val scores = measured?.scores ?: DoubleArray(own.size) { (own[it] - mean) / sd }
        return fitted to Scale(mean, sd, CrossValidation.ladder(p.grades, scores))
    }

    private fun retrain(catalogue: Catalogue): TrainedModel? {
        val began = System.currentTimeMillis()
        val type = catalogue.type
        val scorer = plugins.scorer(type.id) ?: run {
            log.warn("{}: no scorer plugin: the works are not ranked", type.id)
            return null
        }
        val p = prepare(catalogue)
        if (p == null) {
            type.models.forget()
            return null
        }
        val started = System.currentTimeMillis()
        val candidates = if (p.perItem.size >= CrossValidation.MIN_WORKS) CrossValidation.measureAll(p.task, scorer, p.xFor) else emptyList()
        val chosen = CrossValidation.choose(candidates)
        val parameter = chosen?.parameter ?: scorer.defaultParameter
        log.info(
            "{}: {} values of the {} parameter measured on {} grades and {} ungraded linked items × {} features in {} ms, chosen {}",
            type.id, candidates.size, scorer.id, p.used.size, p.task.ungraded.count(), p.layout.width,
            System.currentTimeMillis() - started, parameter,
        )
        val (fitted, scale) = scaled(scorer, p, parameter, chosen)
        val share = categoricalShare(p.layout, catalogue)
        val provisional = ModelMeta(
            scorer = scorer.id, parameter = parameter, categorical = p.layout.categorical, categoricalShare = share,
            vectorCenters = p.spreads.mapValues { it.value.center }, vectorSpreads = p.spreads.mapValues { it.value.spread },
            numbers = p.numbers, scoreMean = scale.mean, scoreSd = scale.sd, ladder = scale.ladder,
            trainedAtMillis = System.currentTimeMillis(), ratingCounts = counts(p.grades),
            metrics = chosen?.metrics, candidates = candidates.map { it.result() },
            topPositive = emptyList(), topNegative = emptyList(),
            textEncoder = plugins.textEncoder()?.id, imageEncoder = plugins.imageEncoder()?.id,
        )
        val model = TrainedModel(provisional, fitted)
        val importance = importance(model, p.perItem)
        val meta = provisional.copy(
            topPositive = importance.filter { it.contribution > 0 }.take(TOP_FEATURES),
            topNegative = importance.filter { it.contribution < 0 }.reversed().take(TOP_FEATURES),
            trainMillis = System.currentTimeMillis() - began,
        )
        type.models.save(scorer.id, Instant.ofEpochMilli(meta.trainedAtMillis), json.writeValueAsString(meta), fitted.pack())
        type.models.replaceHeldOut(heldOutScores(p, chosen, scale))
        return TrainedModel(meta, fitted)
    }

    /**
     * Every work the user marked of interest or not ([Interest]): the marks alone, no grade taken for
     * one. A row a work, at the time it was marked.
     */
    private fun interestRows(catalogue: Catalogue): List<TypedRating> =
        catalogue.items.mapNotNull { item ->
            val mark = catalogue.interest[item.key] ?: return@mapNotNull null
            TypedRating(item.key, item.version, if (mark.interesting) INTERESTING else NOT_INTERESTING, mark.markedAt)
        }

    /**
     * THE MODEL OF INTEREST: the type's scorer learns to put the works the user found interesting
     * above the ones not, on the same features as the grades, its parameter chosen by the same
     * cross-validation over works; Platt's scaling of the out-of-fold scores makes its score a
     * chance. None — and no chances — while there are too few works of either kind for folds.
     */
    private fun retrainInterest(catalogue: Catalogue): TrainedInterest? {
        val began = System.currentTimeMillis()
        val type = catalogue.type
        val scorer = plugins.interestScorer(type.id) ?: return null
        val p = prepareInterest(catalogue)
        val measured = p?.takeIf { it.perItem.size >= CrossValidation.MIN_WORKS }?.let { CrossValidation.measureAll(it.task, scorer, it.xFor) }.orEmpty()
        val chosen = CrossValidation.choose(measured)
        val positive = p?.let { BooleanArray(it.grades.size) { i -> it.grades[i] == INTERESTING } }
        val calibration = chosen?.let { Calibration.fit(it.scores, positive!!) }
        if (p == null || chosen == null || calibration == null) {
            log.info("{}: too few works marked of interest for folds: no chances of interest", type.id)
            type.models.forgetInterest()
            return null
        }
        val fitted = scorer.fit(p.task, p.everyRow, chosen.parameter)
        val (mean, sd) = CrossValidation.standardisation(fitted.scores(p.x, p.grades.size).map { it.toDouble() })
        val meta = InterestMeta(
            scorer = scorer.id, parameter = chosen.parameter, categorical = p.layout.categorical,
            vectorCenters = p.spreads.mapValues { it.value.center }, vectorSpreads = p.spreads.mapValues { it.value.spread },
            numbers = p.numbers, scoreMean = mean, scoreSd = sd, calibration = calibration,
            trainedAtMillis = System.currentTimeMillis(),
            interesting = positive!!.count { it }, notInteresting = positive.count { !it }, metrics = chosen.metrics,
            candidates = measured.map { it.result() }, trainMillis = System.currentTimeMillis() - began,
        )
        log.info(
            "{}: the model of interest learnt {} interesting and {} not, out-of-fold AUC {}, parameter {}",
            type.id, meta.interesting, meta.notInteresting, "%.3f".format(chosen.metrics.concordance), chosen.parameter,
        )
        type.models.saveInterest(scorer.id, Instant.ofEpochMilli(meta.trainedAtMillis), json.writeValueAsString(meta), fitted.pack())
        return TrainedInterest(meta, fitted)
    }

    /**
     * The prediction of every graded item by a model that had not seen its work: the score the
     * cross-validation of the chosen parameter gave its latest grade's row from the fold the work
     * was left out of, on the model's scale. None when there were too few works for folds.
     */
    private fun heldOutScores(p: Prepared, chosen: RankingCandidate?, scale: Scale): Map<ItemKey, Double> {
        val scores = chosen?.scores ?: return emptyMap()
        return p.used.indices.groupBy { p.used[it].key }
            .mapValues { (_, rows) -> scale.ofStandard(scores[rows.maxBy { p.used[it].ratedAt }]) }
    }

    /** What a model that had not seen a graded work says of it: its score, where the user's grade stands, and why. */
    class HeldOut(val score: Double, val gradePlace: Double?, val explanation: List<ContributionGroup>)

    /**
     * HOW THE MODEL WOULD GRADE A GRADED WORK NOT KNOWING THE USER'S GRADE: the model of the fold the
     * work was left out of in the cross-validation — fitted again on every other fold with the
     * model's parameter, the likeness to the marks of the fold's works not counted — scores it and
     * tells why. The model of every grade has seen the work and partly learnt it by heart (its
     * annotation, its cover); this one has not. The score is the one the list sorts the surprises
     * by. None for a work the user has not graded, or when there are too few works for folds.
     *
     * Read as kept: the fitting takes a second or more, in the queue of the computations, and a
     * request never waits for it ([io.github.vlsergey.recommend4me.workload.Requests]) — not yet
     * worked out, it is planned, and the request answers so.
     */
    fun heldOut(key: ItemKey): HeldOutRead {
        val type = stores.typeOf(key.source)
        if (type.source(key.source)?.ratings?.ofItem(key.id).isNullOrEmpty()) return HeldOutRead.None
        val kept = type.models.explained(key) ?: return HeldOutRead.Planned.also { planHeldOut(type, key) }
        val score = kept.score ?: return HeldOutRead.None
        return HeldOutRead.Ready(HeldOut(score, kept.gradePlace, kept.explanation?.let { json.readValue<List<ContributionGroup>>(it) }.orEmpty()))
    }

    /** What a request reads of the model that had not seen a graded work: what it says, nothing to say, or being worked out — planned in the queue. */
    sealed interface HeldOutRead {
        class Ready(val heldOut: HeldOut) : HeldOutRead
        data object None : HeldOutRead
        data object Planned : HeldOutRead
    }

    /**
     * The model that had not seen the graded work fitted in the queue of the computations, and what
     * it says kept ([ModelRepository.keepExplained]); the request that asked does not wait for it.
     * Asked again before it is worked out, it is worked out once.
     */
    private fun planHeldOut(type: TypeStore, key: ItemKey) {
        val work = Work("heldOut", source = key.source, item = key.id)
        workload.plan(work, Instant.now(), "page")
        heldOutWorker.execute {
            try {
                if (type.models.explained(key) != null) {
                    workload.take(work)
                    return@execute
                }
                workload.run(work) {
                    val h = fitHeldOut(key)
                    type.models.keepExplained(key, h?.score, h?.gradePlace, h?.let { json.writeValueAsString(it.explanation) })
                }
            } catch (e: Exception) {
                log.warn("{}: what a model that had not seen the work says of it was not worked out", key, e)
            }
        }
    }

    /** A word on a graded work: what the model that had not seen it said is of the work as it was. */
    private fun heldOutChanged(source: String, itemId: String) = stores.typeOf(source).models.forgetExplained(ItemKey(source, itemId))

    private fun fitHeldOut(key: ItemKey): HeldOut? {
        val type = stores.typeOf(key.source)
        if (type.source(key.source)?.ratings?.ofItem(key.id).isNullOrEmpty()) return null
        val model = load(type) ?: return null
        val scorer = plugins.scorers.firstOrNull { it.id == model.meta.scorer } ?: return null
        // The work's own vectors kept with the graded ones': it is graded, but said so all the same
        val catalogue = reader.read(type, also = listOf(key))
        val p = prepare(catalogue) ?: return null
        val own = p.used.indices.filter { p.used[it].key == key }.maxByOrNull { p.used[it].ratedAt } ?: return null
        val folds = CrossValidation.folds(p.grades, p.works)
        if (folds.all { it == 0 }) return null
        val fold = folds[own]
        val underTest = p.grades.indices.filter { folds[it] == fold }.map { p.works[it] }.toSet()
        val train = p.grades.indices.filter { folds[it] != fold }.toIntArray()
        val x = p.xFor?.invoke(underTest) ?: p.x
        val fitted = scorer.fit(RankingTask(x, p.grades, p.works, p.items), train, model.meta.parameter)
        val all = fitted.scores(x).map { it.toDouble() }
        val (mean, sd) = CrossValidation.standardisation(train.map { all[it] })
        // Fitted on the catalogue as it is now, so laid out as it is now: an answer given since the
        // model was trained adds or drops a feature, and the kept model's layout no longer fits
        val foldModel = TrainedModel(
            model.meta.copy(
                categorical = p.layout.categorical, categoricalShare = categoricalShare(p.layout, catalogue),
                vectorCenters = p.spreads.mapValues { it.value.center }, vectorSpreads = p.spreads.mapValues { it.value.spread },
                numbers = p.numbers, scoreMean = mean, scoreSd = sd,
            ),
            fitted,
        )
        // The work as the fold saw it: its current input, its likeness to the marks of the fold's works left out
        val excluded = underTest.flatMap { p.keysOfWork[it].orEmpty() }.toSet()
        val current = CatalogueReader.previousGradesOfCurrentVersions(catalogue.ratings, catalogue::versionOf)[key]
        val input = CatalogueReader.inputOf(catalogue.byKey.getValue(key), catalogue.vectorsOf(key), current).let { i ->
            if (catalogue.likeness.empty) i else i.withVectors(i.vectors - MARK_KEYS + catalogue.likeness.vectors(key, excluded))
        }
        val grade = p.used[own].grade
        return HeldOut(
            score = foldModel.scale.ofStandard((all[own] - mean) / sd),
            gradePlace = model.scale.grades()[grade],
            explanation = explain(foldModel, input, type),
        )
    }

    /** Where every grade stands on 0..10 by the current model of the type; null without one. */
    fun gradePlaces(typeId: String): Map<Int, Double>? = load(type(typeId))?.scale?.grades()

    private fun scoreAll(catalogue: Catalogue, model: TrainedModel, interest: TrainedInterest?) {
        val previousForCurrent = CatalogueReader.previousGradesOfCurrentVersions(catalogue.ratings, catalogue::versionOf)
        // Every work's vectors read with its chunk and let go after it: of the catalogue only the training's are kept
        val predicted = catalogue.items.chunked(SCORE_CHUNK).parallelStream().flatMap { chunk ->
            val vectors = catalogue.vectorsOfMany(chunk.map { it.key })
            val inputs = chunk.map { CatalogueReader.inputOf(it, vectors.getValue(it.key), previousForCurrent[it.key]) }
            val raw = model.scores(model.layout.matrix(inputs))
            val chances = interest?.chances(inputs)
            chunk.indices.map { i -> chunk[i].key to Predicted(model.scale.score(raw[i].toDouble()), chances?.get(i)) }.stream()
        }.toList()
        val written = System.currentTimeMillis()
        catalogue.type.models.replacePredictions(predicted)
        log.info("{}: {} predictions written in {} ms", catalogue.type.id, predicted.size, System.currentTimeMillis() - written)
    }

    /** Re-scores one item, after its data changed outside a training. */
    fun scoreItem(key: ItemKey) {
        val type = stores.typeOf(key.source)
        val model = load(type) ?: return
        val input = inputOf(type, key, likenesses.stored(type)) ?: return
        type.models.savePrediction(key, Predicted(model.score(input), loadInterest(type)?.chances(listOf(input))?.single()))
    }

    /** The input of one item as the model reads it now. */
    private fun inputOf(type: TypeStore, key: ItemKey, stored: TypeLikeness, vectors: Map<String, FloatArray>? = null): ItemInput? {
        val item = reader.item(type, key) ?: return null
        val ratings = type.source(key.source)!!.ratings.ofItem(key.id).map { TypedRating(key, it.version, it.grade, it.ratedAt) }
        val previous = CatalogueReader.previousGradesOfCurrentVersions(ratings) { item.version }[key]
        return CatalogueReader.inputOf(item, vectors ?: (reader.vectors(type, key) + likenesses.of(type, key, stored)), previous)
    }

    // --- What the interface shows ---

    /** Everything the dialog of a work shows of the model. */
    class Details(
        val explanation: List<ContributionGroup>,
        val reviews: List<Pair<StoredReview, Double?>>,
        val pictures: Map<Int, Double>,
        val pictureMatches: List<MarkLikeness.Match>,
        val reviewMatches: List<MarkLikeness.Match>,
        /** What every part whose text is kept moves the work by, in points: part to points. */
        val parts: Map<String, Double> = emptyMap(),
    )

    /**
     * What moves the prediction of the work, part by part, strongest first, by the model of every
     * grade — for a page of the site. Of a graded work the page shows the model's that had not seen
     * it ([heldOut]): the model of every grade explains it by what it learnt of the work itself.
     */
    fun explanation(key: ItemKey): List<ContributionGroup> {
        val type = stores.typeOf(key.source)
        val model = load(type) ?: return emptyList()
        val input = inputOf(type, key, likenesses.stored(type)) ?: return emptyList()
        return explain(model, input, type)
    }

    fun details(key: ItemKey): Details {
        val type = stores.typeOf(key.source)
        val store = type.source(key.source)!!
        val stored = likenesses.stored(type)
        val model = load(type)
        val reviews = store.reviews.ofItem(key.id)
        if (model == null) {
            return Details(emptyList(), reviews.take(REVIEWS_SHOWN).map { it to null }, emptyMap(), emptyList(), emptyList())
        }
        val input = inputOf(type, key, stored) ?: return Details(emptyList(), emptyList(), emptyMap(), emptyList(), emptyList())
        val influence = reviewInfluence(type, key, model, input, stored, reviews)
        return Details(
            explain(model, input, type),
            influence ?: reviews.take(REVIEWS_SHOWN).map { it to null },
            pictureInfluence(type, key, model, input, stored),
            pictureMatches(type, key, stored),
            reviewMatches(type, key, stored, reviews),
            partInfluence(type, key, model, input),
        )
    }

    /**
     * What moves the work's score, in points, PART BY PART — a facet with all its values, the
     * pictures, a text, a number, the user's own marks on the site — each with every feature in
     * it; the strongest first, nothing left out. A part is switched off as a whole: what it adds
     * together is not the sum of what its features add one by one.
     *
     * Switched off, a vector or a number is the average; a categorical feature is put at how often
     * the catalogue has it — so a liked tag the work lacks counts too, by what lacking it costs
     * against the average work.
     */
    private fun explain(model: TrainedModel, input: ItemInput, type: TypeStore): List<ContributionGroup> {
        val layout = model.layout
        val dense = layout.groups(input).filter { it !is FeatureGroup.Categorical }
        val categorical = layout.categorical.filter { it in input.categorical || !it.startsWith("prev:") }
        val parts = (dense.map { it.feature } + categorical).groupBy(::partOf)
        val denseOf = dense.associateBy { it.feature }
        val share = model.meta.categoricalShare
        val m = Matrix(1 + dense.size + categorical.size + parts.size, layout.width)
        fun average(row: Int, names: Collection<String>) = names.forEach { name ->
            val i = layout.categorical.indexOf(name)
            if (i >= 0) m.held[m.row(row) + layout.categoricalOffset + i] = share.getOrElse(i) { 0f }
        }
        layout.write(input, m, 0)
        dense.forEachIndexed { g, group -> layout.write(input, m, g + 1, group) }
        categorical.forEachIndexed { c, name ->
            val row = 1 + dense.size + c
            layout.write(input, m, row)
            average(row, listOf(name))
        }
        val partList = parts.entries.toList()
        partList.forEachIndexed { p, (_, members) ->
            val row = 1 + dense.size + categorical.size + p
            layout.write(input, m, row, members.mapNotNull { denseOf[it] })
            average(row, members.filter { it !in denseOf })
        }
        val raw = model.scores(m)
        val full = model.scale.score(raw[0].toDouble())
        fun moved(row: Int) = full - model.scale.score(raw[row].toDouble())
        val features = dense.mapIndexed { g, group -> group.feature to moved(g + 1) }.toMap() +
            categorical.mapIndexed { c, name -> name to moved(1 + dense.size + c) }
        val labels = FeatureNames.labels(features.keys, type.sources)
        val partLabels = FeatureNames.partLabels(parts.keys, type.sources)
        return partList.mapIndexed { p, (part, members) ->
            ContributionGroup(
                part, partLabels.getValue(part), moved(1 + dense.size + categorical.size + p),
                members.map { LabelledContribution(Contribution(it, features.getValue(it), it in input.categorical || it in denseOf), labels.getValue(it)) }
                    .sortedByDescending { abs(it.contribution.contribution) },
            )
        }.sortedByDescending { abs(it.contribution) }
    }

    /** The part of the input a feature is switched off with: its facet, the pictures, the reviews, the text; else itself. */
    private fun partOf(feature: String): String = FeatureNames.parseFacet(feature)?.let { "facet:${it.first}" } ?: when {
        feature.startsWith("prev:") -> FeatureNames.PREVIOUS
        feature.startsWith("source:") -> FeatureNames.SOURCE
        feature in PICTURE_BLOCKS -> FeatureNames.PICTURES
        feature in REVIEW_BLOCKS -> FeatureNames.REVIEWS
        else -> feature
    }

    /** What a group of features adds to a work's score: the score with it minus the score with it switched off, in points. */
    private fun occlusion(model: TrainedModel, input: ItemInput): List<Pair<String, Double>> {
        val groups = model.layout.groups(input)
        val m = Matrix(groups.size + 1, model.layout.width)
        model.layout.write(input, m, 0)
        groups.forEachIndexed { g, group -> model.layout.write(input, m, g + 1, group) }
        val raw = model.scores(m)
        val full = model.scale.score(raw[0].toDouble())
        return groups.mapIndexed { g, group -> group.feature to full - model.scale.score(raw[g + 1].toDouble()) }
    }

    /** Mean contribution of every feature over the graded works that have it, strongest positive first. */
    private fun importance(model: TrainedModel, perItem: List<ItemInput>): List<Contribution> {
        val sums = HashMap<String, Double>()
        val counts = HashMap<String, Int>()
        perItem.take(MAX_IMPORTANCE_ITEMS).forEach { input ->
            occlusion(model, input).forEach { (feature, c) ->
                sums.merge(feature, c, Double::plus)
                counts.merge(feature, 1, Int::plus)
            }
        }
        val means = sums.entries.filter { (counts[it.key] ?: 0) >= FeatureLayout.MIN_SUPPORT }.map { it.key to it.value / counts.getValue(it.key) }
        val ranked = means.sortedByDescending { it.second }
        val shown = (ranked.take(TOP_FEATURES) + ranked.takeLast(TOP_FEATURES)).map { it.first }.toSet()
        return ranked.filter { it.first in shown }.map { (f, c) -> Contribution(f, c) }
    }

    /**
     * What every analysed picture of the work moves it by, in points: the score as it is minus the
     * score with that one picture left out — the cover dropped, or a screenshot taken out of the
     * screenshots' mean, their set and their likeness to the marks.
     */
    private fun pictureInfluence(type: TypeStore, key: ItemKey, model: TrainedModel, input: ItemInput, stored: TypeLikeness): Map<Int, Double> {
        val encoder = plugins.imageEncoder() ?: return emptyMap()
        val pictures = type.source(key.source)!!.pictures.positioned(key.id, encoder.id)
        if (pictures.isEmpty()) return emptyMap()
        val screenSet = type.embeddings.find(SetKind.SCREENS)?.takeIf { it.encoder == encoder.id }?.embedding
        val others = input.vectors - PictureRepository.COVER - PictureRepository.SCREENS - SetKind.SCREENS.key - MarkLikeness.PICTURES
        fun inputWith(list: List<Pair<Int, FloatArray>>): ItemInput {
            val set = screenSet?.of(list.filter { it.first > 0 }.map { it.second })
            val marks = stored.pictures.rowOf(key, list.map { (p, v) -> p.toString() to v })?.let { row -> stored.pictures.vector(row) { it.owner == key } }
            return input.withVectors(
                others + PictureRepository.combine(list) +
                    listOfNotNull(set?.let { SetKind.SCREENS.key to it }, marks?.let { MarkLikeness.PICTURES to it }),
            )
        }
        val inputs = listOf(inputWith(pictures)) + pictures.indices.map { i -> inputWith(pictures.filterIndexed { j, _ -> j != i }) }
        val raw = model.scores(model.layout.matrix(inputs))
        val full = model.scale.score(raw[0].toDouble())
        return pictures.indices.associate { i -> pictures[i].first to full - model.scale.score(raw[i + 1].toDouble()) }
    }

    /**
     * What every review of the work moves it by, in points: the score as it is minus the score with
     * that review left out of the reviews' set and of their likeness to the marked ones. The
     * strongest either way, strongest first; null when the model reads no reviews.
     */
    private fun reviewInfluence(
        type: TypeStore, key: ItemKey, model: TrainedModel, input: ItemInput, stored: TypeLikeness, list: List<StoredReview>,
    ): List<Pair<StoredReview, Double?>>? {
        val encoder = plugins.textEncoder() ?: return null
        val embedding = type.embeddings.find(SetKind.REVIEWS)?.takeIf { it.encoder == encoder.id }?.embedding ?: return null
        if (list.isEmpty()) return emptyList()
        val store = type.source(key.source)!!
        val texts = list.map { ReviewRepository.textOf(it.content) }
        val unique = texts.filter { it.isNotEmpty() }.distinct()
        if (unique.isEmpty()) return list.take(REVIEWS_SHOWN).map { it to null }
        val index = unique.withIndex().associate { (i, t) -> t to i }
        val owners = IntArray(unique.size)
        texts.forEach { t -> index[t]?.let { owners[it]++ } }
        val encoded = phrases.of(store, unique)
        val projections = embedding.project(unique.map { encoded.getValue(it) })
        val others = input.vectors - SetKind.REVIEWS.key - MarkLikeness.REVIEWS
        val items = list.indices.mapNotNull { i -> encoded[texts[i]]?.let { list[i].reviewId to it } }
        fun marks(without: String?) =
            stored.reviews.rowOf(key, items.filter { it.first != without })?.let { row -> stored.reviews.vector(row) { it.owner == key } }
        fun inputWith(set: FloatArray?, marks: FloatArray?) =
            input.withVectors(others + listOfNotNull(set?.let { SetKind.REVIEWS.key to it }, marks?.let { MarkLikeness.REVIEWS to it }))
        val inputs = listOf(inputWith(embedding.quantiles(projections, null), marks(null))) + list.indices.map { i ->
            val keep = BooleanArray(unique.size) { true }
            // A review word for word the same as another stays, as the other
            index[texts[i]]?.let { j -> if (owners[j] == 1) keep[j] = false }
            inputWith(embedding.quantiles(projections, keep), marks(list[i].reviewId))
        }
        val raw = model.scores(model.layout.matrix(inputs))
        val full = model.scale.score(raw[0].toDouble())
        return list.indices.map { i -> list[i] to (full - model.scale.score(raw[i + 1].toDouble())) as Double? }
            .sortedByDescending { abs(it.second ?: 0.0) }
            .take(REVIEWS_SHOWN)
    }

    /**
     * What every part of the work — a chapter whose text is kept — moves it by, in points: the
     * score as it is minus the score with that part's windows left out of the set of the text's
     * windows. None when the model reads no text of parts.
     */
    private fun partInfluence(type: TypeStore, key: ItemKey, model: TrainedModel, input: ItemInput): Map<String, Double> {
        val encoder = plugins.textEncoder() ?: return emptyMap()
        if (model.layout.offsetOf(SetKind.PARTS.key) == null) return emptyMap()
        val embedding = type.embeddings.find(SetKind.PARTS)?.takeIf { it.encoder == encoder.id }?.embedding ?: return emptyMap()
        val byPart = type.source(key.source)!!.parts.windowsByPart(key.id, encoder.id)
        if (byPart.isEmpty()) return emptyMap()
        val parts = byPart.keys.toList()
        val windows = parts.flatMap { byPart.getValue(it) }
        val owners = parts.flatMap { id -> List(byPart.getValue(id).size) { id } }
        val projections = embedding.project(windows)
        val others = input.vectors - SetKind.PARTS.key
        fun inputWith(set: FloatArray?) = input.withVectors(others + listOfNotNull(set?.let { SetKind.PARTS.key to it }))
        val inputs = listOf(inputWith(embedding.quantiles(projections, null))) +
            parts.map { id -> inputWith(embedding.quantiles(projections, BooleanArray(windows.size) { owners[it] != id })) }
        val raw = model.scores(model.layout.matrix(inputs))
        val full = model.scale.score(raw[0].toDouble())
        return parts.withIndex().associate { (i, id) -> id to full - model.scale.score(raw[i + 1].toDouble()) }
    }

    /** The pictures of other works marked by the user that the work's pictures are most alike, strongest first. */
    private fun pictureMatches(type: TypeStore, key: ItemKey, stored: TypeLikeness): List<MarkLikeness.Match> {
        val encoder = plugins.imageEncoder() ?: return emptyList()
        val row = stored.pictures.rowOf(key, type.source(key.source)!!.pictures.positioned(key.id, encoder.id).map { (p, v) -> p.toString() to v })
            ?: return emptyList()
        return stored.pictures.matches(key, row, MATCHES_SHOWN)
    }

    /** The reviews of other works marked by the user that the work's reviews read most alike, strongest first. */
    private fun reviewMatches(type: TypeStore, key: ItemKey, stored: TypeLikeness, reviews: List<StoredReview>): List<MarkLikeness.Match> {
        if (stored.reviews.marks.isEmpty()) return emptyList()
        val store = type.source(key.source)!!
        val texts = reviews.map { it.reviewId to ReviewRepository.textOf(it.content) }.filter { it.second.isNotEmpty() }
        val vectors = phrases.of(store, texts.map { it.second })
        val row = stored.reviews.rowOf(key, texts.mapNotNull { (id, text) -> vectors[text]?.let { id to it } }) ?: return emptyList()
        return stored.reviews.matches(key, row, MATCHES_SHOWN)
    }

    /** Share of the catalogue's items with every categorical feature of the [layout]. */
    private fun categoricalShare(layout: FeatureLayout, catalogue: Catalogue): FloatArray {
        val index = layout.categorical.withIndex().associate { (i, name) -> name to i }
        val counts = IntArray(layout.categorical.size)
        catalogue.items.forEach { item -> item.categorical.forEach { name -> index[name]?.let { counts[it]++ } } }
        val total = catalogue.items.size
        return FloatArray(counts.size) { if (total > 0) counts[it].toFloat() / total else 0f }
    }

    fun summary(typeId: String): ModelSummary {
        val type = type(typeId)
        val model = load(type)
        val changedAt = settings.get("model.$typeId.gradesChangedAt")?.toLongOrNull() ?: 0L
        val counts = counts(type.sources.flatMap { s -> s.ratings.all().map { it.grade } }.toIntArray())
        return ModelSummary(
            meta = model?.meta,
            training = training(typeId),
            stale = if (model == null) counts.sum() > 0 else changedAt > model.meta.trainedAtMillis,
            textEncoderReady = plugins.textEncoder() != null,
            imageEncoderReady = plugins.imageEncoder() != null,
            scorer = plugins.scorer(typeId)?.id,
            ratingCounts = counts,
        )
    }

    /** The rows of the model of interest: the user's marks of interest, the grades of earlier versions read as with the grades. */
    private fun prepareInterest(catalogue: Catalogue): Prepared? {
        val previous = CatalogueReader.previousGradesOfCurrentVersions(catalogue.ratings, catalogue::versionOf)
        return prepare(catalogue, interestRows(catalogue), "interest marks") { previous[it.key] }
    }

    /**
     * What every scorer is measured on: the rows of the current grades — of the marks of interest,
     * of the model of [interest] — read under the type's lock; the measuring itself needs no lock —
     * it reads nothing more. Null when there are too few.
     */
    fun measuringInput(typeId: String, interest: Boolean = false): MeasuringInput? = workload.run(Work("scorersInput", type = typeId), mapOf("asked" to 1), joinAlike = false) {
        val catalogue = reader.read(type(typeId))
        val p = (if (interest) prepareInterest(catalogue) else prepare(catalogue)) ?: return@run null
        MeasuringInput(p.task, p.xFor, p.grades.size)
    }

    /** The model of interest as kept: what the page of the models shows of it; null while there is none. */
    fun interestMeta(typeId: String): InterestMeta? = loadInterest(type(typeId))?.meta

    /** Makes [scorerId] the scorer of the type's model of interest and plans the training of the models; false for no such scorer. */
    fun chooseInterestScorer(typeId: String, scorerId: String): Boolean {
        if (plugins.scorers.none { it.id == scorerId }) return false
        settings.set("scorer.$typeId.interest", scorerId)
        trainSoon(typeId)
        return true
    }

    /**
     * WHAT EVERY PART OF THE INPUT IS WORTH TO THE RANKING — the annotation, the cover, a facet with
     * all its values, a number: the out-of-fold quality of the type's scorer trained without that
     * part (its columns zero — the average, nothing to tell the works apart by) against trained on
     * everything, by the same cross-validation and the same choice of the parameter. A part whose
     * removal costs nothing, or improves the quality, adds nothing the grades confirm. Nothing is kept.
     */
    fun worth(typeId: String): ModelWorth? = workload.run(Work("worth", type = typeId), mapOf("asked" to 1)) {
        val type = type(typeId)
        val scorer = plugins.scorer(typeId) ?: return@run null
        val p = prepare(reader.read(type)) ?: return@run null
        if (p.perItem.size < CrossValidation.MIN_WORKS) return@run null
        val full = CrossValidation.choose(CrossValidation.measureAll(p.task, scorer, p.xFor))?.metrics
        val columns = partColumns(p.layout)
        val labels = FeatureNames.partLabels(columns.keys, type.sources)
        fun without(m: Matrix, cols: List<Int>): Matrix {
            val out = Matrix(m.rows, m.cols)
            out.held.copy(m.held, 0, 0, m.rows * m.cols)
            for (r in 0 until m.rows) cols.forEach { out.held[out.row(r) + it] = 0f }
            return out
        }
        val parts = columns.map { (part, cols) ->
            val task = RankingTask(without(p.x, cols), p.grades, p.works, p.items)
            val xFor = p.xFor?.let { f -> { underTest: Set<Long> -> without(f(underTest), cols) } }
            PartWorth(part, labels.getValue(part), cols.size, CrossValidation.choose(CrossValidation.measureAll(task, scorer, xFor))?.metrics)
        }
        ModelWorth(scorer.id, full, p.grades.size, parts.sortedBy { it.metrics?.concordance ?: Double.MAX_VALUE })
    }

    /** The columns of every part of the input in [layout], as the explanation names the parts. */
    private fun partColumns(layout: FeatureLayout): Map<String, List<Int>> {
        val columns = LinkedHashMap<String, MutableList<Int>>()
        layout.blocks.forEach { block ->
            val at = layout.offsetOf(block)!!
            columns.getOrPut(partOf(block)) { ArrayList() } += (at until at + layout.dimOf(block))
        }
        layout.categorical.forEachIndexed { i, name -> columns.getOrPut(partOf(name)) { ArrayList() } += layout.categoricalOffset + i }
        layout.numeric.forEachIndexed { i, name -> columns.getOrPut(partOf(name)) { ArrayList() } += layout.numericOffset + i }
        return columns
    }

    /**
     * WHETHER WEIGHING THE PARTS OF THE INPUT WOULD RANK BETTER ([GroupWeights]): every fold of the
     * works ranked by a model whose weights were chosen without it, against every weight 1 the same
     * way — and the weights chosen on every grade. The rows are read under the type's lock, the
     * trainings — hundreds of them — run without it. Nothing is kept. [cautious]: [GroupWeights.tune].
     */
    fun weighing(typeId: String, cautious: Boolean = true): Weighing? = workload.run(Work("weighing", type = typeId), mapOf("asked" to 1), joinAlike = false) { weighed(typeId, cautious) }

    private fun weighed(typeId: String, cautious: Boolean): Weighing? {
        val type = type(typeId)
        val scorer = plugins.scorer(typeId) ?: return null
        val p = prepare(reader.read(type)) ?: return null
        if (p.perItem.size < CrossValidation.MIN_WORKS) return null
        val started = System.currentTimeMillis()
        val columns = partColumns(p.layout)
        val groups = GroupWeights.groups(columns)
        val labels = FeatureNames.partLabels(columns.keys, type.sources)
        val folds = GroupWeights.nested(p.task, p.xFor, scorer, groups, cautious)
        val onAll = GroupWeights.tune(p.task, p.xFor, scorer, groups, cautious)
        log.info(
            "{}: the weighing of {} groups measured on {} grades in {} ms: {} folds, every weight 1 {} against weighed {}; on every grade {}",
            typeId, groups.size, p.grades.size, System.currentTimeMillis() - started, folds.size,
            folds.map { "%.4f".format(it.baseline) }, folds.map { "%.4f".format(it.weighed) },
            groups.indices.joinToString { "${groups[it].part}=${onAll.weights[it]}" },
        )
        return Weighing(
            scorer.id, p.grades.size,
            groups.mapIndexed { g, group ->
                WeighedGroup(group.part, labels[group.part] ?: I18nKeys.qualified(I18nKeys.STANDARD, "model.rest"), group.columns.size, onAll.weights[g])
            },
            folds, onAll.metrics,
        )
    }

    /** Makes [scorerId] the scorer of the type and plans its training; false for no such scorer. */
    fun chooseScorer(typeId: String, scorerId: String): Boolean {
        if (plugins.scorers.none { it.id == scorerId }) return false
        settings.set("scorer.$typeId", scorerId)
        trainSoon(typeId)
        return true
    }

    private fun counts(grades: IntArray) = IntArray(Grades.MAX).apply { grades.forEach { this[it - Grades.MIN]++ } }

    @PreDestroy
    fun shutdown() {
        scheduler.shutdownNow()
        heldOutWorker.shutdownNow()
    }

    companion object {
        /** The quiet after the user's last change before the model learns it. */
        private const val USER_QUIET_MS = 3_000L

        /** The least quiet after the background work's last change. */
        private const val MACHINE_QUIET_MS = 60_000L

        /** The longest a stream of background changes puts a training off. */
        private const val MAX_WAIT_MS = 10 * 60_000L
        private const val MIN_RATINGS = 3

        /** A work of interest and one not, as grades of the model of interest: only their order matters. */
        private const val INTERESTING = 2
        private const val NOT_INTERESTING = 1
        private const val REVIEWS_SHOWN = 30

        /** The blocks of the pictures: an explanation tells of them together. */
        private val PICTURE_BLOCKS = setOf(PictureRepository.COVER, PictureRepository.SCREENS, SetKind.SCREENS.key, MarkLikeness.PICTURES)

        /** The blocks of the opinions of other people. */
        private val REVIEW_BLOCKS = setOf(SetKind.REVIEWS.key, MarkLikeness.REVIEWS)
        private const val MATCHES_SHOWN = 3
        private const val TOP_FEATURES = 25
        private const val SCORE_CHUNK = 1024
        private const val MAX_IMPORTANCE_ITEMS = 1500
        private val MARK_KEYS = setOf(MarkLikeness.PICTURES, MarkLikeness.REVIEWS, WorkLikeness.WORKS)
    }
}
