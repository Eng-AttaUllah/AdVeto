package org.adveto.app.pipeline

import android.graphics.Bitmap
import org.adveto.app.detect.AdRegionDetector
import org.adveto.app.log.Event
import org.adveto.app.ocr.OcrReader
import org.adveto.app.ocr.OcrResult
import org.adveto.app.policy.AgePolicy
import org.adveto.app.policy.PolicyResult
import org.adveto.app.policy.RiskCategory

/**
 * Stages (3a)/(3b) — the classification seams.
 *
 * The Android side never knows *which* model it is running, only that it maps
 * a region (or a string) to four risk scores. That keeps the device build
 * independent of the training stack and lets Batch 4 swap in whichever model
 * won the benchmark (float or int8) without touching the pipeline.
 */
interface Classifier {
    /** Identifies the artifact in the event log (float/int8/stub...). */
    val name: String

    /** True once a real artifact is loaded; false keeps the path honest. */
    val available: Boolean

    /** @return four scores in [RiskCategory.ORDER] order. */
    fun classify(region: Bitmap): FloatArray
}

interface TextClassifier {
    val name: String
    val available: Boolean

    /** @return four scores in [RiskCategory.ORDER] order. */
    fun classify(text: String): FloatArray
}

/**
 * Placeholder used until Batch 4 ships trained artifacts. Both return
 * all-zero vectors, which the policy must turn into ABSTAIN — correct and
 * *testable*: with no model the system must never claim an ad is safe.
 * Wiring these in exercises the full seven-stage path before any model exists,
 * and every logged event honestly reads `pending-model`.
 */
object PendingModelClassifier : Classifier {
    override val name: String = "pending-model"
    override val available: Boolean = false
    override fun classify(region: Bitmap): FloatArray =
        FloatArray(RiskCategory.ORDER.size)
}

object PendingTextClassifier : TextClassifier {
    override val name: String = "pending-text-model"
    override val available: Boolean = false
    override fun classify(text: String): FloatArray =
        FloatArray(RiskCategory.ORDER.size)
}

/**
 * Stages (3)-(5) — orchestration and score-level fusion.
 *
 * Fusion is the weighted average of the proposal §7 stage (4):
 *     p = (w_img * p_img + w_txt * p_txt) / (w_img + w_txt)
 * weights tuned on validation data; the logistic fusion is evaluated later as
 * an ablation and will be a second implementation of the same seam.
 *
 * Modality is decided by what actually happened, never assumed:
 *   `pending-model`  no artifact yet
 *   `image-only`     OCR returned nothing (empty / failed / timed out)
 *   `image`          OCR worked but no text model
 *   `fusion`         both paths contributed
 */
class Pipeline(
    private val classifier: Classifier = PendingModelClassifier,
    private val textClassifier: TextClassifier = PendingTextClassifier,
    private val ocr: OcrReader? = null,
    private val policy: AgePolicy = AgePolicy.default(),
    private val fusionWeights: FloatArray = floatArrayOf(0.6f, 0.4f), // img, txt
) {

    data class Result(
        val events: List<Event>,
        /** Localisation + classification + policy, end to end, ms. */
        val decisionMs: Double,
        val regions: Int,
        /**
         * Candidates where OCR actually recovered text.
         *
         * This exists because the geometry heuristic proposes the same three
         * slots on *every* frame, so a raw `regions > 0` count is vacuous — it
         * would "pass" with no advertisement on screen at all. A candidate
         * that simultaneously sits at a banner position *and* carries recognised
         * text is the honest interim signal that something ad-like was
         * localised. It is still a proxy: only the accessibility / learned
         * detector work turns it into a real localisation measurement.
         */
        val textHits: Int,
    )

    fun process(
        frame: Bitmap,
        regions: List<AdRegionDetector.Region>,
        ageYears: Int,
        packageName: String,
        detectMs: Double = 0.0,
    ): Result {
        val started = System.nanoTime()
        var textHits = 0

        val events = regions.map { region ->
            val crop = crop(frame, region)

            val pImg = classifier.classify(crop)

            // Stage (3b): OCR is attempted per region and is allowed to fail.
            val ocrResult: OcrResult = ocr?.read(crop)
                ?: OcrResult("", error = "no-reader")
            if (!ocrResult.isEmpty) textHits++

            val useText = textClassifier.available && !ocrResult.isEmpty
            val pTxt = if (useText) textClassifier.classify(ocrResult.text)
            else FloatArray(pImg.size)

            val fused = if (classifier.available) fuse(pImg, pTxt) else pImg
            val policyResult: PolicyResult = policy.decide(fused, ageYears)

            val modality = when {
                !classifier.available -> "pending-model"
                ocrResult.failed -> "image-only"
                !textClassifier.available -> "image"
                else -> "fusion"
            }

            val reason = when {
                !classifier.available -> "model-not-integrated; " + policyResult.reason
                ocrResult.failed -> buildString {
                    append("ocr=")
                    append(
                        when {
                            ocrResult.timedOut -> "timeout"
                            ocrResult.error != null -> "error:${ocrResult.error}"
                            else -> "empty"
                        }
                    )
                    append("; ")
                    append(policyResult.reason)
                }
                else -> policyResult.reason
            }

            Event(
                timestamp = System.currentTimeMillis(),
                packageName = packageName,
                category = policyResult.category.name,
                confidence = policyResult.confidence,
                outcome = policyResult.decision.name.lowercase(),
                reason = reason,
                modality = modality,
                regionSource = region.source.name,
                detectMs = detectMs,
                decisionMs = (System.nanoTime() - started) / 1_000_000.0,
                ageBand = policyResult.band.id,
            )
        }

        return Result(
            events,
            (System.nanoTime() - started) / 1_000_000.0,
            regions.size,
            textHits,
        )
    }

    /** Weighted score-level fusion (proposal §7, stage 4). */
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
        val left = r.left.coerceIn(0, (frame.width - 1).coerceAtLeast(0))
        val top = r.top.coerceIn(0, (frame.height - 1).coerceAtLeast(0))
        val w = r.width().coerceIn(1, frame.width - left)
        val h = r.height().coerceIn(1, frame.height - top)
        return Bitmap.createBitmap(frame, left, top, w, h)
    }
}
