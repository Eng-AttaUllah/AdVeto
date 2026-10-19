package org.adveto.app.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Duration contract for the parent dashboard.
 *
 * These assert the *representation*, not a device: a frame's three region rows
 * must not be read as three sightings, a single sighting must refuse to quote
 * a span rather than reporting zero, and a legacy row must not collapse to the
 * epoch. Each one guards a number a parent could otherwise misread.
 */
class AdOccurrenceTest {

    private fun event(
        frameId: Long,
        pkg: String = "com.example.game",
        category: String = "GAMBLING",
        outcome: String = "abstain",
        modality: String = "pending-model",
        confidence: Float = 0f,
        regionSource: String = "BANNER_BOTTOM",
        detectMs: Double = 1.0,
        decisionMs: Double = 2.0,
        ageBand: String = "6-9",
        reason: String = "confidence 0.000 < tau 0.600",
    ) = Event(
        timestamp = frameId,
        packageName = pkg,
        category = category,
        confidence = confidence,
        outcome = outcome,
        reason = reason,
        modality = modality,
        regionSource = regionSource,
        detectMs = detectMs,
        decisionMs = decisionMs,
        ageBand = ageBand,
        frameId = frameId,
    )

    // --- one frame is one observation -------------------------------------

    @Test
    fun `three region rows from one frame count as a single sample`() {
        val frame = 1_000_000L
        val occ = groupOccurrences(listOf(event(frame), event(frame), event(frame))).single()

        assertEquals(1, occ.samples)
        assertFalse("a lone frame cannot bound a duration", occ.hasSpan)
        assertEquals("all rows are still kept for the detail dialog", 3, occ.events.size)
    }

    @Test
    fun `the region heuristic cannot inflate sample counts`() {
        val rows = buildList {
            repeat(3) { add(event(1_000L)) }   // frame 1
            repeat(3) { add(event(2_000L)) }   // frame 2
            repeat(3) { add(event(3_000L)) }   // frame 3
        }

        val occ = groupOccurrences(rows).single()
        assertEquals("nine rows, three moments", 3, occ.samples)
        assertEquals(9, occ.events.size)
    }

    // --- spans ---------------------------------------------------------------

    @Test
    fun `span is the distance between first and last sighting`() {
        val occ = groupOccurrences(listOf(event(1_000L), event(61_000L))).single()

        assertEquals(1_000L, occ.firstSeen)
        assertEquals(61_000L, occ.lastSeen)
        assertEquals(60_000L, occ.spanMs)
        assertEquals(2, occ.samples)
        assertTrue(occ.hasSpan)
    }

    @Test
    fun `a gap at exactly the threshold still belongs to the same occurrence`() {
        // The split is strict `>`, so a cadence landing precisely on the
        // threshold is not chopped in half.
        val occ = groupOccurrences(
            listOf(event(1_000L), event(1_000L + SESSION_GAP_MS))
        ).single()

        assertEquals(2, occ.samples)
        assertEquals(SESSION_GAP_MS, occ.spanMs)
        assertTrue(occ.hasSpan)
    }

    @Test
    fun `a gap beyond the threshold closes the run`() {
        val rows = listOf(event(1_000L), event(1_000L + SESSION_GAP_MS + 1))
        val occurrences = groupOccurrences(rows)

        assertEquals("two separate sightings, not one long one", 2, occurrences.size)
        occurrences.forEach { assertFalse("single frame each", it.hasSpan) }
    }

    // --- group keys ----------------------------------------------------------

    @Test
    fun `different applications never share an occurrence`() {
        val occurrences = groupOccurrences(
            listOf(event(1_000L, pkg = "com.a"), event(1_500L, pkg = "com.b"))
        )

        assertEquals(2, occurrences.size)
        assertEquals(setOf("com.a", "com.b"), occurrences.map { it.packageName }.toSet())
    }

    @Test
    fun `a category change starts a new occurrence even in the same app`() {
        val occurrences = groupOccurrences(
            listOf(
                event(1_000L, category = "GAMBLING"),
                event(1_500L, category = "VIOLENCE"),
            )
        )

        assertEquals(2, occurrences.size)
    }

    @Test
    fun `input order does not change the result`() {
        val ascending = listOf(event(1_000L), event(2_000L), event(3_000L))

        assertEquals(groupOccurrences(ascending), groupOccurrences(ascending.reversed()))
    }

    @Test
    fun `occurrences come back newest first`() {
        val occurrences = groupOccurrences(
            listOf(event(1_000L, pkg = "com.old"), event(9_000L, pkg = "com.new"))
        )

        assertEquals(listOf("com.new", "com.old"), occurrences.map { it.packageName })
    }

    // --- schema-v1 history ---------------------------------------------------

    @Test
    fun `legacy rows group by timestamp instead of collapsing to the epoch`() {
        fun legacyRow(at: Long) = Event(
            timestamp = at,
            packageName = "com.legacy",
            category = "GAMBLING",
            confidence = 0f,
            outcome = "abstain",
            reason = "pre-v2 row",
            modality = "pending-model",
            regionSource = "BANNER_BOTTOM",
            detectMs = 1.0,
            decisionMs = 2.0,
            ageBand = "6-9",
            frameId = 0L, // what schema v1 wrote before the column existed
        )

        val occ = groupOccurrences(listOf(legacyRow(5_000L), legacyRow(7_000L))).single()

        assertEquals("frameId 0 must fall back to timestamp, not to 0", 5_000L, occ.firstSeen)
        assertEquals(7_000L, occ.lastSeen)
        assertEquals(2, occ.samples)
        assertTrue("two sightings, not one", occ.hasSpan)
    }

    // --- the stub must never read as a verdict ------------------------------

    @Test
    fun `stub rows mark the occurrence unclassified`() {
        val occ = groupOccurrences(listOf(event(1_000L, modality = "pending-model"))).single()

        assertTrue("must not surface as SAFE_GENERAL", occ.unclassified)
    }

    @Test
    fun `a real modality leaves the category visible`() {
        val occurrences = groupOccurrences(
            listOf(
                event(1_000L, modality = "fusion"),
                event(2_000L, modality = "fusion"),
            )
        )

        assertFalse(occurrences.single().unclassified)
    }

    @Test
    fun `one stub row among real ones still withholds the category`() {
        // Conservative on purpose: a category half-derived from the stub is not
        // a category the system may put in front of a parent.
        val occ = groupOccurrences(
            listOf(
                event(1_000L, modality = "fusion"),
                event(2_000L, modality = "pending-model"),
            )
        ).single()

        assertTrue(occ.unclassified)
    }

    // --- presentation values -------------------------------------------------

    @Test
    fun `outcome comes from the most recent frame, not a vote`() {
        val occ = groupOccurrences(
            listOf(event(1_000L, outcome = "allow"), event(2_000L, outcome = "cover"))
        ).single()

        assertEquals("cover", occ.outcome)
    }

    @Test
    fun `confidence is the strongest sample rather than an average`() {
        val occ = groupOccurrences(
            listOf(event(1_000L, confidence = 0.9f), event(2_000L, confidence = 0.1f))
        ).single()

        assertEquals(0.9f, occ.confidence, 0.0001f)
    }

    @Test
    fun `region sources are deduplicated across frames`() {
        val occurrences = groupOccurrences(
            listOf(
                event(1_000L, regionSource = "MREC"),
                event(2_000L, regionSource = "MREC"),
                event(3_000L, regionSource = "BANNER_BOTTOM"),
            )
        ).single()

        assertEquals(listOf("BANNER_BOTTOM", "MREC"), occurrences.regionSources)
    }

    @Test
    fun `empty input yields nothing`() {
        assertTrue(groupOccurrences(emptyList()).isEmpty())
    }

    @Test
    fun `span formatting is stable`() {
        assertEquals("0 s", formatSpan(0))
        assertEquals("0 s", formatSpan(-5))
        assertEquals("29 s", formatSpan(29_000))
        assertEquals("1 m 05 s", formatSpan(65_000))
        assertEquals("4 m 53 s", formatSpan(293_000))
    }
}
