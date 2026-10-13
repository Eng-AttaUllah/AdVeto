package org.adveto.app.pipeline

import android.graphics.Bitmap
import org.adveto.app.detect.AdRegionDetector
import org.adveto.app.log.Event
import org.adveto.app.policy.AgePolicy
import org.adveto.app.policy.PolicyResult
import org.adveto.app.policy.RiskCategory

/**
 * Stages (3a)/(3b) — the classification seam.
 *
 * The Android side never knows *which* model it is running, only that it maps
 * a region crop to four risk scores. That keeps the device build independent
 * of the training stack and lets Batch 4 swap in whichever model won the
 * benchmark (float or int8) without touching the pipeline.
 */
interface Classifier {
    /** Identifies the artifact in the event log (float/int8/stub...). */
    val name: String

    /** True once a real artifact is loaded; false keeps the path honest. */
    val available: Boolean

    /** @return four scores in [RiskCategory.ORDER] order. */
    fun classify(region: Bitmap): FloatArray
}

/**
 * Placeholder used until Batch 4 ships a trained artifact.
 *
 * It returns an all-zero vector, which the policy must turn into ABSTAIN —
 * that is the correct and *testable* behaviour: with no model the system must
 * never claim an ad is safe. Wiring it in exercises the full seven-stage path
 * (capture → detect → classify → fuse → policy → log) before any model exists,
 * and every event honestly reads "pending-model".
 */
object PendingModelClassifier : Classifier {
    override val name: String = "pending-model"
    override val available: Boolean = false

    override fun classify(region: Bitmap): FloatArray =
        FloatArray(RiskCategory.ORDER.size)
}

/**
 * Stage (4) fusion + orchestration.
 *
 * With only one modality available the weighted average collapses to that
 * modality's vector — the same code path Batch 4 uses once OCR/text arrives,
 * where `p = w_img * p_img + w_txt * p_txt`. The fusion ablation later
 * toggles these weights, not this structure.
 */
class Pipeline(
    private val classifier: Classifier = PendingModelClassifier,
    private val policy: AgePolicy = AgePolicy.default(),
    private val fusionWeights: FloatArray = floatArrayOf(1f, 0f), // img, txt
) {

    data class Result(
        val events: List<Event>,
        val detectMs: Double,
        val regions: Int,
    )

    fun process(
        frame: Bitmap,
        regions: List<AdRegionDetector.Region>,
        ageYears: Int,
        density: Float,
        packageName: String,
    ): Result {
        val started = System.nanoTime()
        val events = regions.map { region ->
            val policyResult: PolicyResult = if (!classifier.available) {
                // No model: run the policy anyway so the abstain path is live.
                policy.decide(classifier.classify(frame), ageYears)
            } else {
                val pImg = classifier.classify(crop(frame, region))
                val fused = fuse(pImg, FloatArray(pImg.size)) // text vector empty
                policy.decide(fused, ageYears)
            }

            Event(
                timestamp = System.currentTimeMillis(),
                packageName = packageName,
                category = policyResult.category.name,
                confidence = policyResult.confidence,
                outcome = policyResult.decision.name.lowercase(),
                reason = policyResult.reason,
                modality = if (classifier.available) "image" else "pending-model",
                regionSource = region.source.name,
                detectMs = 0.0,
                decisionMs = (System.nanoTime() - started) / 1_000_000.0,
                ageBand = policyResult.band.id,
            )
        }
        val detectMs = (System.nanoTime() - started) / 1_000_000.0
        return Result(events, detectMs, regions.size)
    }

    /** Weighted score-level fusion (proposal §7 stage 4). */
    private fun fuse(image: FloatArray, text: FloatArray): FloatArray {
        val wImg = fusionWeights.getOrElse(0) { 1f }
        val wTxt = fusionWeights.getOrElse(1) { 0f }
        val total = (wImg + wTxt).takeIf { it > 0f } ?: 1f
        return FloatArray(image.size) { i ->
            (wImg * image[i] + wTxt * text.getOrElse(i) { 0f }) / total
        }
    }

    private fun crop(frame: Bitmap, region: AdRegionDetector.Region): Bitmap {
        val r = region.rect
        val left = r.left.coerceIn(0, frame.width - 1)
        val top = r.top.coerceIn(0, frame.height - 1)
        val w = r.width().coerceIn(1, frame.width - left)
        val h = r.height().coerceIn(1, frame.height - top)
        return Bitmap.createBitmap(frame, left, top, w, h)
    }
}
