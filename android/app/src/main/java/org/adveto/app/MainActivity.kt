package org.adveto.app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
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
    }

    private lateinit var status: TextView
    private lateinit var captureBtn: Button
    private lateinit var overlayBtn: Button

    private val ui = Handler(Looper.getMainLooper())
    private var capturing = false

    private var frameCount = 0
    private var lastRegionCount = 0
    private var lastDetectMs = 0.0
    private var captureState = "not-started"
    private var captureReason = "-"
    private var overlayResult = "-"

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
                    lastRegionCount = intent.getIntExtra(ScreenCaptureService.EXTRA_REGION_COUNT, 0)
                    lastDetectMs = intent.getDoubleExtra(ScreenCaptureService.EXTRA_FRAME_MS, 0.0)
                    if (capturing) captureState = "running"
                    render()
                }
                ScreenCaptureService.ACTION_CAPTURE_STATE -> {
                    captureState = intent.getStringExtra(ScreenCaptureService.EXTRA_STATE) ?: "?"
                    captureReason = intent.getStringExtra(ScreenCaptureService.EXTRA_REASON) ?: "-"
                    capturing = captureState == "running"
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

        status = findViewById(R.id.statusText)
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
        val gate = buildString {
            append("GO/NO-GO probe\n")
            append("1. capture .............. $captureState ($captureReason)\n")
            append("2. frames processed ..... $frameCount  last detect %.1f ms\n"
                .format(lastDetectMs))
            append("   regions in last frame  $lastRegionCount\n")
            append("3. overlay probe ........ $overlayResult")
        }
        status.text = getString(R.string.status_format, gate)

        captureBtn.text = if (capturing) getString(R.string.btn_capturing)
        else getString(R.string.btn_start_capture)
        overlayBtn.text = if (OverlayService.canDraw(this)) getString(R.string.btn_overlay_ok)
        else getString(R.string.btn_allow_overlay)
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
