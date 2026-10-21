package org.adveto.app.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import androidx.core.app.NotificationCompat
import org.adveto.app.R
import org.adveto.app.config.AppPrefs
import org.adveto.app.detect.AdRegionDetector
import org.adveto.app.log.EventLogger
import org.adveto.app.ocr.MlKitOcr
import org.adveto.app.pipeline.Pipeline
import org.adveto.app.source.ForegroundApp

/**
 * Stage (1) — screen capture.
 *
 * A foreground service of type `mediaProjection` (required from API 34) that
 * owns the [MediaProjection] consent grant and renders frames into an
 * [ImageReader]. Frames are throttled to [FRAME_INTERVAL_MS] because the
 * pipeline is a research prototype measuring *decision latency*, not a
 * high-frequency recorder — and because captured frames are held in memory
 * only (proposal §8, privacy).
 *
 * Capture-denial is a first-class state: if the consent dialog is declined the
 * service is never started, and the UI records `no-monitoring`. If the user
 * revokes mid-run, [MediaProjection.Callback.onStop] tears everything down and
 * reports `capture-revoked` rather than silently stopping.
 *
 * Ownership of the projection object is confined to the capture thread
 * ([handlerThread]) to avoid racing the callback thread.
 */
class ScreenCaptureService : Service() {

    companion object {
        private const val TAG = "AdVetoCapture"
        const val CHANNEL_ID = "adveto_capture"
        const val NOTIFICATION_ID = 1

        const val ACTION_START = "org.adveto.app.START_CAPTURE"
        const val ACTION_STOP = "org.adveto.app.STOP_CAPTURE"

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        /** Emitted for every processed frame so the UI can show liveness. */
        const val ACTION_FRAME_PROCESSED = "org.adveto.app.FRAME_PROCESSED"
        const val EXTRA_FRAME_MS = "frame_ms"
        const val EXTRA_REGION_COUNT = "region_count"
        const val EXTRA_TEXT_HITS = "text_hits"
        const val EXTRA_WIDTH = "width"
        const val EXTRA_HEIGHT = "height"

        /** Emitted when capture ends, with a reason code (never silent). */
        const val ACTION_CAPTURE_STATE = "org.adveto.app.CAPTURE_STATE"
        const val EXTRA_STATE = "state"           // running | revoked | denied
        const val EXTRA_REASON = "reason"

        /** Minimum gap between processed frames. */
        const val FRAME_INTERVAL_MS = 1000L

        /**
         * Heartbeat period for the delivery watchdog.
         *
         * Independent of [FRAME_INTERVAL_MS] on purpose: the counters inside
         * `onImage()` only ever print when a frame arrives, so a virtual
         * display that pushes *nothing* — the exact case under investigation —
         * would produce an empty log that is indistinguishable from a dead
         * process. The watchdog runs on a separate tick precisely so silence
         * at the reader becomes an observable line rather than an absence.
         */
        private const val DELIVERY_WATCHDOG_MS = 5_000L

        /** Set by the service while running; cleared on teardown. */
        @Volatile var instance: ScreenCaptureService? = null
    }

    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var screenWidth = 0
    private var screenHeight = 0
    private var densityDpi = 0
    private var lastFrameAt = 0L

    @Volatile private var running = false

    // --- delivery diagnostics ------------------------------------------------
    //
    // Every exit path in onImage() used to be silent. That is what made the
    // 6-frame / 173-second anomaly unresolvable: "the virtual display pushed
    // nothing", "the frame was throttled", and "the conversion returned null"
    // all produced the same log — no line at all. The three causes are
    // separable only by knowing how often each path ran, so each is counted.
    //
    // Reading them:
    //   raw == 0              no callbacks — nothing is being pushed
    //   raw > 0, processed 0  frames arrive but die on the way; the nonzero
    //                         counter names where
    //   raw ~= processed      healthy; low `raw` on a static screen is normal
    @Volatile private var rawFrames = 0L        // onImage() entered
    @Volatile private var throttledFrames = 0L  // discarded by FRAME_INTERVAL_MS
    @Volatile private var acquireFailed = 0L    // acquireLatestImage null/exception
    @Volatile private var nullBitmap = 0L       // imageToBitmap returned null
    @Volatile private var processedFrames = 0L  // reached the pipeline
    /** Any callback at all, throttled or not — the watchdog's only witness. */
    @Volatile private var lastCallbackAtMs = 0L
    private var lastStatAtMs = 0L

    /** Latest frame retained for the UI/detector; memory-only by design. */
    @Volatile var latestFrame: Bitmap? = null
        private set

    /**
     * Stages (3)-(5), including ML Kit OCR. Uses
     * [org.adveto.app.pipeline.PendingModelClassifier] until Batch 4 ships a
     * trained artifact; the policy still runs so the abstain path is live and
     * every logged row is honest.
     *
     * OCR failures are expected, not exceptional: an empty/timeout result
     * downgrades the event to `image-only` (proposal §7 fallbacks).
     */
    private val ocr = MlKitOcr()
    private val pipeline = Pipeline(ocr = ocr)

    /**
     * Configured child age. Read per frame because the parent can change it at
     * any time and the policy is evaluated against the *current* value — the
     * age band is a policy input, not a device property.
     *
     * Read through [AppPrefs] rather than a private copy of the preference key:
     * the dashboard reports verdicts under this same band, and two independent
     * copies of the key would let it narrate decisions under a band the policy
     * never used.
     */
    private fun configuredAge(): Int = AppPrefs.ageYears(this)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_STOP -> {
                teardown("stopped-by-user")
                stopSelf()
            }
        }
        // Captures continue while the process is alive; the projection grant
        // itself does not survive process death, so restart is not sticky.
        return START_NOT_STICKY
    }

    private fun handleStart(intent: Intent) {
        // Promote to foreground FIRST, before any validation. This service is
        // started with startForegroundService(), which mandates startForeground()
        // within the system timeout regardless of how the command turns out.
        // Returning early on bad extras without promoting is exactly the
        // ForegroundServiceDidNotStartInTimeException crash, so the contract is
        // honoured up front and the failure path below only has to tear down.
        startForegroundInternal()

        if (running) return

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val resultData: android.content.Intent? =
            if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(EXTRA_RESULT_DATA, android.content.Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(EXTRA_RESULT_DATA)
            }
        if (resultData == null) {
            emitState("denied", "missing-projection-intent")
            stopSelf()
            return
        }

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mp = mpm.getMediaProjection(resultCode, resultData) ?: run {
            emitState("denied", "getMediaProjection-null")
            stopSelf()
            return
        }

        val thread = HandlerThread("adveto-capture").apply { start() }
        handlerThread = thread
        handler = Handler(thread.looper)

        // Register the callback BEFORE creating the virtual display so a
        // revoke that arrives early is never missed.
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                // Revocation (user swipe-away, app switch, policy) — explicit,
                // logged, never treated as a normal frame gap.
                emitState("revoked", "projection-stopped")
                teardown("revoked")
                stopSelf()
            }
        }, handler)

        projection = mp
        configureDisplayMetrics()
        attachImageReader()
        running = true
        instance = this
        emitState("running", "authorised")
        Log.i(TAG, "capture started ${screenWidth}x$screenHeight @$densityDpi")
        startDeliveryWatchdog()
    }

    /**
     * Repeating liveness probe on the capture thread.
     *
     * This is the line that separates "the projection is alive but the display
     * has nothing to show" from "nothing is reaching the reader at all". Its
     * `sinceCallbackMs` is the age of the *last raw callback*; `-1` means one
     * has never been made since the session began. The loop re-arms only while
     * [running], so `teardown()` stopping the flag also stops the tick without
     * needing a separate cancellation path.
     */
    private fun startDeliveryWatchdog() {
        val tick = object : Runnable {
            override fun run() {
                if (!running) return
                val sinceCallbackMs =
                    if (lastCallbackAtMs == 0L) -1L
                    else System.currentTimeMillis() - lastCallbackAtMs
                Log.i(TAG, "alive raw=$rawFrames processed=$processedFrames " +
                        "throttled=$throttledFrames nullBitmap=$nullBitmap " +
                        "sinceCallback_ms=$sinceCallbackMs")
                handler?.postDelayed(this, DELIVERY_WATCHDOG_MS)
            }
        }
        handler?.postDelayed(tick, DELIVERY_WATCHDOG_MS)
    }

    private fun startForegroundInternal() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notif_channel_capture),
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_capture_title))
            .setContentText(getString(R.string.notif_capture_text))
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun configureDisplayMetrics() {
        val wm = getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            screenWidth = bounds.width()
            screenHeight = bounds.height()
            densityDpi = resources.configuration.densityDpi
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
            screenWidth = metrics.widthPixels
            screenHeight = metrics.heightPixels
            densityDpi = metrics.densityDpi
        }
    }

    private fun attachImageReader() {
        // RGBA_8888 keeps a direct 1:1 copy into an ARGB_8888 Bitmap, which is
        // what the detector and (later) the image model consume.
        val reader = ImageReader.newInstance(
            screenWidth, screenHeight, PixelFormat.RGBA_8888, 2
        )
        reader.setOnImageAvailableListener({ r -> onImage(r) }, handler)

        val mp = projection ?: return
        val vd = mp.createVirtualDisplay(
            "AdVeto",
            screenWidth, screenHeight, densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface, null, handler
        )
        // createVirtualDisplay() returns null when the projection or surface is
        // already gone (revoked mid-start, low memory). Leaving that silent would
        // look identical to a frame stall — no frames, no error. Surface it.
        if (vd == null) {
            Log.e(TAG, "createVirtualDisplay returned null; no frames will arrive")
            reader.close()
            emitState("denied", "virtual-display-null")
            stopSelf()
            return
        }
        virtualDisplay = vd
        imageReader = reader
    }

    private fun onImage(reader: ImageReader) {
        if (!running) return
        rawFrames++
        lastCallbackAtMs = System.currentTimeMillis()
        val now = System.currentTimeMillis()
        if (now - lastFrameAt < FRAME_INTERVAL_MS) {
            // Discard immediately so the reader buffer never back-pressures.
            throttledFrames++
            discard(reader)
            emitStats()
            return
        }
        lastFrameAt = now

        val image = try {
            reader.acquireLatestImage()
        } catch (e: Exception) {
            acquireFailed++
            Log.w(TAG, "acquireLatestImage failed: ${e.message}")
            emitStats()
            return
        }
        if (image == null) {
            acquireFailed++
            emitStats()
            return
        }

        val started = System.nanoTime()
        try {
            val bitmap = imageToBitmap(image)
            if (bitmap == null) {
                nullBitmap++
                // This path was `?: return` — entirely silent. A conversion
                // failure was therefore indistinguishable from no frames ever
                // arriving, which is the ambiguity the counters exist to remove.
                Log.w(TAG, "imageToBitmap returned null (nullBitmap=$nullBitmap " +
                        "of raw=$rawFrames) — dropped without processing")
                emitStats()
                return
            }
            processedFrames++
            latestFrame?.recycle()
            latestFrame = bitmap

            // Stage (2): localise advertisement regions within this frame.
            val detectStart = System.nanoTime()
            val regions = AdRegionDetector.detect(bitmap, resources.displayMetrics.density)
            val detectMs = (System.nanoTime() - detectStart) / 1_000_000.0

            // Stages (3)-(5) then (7): OCR + classify, age policy, log.
            // With no trained artifact wired in yet, the classifier returns an
            // all-zero vector and the policy must abstain — the system never
            // claims an ad is safe just because a model is missing.
            // One identity for this moment. Stamped before processing rather
            // than inside the region loop, so OCR time cannot let row A and
            // row B of the *same* frame land in different seconds and split
            // one observation into two.
            val frameId = System.currentTimeMillis()

            val result = pipeline.process(
                frame = bitmap,
                regions = regions,
                ageYears = configuredAge(),
                packageName = ForegroundApp.current(this),
                detectMs = detectMs,
                frameId = frameId,
            )
            result.events.forEach { EventLogger.log(this, it) }

            val elapsedMs = (System.nanoTime() - started) / 1_000_000.0
            // Frames themselves are NEVER persisted (proposal §8) — only rows.
            Log.i(TAG, "frame ${bitmap.width}x${bitmap.height} regions=${regions.size} " +
                    "textHits=${result.textHits} " +
                    "detect+policy_ms=%.1f events=${result.events.size}".format(elapsedMs))
            // Forced: a run in which nothing processes must still print, since
            // that is the very case under investigation.
            emitStats(force = true)

            sendBroadcast(Intent(ACTION_FRAME_PROCESSED).apply {
                setPackage(packageName)
                putExtra(EXTRA_FRAME_MS, elapsedMs)
                putExtra(EXTRA_REGION_COUNT, regions.size)
                putExtra(EXTRA_TEXT_HITS, result.textHits)
                putExtra(EXTRA_WIDTH, bitmap.width)
                putExtra(EXTRA_HEIGHT, bitmap.height)
            })
        } finally {
            image.close()
        }
    }

    /**
     * Counter heartbeat.
     *
     * Rate-limited so a 60 Hz display cannot flood the buffer, but forced on
     * every processed frame. The rate limit is the point rather than a nicety:
     * if counters were printed only on success, the failure mode being hunted
     * would emit nothing whatsoever — the same silence that concealed the
     * problem originally. A `raw`-only run still prints every 5 s.
     */
    private fun emitStats(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastStatAtMs < 5_000L) return
        lastStatAtMs = now
        Log.i(TAG, "stats raw=$rawFrames throttled=$throttledFrames " +
                "acquireNull=$acquireFailed nullBitmap=$nullBitmap " +
                "processed=$processedFrames running=$running")
    }

    private fun discard(reader: ImageReader) {
        // acquireLatestImage must be called to release the buffer slot.
        try {
            reader.acquireLatestImage()?.close()
        } catch (_: Exception) {
        }
    }

    private fun imageToBitmap(image: Image): Bitmap? {
        val plane = image.planes.firstOrNull() ?: return null
        val buffer = plane.buffer
        val bitmap = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(buffer)
        return bitmap
    }

    private fun emitState(state: String, reason: String) {
        sendBroadcast(Intent(ACTION_CAPTURE_STATE).apply {
            setPackage(packageName)
            putExtra(EXTRA_STATE, state)
            putExtra(EXTRA_REASON, reason)
        })
    }

    private fun teardown(reason: String) {
        if (!running && projection == null) return
        running = false
        try {
            virtualDisplay?.release()
            imageReader?.close()
            projection?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "teardown: ${e.message}")
        }
        virtualDisplay = null
        imageReader = null
        projection = null
        handlerThread?.quitSafely()
        handlerThread = null
        handler = null
        Log.i(TAG, "capture torn down ($reason)")
    }

    override fun onDestroy() {
        teardown("service-destroyed")
        ocr.close()
        instance = null
        super.onDestroy()
    }
}
