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
import org.adveto.app.detect.AdRegionDetector
import org.adveto.app.log.EventLogger
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
        const val EXTRA_WIDTH = "width"
        const val EXTRA_HEIGHT = "height"

        /** Emitted when capture ends, with a reason code (never silent). */
        const val ACTION_CAPTURE_STATE = "org.adveto.app.CAPTURE_STATE"
        const val EXTRA_STATE = "state"           // running | revoked | denied
        const val EXTRA_REASON = "reason"

        /** Minimum gap between processed frames. */
        const val FRAME_INTERVAL_MS = 1000L

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

    /** Latest frame retained for the UI/detector; memory-only by design. */
    @Volatile var latestFrame: Bitmap? = null
        private set

    /**
     * Stages (3)-(5). Uses [org.adveto.app.pipeline.PendingModelClassifier]
     * until Batch 4 ships a trained artifact; the policy still runs so the
     * abstain path is live and every logged row is honest.
     */
    private val pipeline = Pipeline()

    /**
     * Configured child age. Read per frame because the parent can change it at
     * any time and the policy is evaluated against the *current* value — the
     * age band is a policy input, not a device property.
     */
    private fun configuredAge(): Int =
        getSharedPreferences("adveto", MODE_PRIVATE).getInt("age_years", 8)

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

        startForegroundInternal()

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
        virtualDisplay = mp.createVirtualDisplay(
            "AdVeto",
            screenWidth, screenHeight, densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface, null, handler
        )
        imageReader = reader
    }

    private fun onImage(reader: ImageReader) {
        if (!running) return
        val now = System.currentTimeMillis()
        if (now - lastFrameAt < FRAME_INTERVAL_MS) {
            // Discard immediately so the reader buffer never back-pressures.
            discard(reader)
            return
        }
        lastFrameAt = now

        val image = try {
            reader.acquireLatestImage() ?: return
        } catch (e: Exception) {
            Log.w(TAG, "acquireLatestImage failed: ${e.message}")
            return
        }

        val started = System.nanoTime()
        try {
            val bitmap = imageToBitmap(image) ?: return
            latestFrame?.recycle()
            latestFrame = bitmap

            // Stage (2): localise advertisement regions within this frame.
            val regions = AdRegionDetector.detect(bitmap, resources.displayMetrics.density)

            // Stages (3)-(5) then (7): classify, apply the age policy, log.
            // With no trained artifact wired in yet, the classifier returns an
            // all-zero vector and the policy must abstain — the system never
            // claims an ad is safe just because a model is missing.
            val result = pipeline.process(
                frame = bitmap,
                regions = regions,
                ageYears = configuredAge(),
                density = resources.displayMetrics.density,
                packageName = ForegroundApp.current(this),
            )
            result.events.forEach { EventLogger.log(this, it) }

            val elapsedMs = (System.nanoTime() - started) / 1_000_000.0
            // Frames themselves are NEVER persisted (proposal §8) — only rows.
            Log.i(TAG, "frame ${bitmap.width}x${bitmap.height} regions=${regions.size} " +
                    "detect+policy_ms=%.1f events=${result.events.size}".format(elapsedMs))

            sendBroadcast(Intent(ACTION_FRAME_PROCESSED).apply {
                setPackage(packageName)
                putExtra(EXTRA_FRAME_MS, elapsedMs)
                putExtra(EXTRA_REGION_COUNT, regions.size)
                putExtra(EXTRA_WIDTH, bitmap.width)
                putExtra(EXTRA_HEIGHT, bitmap.height)
            })
        } finally {
            image.close()
        }
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
        instance = null
        super.onDestroy()
    }
}
