package org.adveto.app.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deliverable 3: age-policy test cases covering allow, cover, and abstain.
 *
 * These are deliberately table-driven over *score vectors*, because that is
 * the contract between the fusion stage and the policy stage: whatever models
 * are eventually wired in, only their four numbers cross this boundary. Every
 * decision the policy can make is asserted here, so a change to the matrix
 * that nobody intended fails the build rather than the evaluation.
 */
class AgePolicyTest {

    private val policy = AgePolicy(tau = 0.60f)

    private fun scores(
        safe: Float, adult: Float, gambling: Float, violence: Float
    ) = floatArrayOf(safe, adult, gambling, violence)

    // --- abstain ----------------------------------------------------------

    @Test
    fun `abstains when no class reaches tau`() {
        val r = policy.decide(scores(0.40f, 0.30f, 0.20f, 0.10f), ageYears = 8)
        assertEquals(Decision.ABSTAIN, r.decision)
        assertTrue("reason should mention tau: ${r.reason}", r.reason.contains("tau"))
    }

    @Test
    fun `abstains at exactly the threshold boundary check - just below tau`() {
        val r = policy.decide(scores(0.59f, 0.20f, 0.11f, 0.10f), ageYears = 8)
        assertEquals(Decision.ABSTAIN, r.decision)
    }

    @Test
    fun `uncertainty is not forced into allow or cover`() {
        // Even for a band where everything is allowed (18+), sub-tau output
        // must abstain: abstention reports detection quality, not policy.
        val r = policy.decide(scores(0.50f, 0.30f, 0.10f, 0.10f), ageYears = 25)
        assertEquals(Decision.ABSTAIN, r.decision)
    }

    // --- allow ------------------------------------------------------------

    @Test
    fun `allows safe content for every band`() {
        val bands = listOf(4, 7, 11, 15, 30)
        for (age in bands) {
            val r = policy.decide(scores(0.92f, 0.03f, 0.03f, 0.02f), age)
            assertEquals("age $age", Decision.ALLOW, r.decision)
            assertEquals(RiskCategory.SAFE_GENERAL, r.category)
        }
    }

    @Test
    fun `allows violence for the 13-17 band but covers it for younger`() {
        assertEquals(
            Decision.ALLOW,
            policy.decide(scores(0.05f, 0.05f, 0.05f, 0.85f), ageYears = 15).decision
        )
        assertEquals(
            Decision.COVER,
            policy.decide(scores(0.05f, 0.05f, 0.05f, 0.85f), ageYears = 9).decision
        )
    }

    // --- cover ------------------------------------------------------------

    @Test
    fun `covers gambling for every minor`() {
        for (age in listOf(3, 5, 8, 12, 17)) {
            val r = policy.decide(scores(0.05f, 0.05f, 0.85f, 0.05f), ageYears = age)
            assertEquals("age $age", Decision.COVER, r.decision)
            assertEquals(RiskCategory.GAMBLING, r.category)
        }
    }

    @Test
    fun `covers adult content for minors and allows it for adults`() {
        assertEquals(
            Decision.COVER,
            policy.decide(scores(0.05f, 0.90f, 0.03f, 0.02f), ageYears = 16).decision
        )
        assertEquals(
            Decision.ALLOW,
            policy.decide(scores(0.05f, 0.90f, 0.03f, 0.02f), ageYears = 20).decision
        )
    }

    // --- safety properties -------------------------------------------------

    @Test
    fun `missing rule for an unsafe category fails safe to COVER`() {
        // A category/band pair the matrix does not know about must never
        // degrade to ALLOW: fail closed, then be visible in the log.
        val incomplete = AgePolicy(
            tau = 0.5f,
            rules = mapOf(RiskCategory.GAMBLING to mapOf("10-12" to Decision.COVER)),
        )
        val r = incomplete.decide(scores(0.05f, 0.05f, 0.05f, 0.85f), ageYears = 15)
        assertEquals(Decision.COVER, r.decision)
        assertEquals("rule should be reported as missing", "rule-missing-safe-default", r.reason)
    }

    @Test
    fun `under-age children get the strictest band`() {
        val r = policy.decide(scores(0.05f, 0.05f, 0.05f, 0.85f), ageYears = 2)
        assertEquals(AgeBand.BANDS.first(), r.band)
        assertEquals(Decision.COVER, r.decision)
    }

    @Test
    fun `band boundaries resolve deterministically`() {
        assertEquals("3-5", AgeBand.of(3).id)
        assertEquals("3-5", AgeBand.of(5).id)
        assertEquals("6-9", AgeBand.of(6).id)
        assertEquals("10-12", AgeBand.of(12).id)
        assertEquals("13-17", AgeBand.of(17).id)
        assertEquals("18+", AgeBand.of(18).id)
    }

    @Test
    fun `vector order matches the category contract`() {
        // The model's output index -> category mapping is an ABI between the
        // training pipeline and the device; if it drifts, every decision flips.
        assertEquals(
            listOf(
                RiskCategory.SAFE_GENERAL,
                RiskCategory.ADULT_SEXUALIZED,
                RiskCategory.GAMBLING,
                RiskCategory.VIOLENCE,
            ),
            RiskCategory.ORDER
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects a score vector of the wrong size`() {
        policy.decide(floatArrayOf(1f, 0f), ageYears = 8)
    }
}
