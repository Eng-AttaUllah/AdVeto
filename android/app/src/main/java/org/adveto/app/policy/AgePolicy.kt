package org.adveto.app.policy

/** The four risk categories fixed by the proposal (§3, §8). */
enum class RiskCategory {
    SAFE_GENERAL,
    ADULT_SEXUALIZED,
    GAMBLING,
    VIOLENCE;

    val unsafe: Boolean get() = this != SAFE_GENERAL

    companion object {
        /** Order is part of the contract: model output vector index -> category. */
        val ORDER: List<RiskCategory> = entries.toList()

        fun fromIndex(i: Int): RiskCategory =
            ORDER.getOrElse(i) { SAFE_GENERAL }
    }
}

/** Stage (5) outcomes — allow, cover, or abstain (proposal §7). */
enum class Decision { ALLOW, COVER, ABSTAIN }

/**
 * Configured age band. The proposal adopts a configurable band policy rather
 * than one fixed classifier threshold (Wang et al. [5]) — age changes what is
 * acceptable, not what the model sees.
 */
data class AgeBand(val id: String, val minYears: Int, val maxYears: Int) {
    companion object {
        val BANDS = listOf(
            AgeBand("3-5", 3, 5),
            AgeBand("6-9", 6, 9),
            AgeBand("10-12", 10, 12),
            AgeBand("13-17", 13, 17),
            AgeBand("18+", 18, 120),
        )

        fun of(ageYears: Int): AgeBand = when {
            ageYears < BANDS.first().minYears -> BANDS.first()   // under-age: strictest
            ageYears > BANDS.last().maxYears -> BANDS.last()
            else -> BANDS.first { ageYears in it.minYears..it.maxYears }
        }
    }
}

/** Outcome plus enough context to write a readable, auditable event row. */
data class PolicyResult(
    val decision: Decision,
    val category: RiskCategory,
    val confidence: Float,
    val band: AgeBand,
    val reason: String,
)

/**
 * Stage (5) — the age-aware policy.
 *
 * Pure and side-effect free: given a fused score vector and a child's age it
 * returns allow / cover / abstain. That purity is deliberate — deliverable 3
 * requires policy cases that are *executable as regression tests*, and they run
 * on the JVM (`./gradlew test`) with no device, no model, and no I/O.
 *
 * Order of judgement:
 *   1. confidence < tau            -> ABSTAIN  (never forced into allow/cover)
 *   2. look up (category, band)    -> ALLOW | COVER
 *   3. rule missing for an unsafe  -> COVER    (fail safe, never fail open)
 *
 * @param tau minimum fused confidence required before any allow/cover is
 *        returned; below it the event is logged as uncertain and the abstain
 *        *rate* is reported alongside accuracy (proposal §7) — frequent
 *        abstention is not equivalent to reliable detection.
 * @param rules (category x band) -> decision. Defaults below are a starting
 *        point; the values are configuration, not model output, and are
 *        documented in docs/04-age-policy.md.
 */
class AgePolicy(
    private val tau: Float,
    private val rules: Map<RiskCategory, Map<String, Decision>> = DEFAULT_RULES,
) {

    fun decide(scores: FloatArray, ageYears: Int): PolicyResult {
        require(scores.size == RiskCategory.ORDER.size) {
            "expected ${RiskCategory.ORDER.size} scores, got ${scores.size}"
        }
        val band = AgeBand.of(ageYears)

        // Normalise defensively: fusion output may be logits or unnormalised
        // weights, and argmax/max are only meaningful on a common scale.
        val max = scores.maxOrNull() ?: 0f
        val min = scores.minOrNull() ?: 0f
        val normalised = if (max <= 0f) scores else scores.map { (it - min) / (max - min) }.toFloatArray()
        val idx = normalised.indices.maxBy { normalised[it] }
        val confidence = scores[idx].coerceIn(0f, 1f)
        val category = RiskCategory.fromIndex(idx)

        if (confidence < tau) {
            return PolicyResult(
                decision = Decision.ABSTAIN,
                category = category,
                confidence = confidence,
                band = band,
                reason = "confidence %.3f < tau %.3f".format(confidence, tau),
            )
        }

        val lookup = rules[category]?.get(band.id)
        val verdict = lookup ?: if (category.unsafe) Decision.COVER else Decision.ALLOW

        // The reason must distinguish "the matrix said so" from "the matrix
        // had nothing to say" — a missing rule failing safe is a defect to be
        // visible in the log, not to look like a deliberate COVER.
        val reason = when {
            lookup != null -> "rule ${category.name} x ${band.id} = $lookup"
            category.unsafe -> "rule-missing-safe-default"
            else -> "safe-default-allow"
        }

        return PolicyResult(
            decision = verdict,
            category = category,
            confidence = confidence,
            band = band,
            reason = reason,
        )
    }

    companion object {
        /**
         * Default matrix — 3-5 is the strictest band, 18+ unrestricted.
         * Sexualised content is covered at every age: advertising standards
         * restrict it for minors as a class, which is a *policy* fact, not a
         * model output.
         */
        val DEFAULT_RULES: Map<RiskCategory, Map<String, Decision>> = mapOf(
            RiskCategory.SAFE_GENERAL to mapOf(
                "3-5" to Decision.ALLOW, "6-9" to Decision.ALLOW,
                "10-12" to Decision.ALLOW, "13-17" to Decision.ALLOW,
                "18+" to Decision.ALLOW,
            ),
            RiskCategory.ADULT_SEXUALIZED to mapOf(
                "3-5" to Decision.COVER, "6-9" to Decision.COVER,
                "10-12" to Decision.COVER, "13-17" to Decision.COVER,
                "18+" to Decision.ALLOW,
            ),
            RiskCategory.GAMBLING to mapOf(
                "3-5" to Decision.COVER, "6-9" to Decision.COVER,
                "10-12" to Decision.COVER, "13-17" to Decision.COVER,
                "18+" to Decision.ALLOW,
            ),
            RiskCategory.VIOLENCE to mapOf(
                "3-5" to Decision.COVER, "6-9" to Decision.COVER,
                "10-12" to Decision.COVER, "13-17" to Decision.ALLOW,
                "18+" to Decision.ALLOW,
            ),
        )

        /** Research configuration: tau is swept on validation, not guessed. */
        fun default(tau: Float = 0.60f) = AgePolicy(tau = tau)
    }
}
