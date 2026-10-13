package org.adveto.app.overlay

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Build
import android.os.IBinder
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.util.Log

/**
 * Stage (6) — overlay.
 *
 * Draws a cover over exactly the advertisement region when (and only when) the
 * age policy returns `cover`. It is a separate service from capture so that
 * overlay failure is observable and recordable: if the window cannot be added
 * (permission revoked, bad token) the caller learns it and logs a bypass case
 * instead of assuming the ad was hidden.
 *
 * Requires `SYSTEM_ALERT_WINDOW` (Settings.canDrawOverlays). The Week 1-2 gate
 * checks specifically whether an overlay draws over *another application's*
 * advertisement — if the platform blocks that, the recorded contingency is to
 * demonstrate it inside the prototype's own test activity (proposal §9).
 */
class OverlayService : Service() {

    companion object {
        private const val TAG = "AdVetoOverlay"
        const val ACTION_SHOW = "org.adveto.app.OVERLAY_SHOW"
        const val ACTION_HIDE = "org.adveto.app.OVERLAY_HIDE"
        const val EXTRA_LEFT = "left"
        const val EXTRA_TOP = "top"
        const val EXTRA_RIGHT = "right"
        const val EXTRA_BOTTOM = "bottom"
        const val EXTRA_LABEL = "label"
        const val EXTRA_RESULT = "result"   // drawn | no-permission | failed

        fun canDraw(context: Context): Boolean =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                android.provider.Settings.canDrawOverlays(context)
            } else true
    }

    private var currentView: android.view.View? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW -> show(intent)
            ACTION_HIDE -> hide()
        }
        return START_NOT_STICKY
    }

    private fun show(intent: Intent) {
        if (!canDraw(this)) {
            Log.w(TAG, "SYSTEM_ALERT_WINDOW not granted")
            sendResult("no-permission")
            return
        }
        val rect = Rect(
            intent.getIntExtra(EXTRA_LEFT, 0),
            intent.getIntExtra(EXTRA_TOP, 0),
            intent.getIntExtra(EXTRA_RIGHT, 0),
            intent.getIntExtra(EXTRA_BOTTOM, 0)
        )
        val label = intent.getStringExtra(EXTRA_LABEL) ?: "Covered by AdVeto"

        try {
            hide()

            val pad = (8 * resources.displayMetrics.density).toInt()
            val panel = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setBackgroundColor(Color.argb(235, 13, 27, 42))   // opaque cover
                setPadding(pad, pad, pad, pad)
                addView(TextView(context).apply {
                    text = label
                    setTextColor(Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                })
            }

            val type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            val params = WindowManager.LayoutParams(
                rect.width().coerceAtLeast(1),
                rect.height().coerceAtLeast(1),
                type,
                // Touchable (not FLAG_NOT_TOUCHABLE): the cover must swallow the
                // tap that would otherwise open the landing page.
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                x = rect.left
                y = rect.top
                gravity = Gravity.TOP or Gravity.START
            }

            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            wm.addView(panel, params)
            currentView = panel
            Log.i(TAG, "overlay drawn at $rect")
            sendResult("drawn")
        } catch (e: Exception) {
            // Bypass case: an overlay that fails to draw must be recorded, not
            // swallowed — the proposal requires bypass cases to be exercised.
            Log.e(TAG, "overlay failed: ${e.message}")
            currentView = null
            sendResult("failed")
        }
    }

    private fun hide() {
        val v = currentView ?: return
        try {
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            wm.removeView(v)
        } catch (e: Exception) {
            Log.w(TAG, "removeView: ${e.message}")
        }
        currentView = null
    }

    private fun sendResult(result: String) {
        sendBroadcast(Intent("org.adveto.app.OVERLAY_RESULT").apply {
            setPackage(packageName)
            putExtra(EXTRA_RESULT, result)
        })
        if (result != "drawn") stopSelf()
    }

    override fun onDestroy() {
        hide()
        super.onDestroy()
    }
}
