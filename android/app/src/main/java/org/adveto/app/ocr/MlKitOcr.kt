package org.adveto.app.ocr

import android.graphics.Bitmap

/** Text recovered from one region, plus why it may be absent. */
data class OcrResult(
    val text: String,
    /** True when recognition exceeded its budget rather than finding nothing. */
    val timedOut: Boolean = false,
    val error: String? = null,
) {
    val isEmpty: Boolean get() = text.isBlank()
    /** Stage-(3b) fallback flag: pipeline must degrade to image-only. */
    val failed: Boolean get() = timedOut || error != null || isEmpty
}

/**
 * Stage (3b) seam between the pipeline and whatever recognises text.
 *
 * Abstracted for two reasons: the pilot runs ML Kit, while the offline
 * evaluation harness runs Tesseract over dataset crops on the host — both must
 * feed the same text classifier with the same contract.
 */
interface OcrReader {
    val available: Boolean
    fun read(region: Bitmap): OcrResult
    fun close() {}
}

/**
 * On-device OCR via Google ML Kit (bundled latin model, no network).
 *
 * [com.google.android.gms.tasks.Tasks.await] blocks the calling thread, which
 * is deliberate here: OCR runs on the capture worker thread — never the main
 * thread — and the pipeline wants a *synchronous* answer so the decision can
 * be logged in the same frame iteration. The timeout bounds worst-case
 * capture-to-decision latency, which is a reported metric: an OCR that hangs
 * would otherwise silently dominate the budget.
 */
class MlKitOcr(private val timeoutMs: Long = 400L) : OcrReader {

    private val recognizer =
        com.google.mlkit.vision.text.TextRecognition
            .getClient(com.google.mlkit.vision.text.latin.TextRecognizerOptions.DEFAULT_OPTIONS)

    override val available: Boolean = true

    override fun read(region: Bitmap): OcrResult {
        return try {
            val task = recognizer.process(com.google.mlkit.vision.common.InputImage.fromBitmap(region, 0))
            val visionText = com.google.android.gms.tasks.Tasks.await(
                task, timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS
            )
            OcrResult(visionText.textBlocks.joinToString("\n") { it.text })
        } catch (e: java.util.concurrent.TimeoutException) {
            OcrResult("", timedOut = true)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            OcrResult("", error = "interrupted")
        } catch (e: Exception) {
            OcrResult("", error = e.javaClass.simpleName)
        }
    }

    override fun close() {
        recognizer.close()
    }
}
