package org.adveto.app.log

/**
 * Stage (7) — one *advertisement occurrence* as shown to the parent.
 *
 * The raw log holds one row per region per frame, which is the wrong shape
 * for a human: a parent asks "that app showed me four gambling ads for five
 * minutes", not "37 decisions were logged". This type is the join between
 * those two views — a run of frames in which the same application kept
 * producing the same category.
 *
 * ### Why every duration is a span, not a measurement
 *
 * Frames arrive only when the screen content changes, so sampling is
 * *content-driven* rather than fixed-rate: the 19 Oct capture recorded 6
 * frames across 173 seconds. [spanMs] is the distance between the first and
 * last sighting of a `(application, category)` pair, and two independent
 * effects pull it in opposite directions, so it is neither a clean lower nor
 * a clean upper bound on any single advertisement:
 *
 *  * **Undercounts** when an advertisement stays visible across an unsampled
 *    gap — the frames in between never arrived, so part of its run is absent
 *    from the measurement.
 *  * **Overcounts** when two advertisements from the same application and
 *    category follow one another inside [SESSION_GAP_MS] — they are one run
 *    here, so the span covers both and exceeds either alone.
 *
 * It therefore answers *"how long did this application keep showing this
 * category"*, which is the honest reading of what was sampled. Nothing here
 * ever claims airtime, and a single sighting reports "duration unknown"
 * rather than `0 s`, which would read as "it flashed by" instead of "we do
 * not know".
 *
 * ### Why samples are counted from frames, not rows
 *
 * One frame emits up to three rows — one per proposed region — all carrying
 * the same [Event.frameId]. Counting rows would report three observations for
 * one moment, inflating the appearance of sampling precision by exactly the
 * region heuristic's factor of three.
 */
data class AdOccurrence(
    /** Application that produced the advertisement (the "platform"). */
    val packageName: String,
    val category: String,
    val firstSeen: Long,
    val lastSeen: Long,
    /** Distinct frames observed — deliberately *not* the row count. */
    val samples: Int,
    /**
     * Outcome of the most recent frame in the run.
     *
     * Taken from the last frame rather than a vote: the rows are chronological,
     * so "what the system last did" is well defined, whereas a vote could
     * report `allow` for an occurrence the system covered seconds later.
     */
    val outcome: String,
    /** Highest confidence observed across the run, so a single strong sample
     *  is not averaged away by weaker ones. */
    val confidence: Float,
    val modality: String,
    val ageBand: String,
    /** Distinct region kinds seen (BANNER_BOTTOM, MREC, ...), sorted. */
    val regionSources: List<String>,
    /** Every row in the run, ascending by frame — what the detail dialog reads. */
    val events: List<Event>,
) {
    /** Observation span: first sighting to last. A sampled value, never airtime. */
    val spanMs: Long get() = lastSeen - firstSeen

    /**
     * Whether a span may be quoted at all.
     *
     * One sighting cannot bound anything: first and last are the same instant
     * and would render as "0 s", which reads as a measurement of brevity when
     * the truth is that nothing was sampled either side of it.
     */
    val hasSpan: Boolean get() = samples >= 2

    /**
     * True while the classifier is still a stub.
     *
     * Under the stub the score vector is all zeros, so `AgePolicy`'s argmax
     * resolves to index 0 — `SAFE_GENERAL` — on every frame. Rendering that as
     * a category would tell a parent the advertisement was assessed as safe
     * when no assessment took place, which is the single most misleading
     * thing this screen could say.
     */
    val unclassified: Boolean get() = events.any { it.modality == "pending-model" }
}

/**
 * How long a gap is tolerated before a run is considered over.
 *
 * Chosen above the sparsest spacing actually measured (~29 s between frames on
 * a static screen) so that ordinary sampling gaps do not shred one advertisement
 * into many short ones. The trade is deliberate and asymmetric: a longer gap
 * merges two ads from the same application into one occurrence, which
 * *overstates* the span but never manufactures a short duration. Because every
 * span is reported as a bound alongside its sample count, a reader can see the
 * evidence rather than trusting a single number.
 */
const val SESSION_GAP_MS: Long = 60_000L

/**
 * Group decision rows into advertisement occurrences.
 *
 * Splits on two conditions: the `(packageName, category)` pair changes, or the
 * gap between consecutive frames exceeds [sessionGapMs]. Rows from one frame
 * share a [Event.frameId] and therefore a gap of 0, so the three region rows
 * of a single moment can never be mistaken for separate sightings.
 *
 * Pure and side-effect free — it runs in `testDebugUnitTest` with no device
 * and no Android framework, which is what makes the duration contract
 * checkable rather than merely asserted.
 *
 * @param events rows to group, in any order.
 * @return occurrences newest-first.
 */
fun groupOccurrences(
    events: List<Event>,
    sessionGapMs: Long = SESSION_GAP_MS,
): List<AdOccurrence> {
    if (events.isEmpty()) return emptyList()

    return events
        .groupBy { it.packageName to it.category }
        .flatMap { (key, group) ->
            val ordered = group.sortedBy { frameOf(it) }
            val runs = mutableListOf<List<Event>>()
            var current = mutableListOf<Event>()
            var lastFrame = 0L

            ordered.forEach { event ->
                val frame = frameOf(event)
                // First row of a frame, not merely of a run: a row whose frame
                // jumps by more than the threshold closes the run, while rows
                // sharing a frame never do.
                if (current.isNotEmpty() && frame - lastFrame > sessionGapMs) {
                    runs += current
                    current = mutableListOf()
                }
                current += event
                lastFrame = frame
            }
            if (current.isNotEmpty()) runs += current

            runs.map { run -> occurrence(key.first, key.second, run) }
        }
        .sortedByDescending { it.lastSeen }
}

/**
 * Frame identity of a row, tolerating pre-v2 data.
 *
 * A row written before schema v2 carries `frameId == 0`; falling back to
 * [Event.timestamp] keeps such history groupable instead of collapsing every
 * legacy row into one zero-length run at the epoch.
 */
private fun frameOf(event: Event): Long =
    if (event.frameId != 0L) event.frameId else event.timestamp

private fun occurrence(
    packageName: String,
    category: String,
    run: List<Event>,
): AdOccurrence {
    val frames = run.map { frameOf(it) }
    val latest = run.last()
    return AdOccurrence(
        packageName = packageName,
        category = category,
        firstSeen = frames.min(),
        lastSeen = frames.max(),
        samples = frames.distinct().size,
        outcome = latest.outcome,
        confidence = run.maxOf { it.confidence },
        modality = latest.modality,
        ageBand = latest.ageBand,
        regionSources = run.map { it.regionSource }.distinct().sorted(),
        events = run,
    )
}

/**
 * "4 m 53 s" / "29 s".
 *
 * Lives here rather than in the activity so the display contract can be
 * asserted in tests; `MainActivity` keeps its own copy for the gate card
 * because the gate's elapsed timer is a different thing (wall-clock session
 * time) and the two should not silently drift into one concept.
 */
fun formatSpan(ms: Long): String {
    if (ms <= 0L) return "0 s"
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return if (minutes > 0) "%d m %02d s".format(minutes, seconds) else "$totalSeconds s"
}
