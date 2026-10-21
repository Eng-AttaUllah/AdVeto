package org.adveto.app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import org.adveto.app.capture.ScreenCaptureService
import org.adveto.app.overlay.OverlayService

/**
 * Batch-2 pilot UI: everything the Week 1-2 feasibility gate needs to be
 * pressed by a human on a real device.
 *
 * GO/NO-GO checklist (proposal §9), one control each:
 *   1. MediaProjection authorisation obtained *and sustained* → "Start capture"
 *      then leave it running; frames must keep arriving (1 Hz) and revocation
 *      must surface as an explicit state, not silence.
 *   2. An advertisement region localised within the capture window → frame
 *      broadcasts show `regions > 0`.
 *   3. An overlay draws over another application's advertisement → "Test
 *      overlay" while capture is running over a third-party app.
 */
class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "AdVetoUI"

        /** Window for the operator to reach the ad-heavy app before the probe. */
        private const val OVERLAY_DELAY_MS = 12_000L

        /**
         * Sustain threshold for GO-1. The proposal's claim is that authorisation
         * is not merely *obtained* but *held*; a projection that dies after a
         * few seconds says nothing about foreground-service stability, so a
         * short capture is reported as in-progress rather than as a pass.
         */
        private const val SUSTAIN_MS = 60_000L

        /**
         * How much of a sustained window may pass without a single frame
         * before coverage stops counting as observed.
         *
         * Sampling is event-driven — `FRAME_INTERVAL_MS` is a ceiling, not a
         * 1 Hz promise — so short quiet stretches on a still screen are
         * correct behaviour and must not fail the gate. The allowance exists
         * to catch the opposite failure: a window that was *touched* by frames
         * at the start but not covered by them, which is exactly what a wall
         * clock cannot distinguish from healthy delivery.
         */
        private const val DELIVERY_GAP_MS = 15_000L

        /**
         * Per-frame latency budget, taken from the capture interval itself: the
         * service requests one frame per second, so a frame that costs more
         * than 1000 ms of detection + policy cannot sustain the rate it was
         * asked for. Colouring the metric against this makes an over-budget
         * detector visible on the pilot device rather than buried in logcat.
         */
        private const val FRAME_BUDGET_MS = 1000.0
    }

    private lateinit var stateChip: TextView
    private lateinit var captureReasonText: TextView
    private lateinit var framesValue: TextView
    private lateinit var latencyValue: TextView
    private lateinit var hitsValue: TextView
    private lateinit var uptimeValue: TextView
    private lateinit var gate1Value: TextView
    private lateinit var gate2Value: TextView
    private lateinit var gate3Value: TextView
    private lateinit var captureBtn: Button
    private lateinit var overlayBtn: Button

    private val ui = Handler(Looper.getMainLooper())
    private var capturing = false

    private var frameCount = 0
    // Wall-clock stamp of the first and most recent frame of the *current*
    // session. GO-1 needs the span frames cover, not merely their count: a
    // service that ran for 60 s while frames arrived only in the opening two
    // of them has proved nothing about sustained delivery, and a bare timer
    // cannot tell those two cases apart.
    private var firstFrameAtMs = 0L
    private var lastFrameAtMs = 0L
    private var lastRegionCount = 0
    private var lastTextHits = 0
    private var lastDetectMs = 0.0
    private var captureState = "not-started"
    private var captureReason = "-"
    private var overlayResult = "-"

    // --- gate bookkeeping --------------------------------------------------
    // GO-1 is a *sustained* claim, so elapsed time is part of the evidence: a
    // capture that dies at 12 s must not read as a pass. GO-2 is remembered as
    // "ever observed" because the proposal only claims one localised frame
    // inside the window. GO-3 is recorded only when the cover was genuinely
    // drawn — not when the probe was merely armed.
    private var captureStartedAtMs = 0L
    private var lastUptimeMs = 0L
    private var gate2Observed = false
    private var gate3ShownAtMs = 0L

    // --- consent for screen capture (stage 1) -----------------------------
    private val captureLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK && result.data != null) {
                val intent = Intent(this, ScreenCaptureService::class.java).apply {
                    action = ScreenCaptureService.ACTION_START
                    putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                    putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, result.data)
                }
                ContextCompat.startForegroundService(this, intent)
            } else {
                // Capture denial is a first-class state, never an error path.
                captureState = "denied"
                captureReason = "user-declined-consent"
                render()
            }
        }

    // --- notifications (API 33+) ------------------------------------------
    private val notifLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            Log.i(TAG, "POST_NOTIFICATIONS granted=$granted")
        }

    // --- broadcasts from the pipeline --------------------------------------
    private val pipelineReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ScreenCaptureService.ACTION_FRAME_PROCESSED -> {
                    frameCount++
                    val receivedAtMs = System.currentTimeMillis()
                    if (firstFrameAtMs == 0L) firstFrameAtMs = receivedAtMs
                    lastFrameAtMs = receivedAtMs
                    lastRegionCount = intent.getIntExtra(ScreenCaptureService.EXTRA_REGION_COUNT, 0)
                    lastTextHits = intent.getIntExtra(ScreenCaptureService.EXTRA_TEXT_HITS, 0)
                    lastDetectMs = intent.getDoubleExtra(ScreenCaptureService.EXTRA_FRAME_MS, 0.0)
                    // Latched, never cleared: GO-2 claims one localised frame
                    // inside the window, so a later clean frame must not
                    // un-observe it. The per-frame value still drives the
                    // live metric above.
                    if (lastTextHits > 0) gate2Observed = true
                    if (capturing) captureState = "running"
                    render()
                }
                ScreenCaptureService.ACTION_CAPTURE_STATE -> {
                    captureState = intent.getStringExtra(ScreenCaptureService.EXTRA_STATE) ?: "?"
                    captureReason = intent.getStringExtra(ScreenCaptureService.EXTRA_REASON) ?: "-"
                    val wasCapturing = capturing
                    capturing = captureState == "running"
                    if (capturing && !wasCapturing) {
                        // Fresh session: reset the sustain clock and the
                        // per-session counters, but keep gate2Observed so a
                        // second probe cannot erase a first probe's result.
                        captureStartedAtMs = System.currentTimeMillis()
                        lastUptimeMs = 0L
                        // The comment above promises per-session counters, but
                        // frameCount never actually reset — a restart silently
                        // carried the previous run's total into the next run's
                        // GO-1 evidence. Stamped with it are the two marks that
                        // make the frame span meaningful for this session only.
                        frameCount = 0
                        firstFrameAtMs = 0L
                        lastFrameAtMs = 0L
                    } else if (!capturing && wasCapturing) {
                        captureStartedAtMs.takeIf { it != 0L }?.let {
                            lastUptimeMs = System.currentTimeMillis() - it
                        }
                    }
                    render()
                }
                "org.adveto.app.OVERLAY_RESULT" -> {
                    overlayResult = intent.getStringExtra(OverlayService.EXTRA_RESULT) ?: "?"
                    render()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        stateChip = findViewById(R.id.stateChip)
        captureReasonText = findViewById(R.id.captureReasonText)
        framesValue = findViewById(R.id.framesValue)
        latencyValue = findViewById(R.id.latencyValue)
        hitsValue = findViewById(R.id.hitsValue)
        uptimeValue = findViewById(R.id.uptimeValue)
        gate1Value = findViewById(R.id.gate1Value)
        gate2Value = findViewById(R.id.gate2Value)
        gate3Value = findViewById(R.id.gate3Value)
        captureBtn = findViewById(R.id.captureButton)
        overlayBtn = findViewById(R.id.overlayButton)
        val stopBtn = findViewById<Button>(R.id.stopButton)
        val overlayTestBtn = findViewById<Button>(R.id.overlayTestButton)

        captureBtn.setOnClickListener { requestCapture() }
        stopBtn.setOnClickListener { stopCapture() }
        overlayBtn.setOnClickListener { requestOverlayPermission() }
        overlayTestBtn.setOnClickListener { testOverlay() }
        findViewById<Button>(R.id.dashboardButton).setOnClickListener {
            startActivity(Intent(this, org.adveto.app.dashboard.DashboardActivity::class.java))
        }

        requestNotificationPermission()
        render()
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(
            this, pipelineReceiver, pipelineFilter(), ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onStop() {
        unregisterReceiver(pipelineReceiver)
        super.onStop()
    }

    private fun pipelineFilter() = IntentFilter().apply {
        addAction(ScreenCaptureService.ACTION_FRAME_PROCESSED)
        addAction(ScreenCaptureService.ACTION_CAPTURE_STATE)
        addAction("org.adveto.app.OVERLAY_RESULT")
    }

    // --- actions ----------------------------------------------------------

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun requestCapture() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        captureLauncher.launch(mpm.createScreenCaptureIntent())
    }

    private fun stopCapture() {
        sendBroadcast(Intent(ScreenCaptureService.ACTION_CAPTURE_STATE).apply {
            setPackage(packageName)
            putExtra(ScreenCaptureService.EXTRA_STATE, "stopped")
            putExtra(ScreenCaptureService.EXTRA_REASON, "user-request")
        })
        stopService(Intent(this, ScreenCaptureService::class.java))
        capturing = false
        captureState = "stopped"
        captureReason = "user-request"
        // Freeze the sustain clock here as well: stopping is a deliberate end,
        // and leaving the clock running would let a later render overstate the
        // duration that was actually observed.
        captureStartedAtMs.takeIf { it != 0L }?.let {
            lastUptimeMs = System.currentTimeMillis() - it
        }
        render()
    }

    private fun requestOverlayPermission() {
        if (!OverlayService.canDraw(this)) {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        } else {
            overlayResult = "permission-granted"
            render()
        }
    }

    /**
     * Stage (6) probe: cover the bottom banner slot for 2.5 s — but on a
     * delay, because pressing the button puts *this* app in the foreground and
     * an overlay drawn immediately would cover our own screen rather than the
     * third-party advertisement. The delay is what makes the cross-application
     * claim actually testable: switch to the ad-heavy app during the window
     * and the cover paints over it there.
     *
     * @return delay in ms so the UI can tell the operator what to do.
     */
    private fun testOverlay(): Long {
        if (!OverlayService.canDraw(this)) {
            overlayResult = "no-permission — tap 'Allow overlay' first"
            render()
            return 0L
        }
        val dm = resources.displayMetrics
        val h = (50 * dm.density).toInt()          // 50dp banner band
        val w = dm.widthPixels

        overlayResult = "firing in ${OVERLAY_DELAY_MS / 1000} s — switch to the ad app now"
        render()

        ui.postDelayed({
            startOverlay(
                OverlayService.ACTION_SHOW,
                rect = intArrayOf(0, dm.heightPixels - h, w, dm.heightPixels),
                label = "AdVeto — covered (gate probe)"
            )
            // Recorded only now, not when the button was pressed: arming the
            // probe proves nothing, and GO-3 is a claim that the cover was
            // actually drawn. startOverlay() has already swallowed a start
            // failure into overlayResult, so a failed start must not latch.
            if (!overlayResult.startsWith("failed")) {
                gate3ShownAtMs = System.currentTimeMillis()
            }
            overlayResult = "shown — did it cover the ad? (hide in 2.5 s)"
            render()

            ui.postDelayed({
                startOverlay(OverlayService.ACTION_HIDE)
                overlayResult = "shown + hidden — record PASS if it covered the ad"
                render()
            }, 2500L)
        }, OVERLAY_DELAY_MS)

        return OVERLAY_DELAY_MS
    }

    /**
     * Direct service start rather than a broadcast: the overlay must be
     * drawable while this activity is *not* foreground (that is the whole
     * point of the probe), and an explicit startService to our own component
     * survives that as long as the capture foreground service keeps the
     * process privileged. Failure is surfaced, never swallowed.
     */
    private fun startOverlay(action: String, rect: IntArray? = null, label: String? = null) {
        try {
            val i = Intent(this, OverlayService::class.java).setAction(action)
            if (rect != null && rect.size == 4) {
                i.putExtra(OverlayService.EXTRA_LEFT, rect[0])
                i.putExtra(OverlayService.EXTRA_TOP, rect[1])
                i.putExtra(OverlayService.EXTRA_RIGHT, rect[2])
                i.putExtra(OverlayService.EXTRA_BOTTOM, rect[3])
            }
            label?.let { i.putExtra(OverlayService.EXTRA_LABEL, it) }
            startService(i)
        } catch (e: Exception) {
            Log.w(TAG, "overlay start failed: ${e.message}")
            overlayResult = "failed: ${e.javaClass.simpleName}"
            render()
        }
    }

    // --- rendering ---------------------------------------------------------

    private fun render() {
        // --- pipeline card ---
        val chipColour = when {
            capturing -> R.color.stateRunning
            captureState == "denied" || captureState == "failed" -> R.color.stateError
            captureState == "stopped" || captureState == "revoked" -> R.color.stateWarn
            else -> R.color.stateIdle
        }
        stateChip.background = stateChip.background.apply {
            (this as? GradientDrawable)?.setColor(ContextCompat.getColor(this@MainActivity, chipColour))
        }
        stateChip.text = captureState
        captureReasonText.text = captureReason

        framesValue.text = frameCount.toString()
        latencyValue.text = if (frameCount > 0) "%.0f".format(lastDetectMs) else "—"
        latencyValue.setTextColor(
            ContextCompat.getColor(
                this,
                // A per-frame budget is 1000 ms (the capture interval); over
                // that the detector cannot keep the rate it is asked for.
                when {
                    frameCount == 0 -> R.color.textPrimary
                    lastDetectMs > FRAME_BUDGET_MS -> R.color.stateError
                    lastDetectMs > FRAME_BUDGET_MS * 0.6 -> R.color.stateWarn
                    else -> R.color.stateRunning
                }
            )
        )
        hitsValue.text = lastTextHits.toString()
        uptimeValue.text = formatDuration(currentUptimeMs())

        // --- gate card ---
        // Each verdict is stated from what was actually observed. Nothing here
        // reports a pass on the strength of an intent being sent.
        val uptime = currentUptimeMs()
        // The span the frames themselves cover. Guarded so a single frame can
        // never produce a duration: one mark is a point, not an interval.
        val frameSpanMs = if (frameCount >= 2) lastFrameAtMs - firstFrameAtMs else 0L
        when {
            uptime == 0L -> {
                gate1Value.text = getString(R.string.gate_value_idle)
                gate1Value.setTextColor(ContextCompat.getColor(this, R.color.textSecondary))
            }
            uptime >= SUSTAIN_MS -> {
                // Two facts, stated apart. This line used to read "observed —
                // held X" from the timer alone, so a service that survived the
                // full window while pushing one frame would still have looked
                // like a pass. Sustained delivery means frames actually span
                // the window — and that the window was *covered* by frames,
                // not merely touched at both ends.
                val uncoveredMs = (uptime - frameSpanMs).coerceAtLeast(0L)
                val deliverySustained = frameSpanMs >= SUSTAIN_MS
                val coverageOk = deliverySustained && uncoveredMs <= DELIVERY_GAP_MS
                gate1Value.text = when {
                    frameCount == 0 ->
                        "service held ${formatDuration(uptime)}; no frame arrived — " +
                            "capture unproven"
                    coverageOk ->
                        "observed — held ${formatDuration(uptime)}; " +
                            "$frameCount frames spanning ${formatDuration(frameSpanMs)}"
                    deliverySustained ->
                        "service held ${formatDuration(uptime)}; frames span " +
                            "${formatDuration(frameSpanMs)} — " +
                            "${formatDuration(uncoveredMs)} uncovered"
                    else ->
                        "service held ${formatDuration(uptime)} but " +
                            "$frameCount frame(s) span ${formatDuration(frameSpanMs)} — " +
                            "capture unproven"
                }
                gate1Value.setTextColor(
                    ContextCompat.getColor(
                        this,
                        when {
                            coverageOk -> R.color.stateRunning
                            frameCount == 0 -> R.color.stateError
                            else -> R.color.stateWarn
                        }
                    )
                )
            }
            capturing -> {
                gate1Value.text =
                    "in progress — ${formatDuration(uptime)} of ${formatDuration(SUSTAIN_MS)}" +
                        " · $frameCount frame(s)"
                gate1Value.setTextColor(ContextCompat.getColor(this, R.color.stateWarn))
            }
            else -> {
                gate1Value.text = "not sustained — ended at ${formatDuration(uptime)}" +
                    " · $frameCount frame(s) spanning ${formatDuration(frameSpanMs)}"
                gate1Value.setTextColor(ContextCompat.getColor(this, R.color.stateError))
            }
        }

        if (gate2Observed) {
            gate2Value.text = "observed — text recognised in an ad-positioned slot"
            gate2Value.setTextColor(ContextCompat.getColor(this, R.color.stateRunning))
        } else if (capturing) {
            gate2Value.text = "pending — $lastRegionCount slot(s) proposed, no text-hit yet"
            gate2Value.setTextColor(ContextCompat.getColor(this, R.color.stateWarn))
        } else {
            gate2Value.text = getString(R.string.gate_value_idle)
            gate2Value.setTextColor(ContextCompat.getColor(this, R.color.textSecondary))
        }

        // The service can only confirm the cover was drawn. Whether it landed on
        // a real advertisement needs a human, so GO-3 stays provisional until
        // the operator confirms it — it is never shown as an outright pass.
        when {
            gate3ShownAtMs != 0L -> {
                gate3Value.text = "cover drawn — confirm it painted over the ad"
                gate3Value.setTextColor(ContextCompat.getColor(this, R.color.stateWarn))
            }
            overlayResult.startsWith("no-permission") -> {
                gate3Value.text = "blocked — overlay permission not granted"
                gate3Value.setTextColor(ContextCompat.getColor(this, R.color.stateError))
            }
            else -> {
                gate3Value.text = getString(R.string.gate_value_idle)
                gate3Value.setTextColor(ContextCompat.getColor(this, R.color.textSecondary))
            }
        }

        // --- controls ---
        captureBtn.text = if (capturing) getString(R.string.btn_capturing)
        else getString(R.string.btn_start_capture)
        overlayBtn.text = if (OverlayService.canDraw(this)) getString(R.string.btn_overlay_ok)
        else getString(R.string.btn_allow_overlay)
    }

    /** Live while capturing, frozen at the session's end otherwise. */
    private fun currentUptimeMs(): Long = when {
        capturing && captureStartedAtMs != 0L -> System.currentTimeMillis() - captureStartedAtMs
        else -> lastUptimeMs
    }

    private fun formatDuration(ms: Long): String {
        if (ms <= 0L) return "0 s"
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return if (minutes > 0) "%d m %02d s".format(minutes, seconds) else "$totalSeconds s"
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
