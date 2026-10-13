package org.adveto.app

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

/**
 * Bootstrap activity.
 *
 * Batch 1 exists only to prove the toolchain end to end (JDK 17 + Android SDK 35 +
 * AGP 8.7 + Kotlin 2.0 -> a real APK). Batch 2 replaces this with the pilot UI:
 * capture consent, region preview, overlay toggle, and the event log.
 *
 * Extends the framework [Activity] (not AppCompatActivity) because the Batch-1
 * theme is a framework Material theme — no AppCompat dependency for the proof build.
 */
class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<TextView>(R.id.statusText).text = getString(R.string.toolchain_ok)
    }
}
