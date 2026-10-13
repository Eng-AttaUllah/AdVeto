package org.adveto.app.detect

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.DisplayMetrics

/**
 * Stage (2) — ad-region detection.
 *
 * Returns *candidate* advertisement regions for a captured frame, each tagged
 * with how it was proposed. An empty result would mean "could not be
 * localised", which the pipeline records as `not-processed` — never as safe
 * (proposal §7).
 *
 * Why heuristics first: the proposal frames this stage as object detection
 * [8][9], but training a detector is the riskiest item on a CPU-only build
 * machine and the Week 1-2 feasibility gate must run *before* dataset work
 * begins. Staged plan:
 *
 *   1. (this) banner geometry — the dominant in-app ad formats have stable
 *      placements: 320x50 / 320x100 banners pinned top or bottom, and the
 *      300x250 "MREC" rectangle. Zero training cost, deterministic, testable.
 *   2. Batch 2c — OCR-cluster proposals (text density localises native ads)
 *      and AccessibilityService ad-view bounds from known ad-SDK view classes.
 *   3. Batch 4 — if schedule allows, a small learned detector trained on the
 *      bounding boxes recorded while building the dataset: every region crop
 *      keeps its source rectangle, so boxes come for free.
 *
 * Ordering constraint worth stating: detection quality is reported as
 * "localised within the capture window" and sits *outside* the image/text/
 * fusion benchmark, so a weak detector degrades coverage — not accuracy.
 */
object AdRegionDetector {

    /** How a region was proposed — recorded with every event for the report. */
    enum class Source { BANNER_BOTTOM, BANNER_TOP, MREC, FULL_BLEED }

    data class Region(val rect: Rect, val source: Source)

    /** Minimum banner height in dp — below this it is UI chrome, not an ad. */
    private const val MIN_BANNER_DP = 40
    private const val MAX_BANNER_DP = 110

    /** MREC nominal size 300x250 (dp). */
    private const val MREC_W_DP = 300
    private const val MREC_H_DP = 250

    /** Gap from the screen edge (dp) still considered "pinned". */
    private const val PIN_TOLERANCE_DP = 8

    /**
     * @param displayDensity px-per-dp of the *display the frame was captured
     *        from*. Screen-capture bitmaps do not reliably carry display
     *        density, and the whole geometry is in dp, so the caller (service)
     *        passes its own `resources.displayMetrics.density`.
     */
    fun detect(frame: Bitmap, displayDensity: Float? = null): List<Region> {
        val d = densityOf(frame, displayDensity)
        val w = frame.width
        val h = frame.height
        val minBanner = (MIN_BANNER_DP * d).toInt()
        val maxBanner = (MAX_BANNER_DP * d).toInt()
        val pin = (PIN_TOLERANCE_DP * d).toInt()

        val regions = mutableListOf<Region>()

        // --- bottom banner (the most common placement by far) -------------
        if (h - maxBanner > pin) {
            regions.add(Region(Rect(0, h - minBanner.coerceAtMost(h), w, h), Source.BANNER_BOTTOM))
        }

        // --- top banner ----------------------------------------------------
        if (h > maxBanner + pin) {
            regions.add(Region(Rect(0, 0, w, minBanner.coerceAtMost(h)), Source.BANNER_TOP))
        }

        // --- 300x250 MREC, horizontally centred, lower half ---------------
        val mrecW = (MREC_W_DP * d).toInt().coerceAtMost(w)
        val mrecH = (MREC_H_DP * d).toInt().coerceAtMost(h)
        if (mrecW > 0 && mrecH > 0) {
            val left = (w - mrecW) / 2
            val top = ((h - mrecH) / 2 + (h - mrecH) / 4).coerceAtLeast(0)
            regions.add(Region(Rect(left, top, left + mrecW, (top + mrecH).coerceAtMost(h)), Source.MREC))
        }

        // --- full-bleed fallback -------------------------------------------
        // A full-screen interstitial IS the frame. Proposing it keeps the
        // pipeline honest: it is localisable, so it must be classified rather
        // than quietly dropped as not-processed.
        if (regions.isEmpty()) {
            regions.add(Region(Rect(0, 0, w, h), Source.FULL_BLEED))
        }

        return regions.filter { it.rect.width() > 0 && it.rect.height() > 0 }
    }

    private fun densityOf(frame: Bitmap, override: Float?): Float {
        if (override != null && override > 0f) return override
        if (frame.density > DisplayMetrics.DENSITY_DEFAULT) return frame.density / 160f
        return inferDensity(frame)
    }

    /** Derive px-per-dp from frame size when the bitmap carries no density. */
    private fun inferDensity(frame: Bitmap): Float = when {
        frame.width >= 1440 -> 3.5f
        frame.width >= 1080 -> 2.75f
        frame.width >= 720 -> 2.0f
        else -> 1.5f
    }
}
