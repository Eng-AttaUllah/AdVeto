package org.adveto.app.dashboard

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import org.adveto.app.R
import org.adveto.app.log.EventLogger

/**
 * Stage (7) — parent dashboard.
 *
 * Deliberately a *read-only aggregation* over the event log: which applications
 * repeatedly produced which categories, and what the system did about them.
 * That is the part a parent can actually act on — a flat blocked/unblocked
 * list cannot tell them that one approved game keeps serving gambling ads.
 *
 * Kept to a single screen and plain queries: the proposal asks for a "basic
 * parent dashboard", and anything richer would be unmeasured scope creep in
 * Weeks 1-2.
 */
class DashboardActivity : Activity() {

    private lateinit var body: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dashboard)
        body = findViewById(R.id.dashboardBody)
        findViewById<Button>(R.id.refreshButton).setOnClickListener { render() }
        render()
    }

    private fun render() {
        body.text = getString(R.string.dashboard_loading)
        EventLogger.run(this) { dao ->
            val total = dao.count()
            val outcomes = dao.outcomes()
            val matrix = dao.byPackageAndCategory()

            val text = buildString {
                append("Events logged: $total\n\n")

                append("Decisions\n")
                if (outcomes.isEmpty()) append("  (none yet)\n")
                outcomes.forEach { append("  %-14s %d\n".format(it.outcome, it.n)) }

                append("\nSource application x category\n")
                if (matrix.isEmpty()) {
                    append("  (none yet — start capture and open an ad-heavy app)\n")
                } else {
                    matrix.forEach {
                        append("  %-28s %-18s %d\n".format(
                            it.packageName.removePrefix("com.").take(28),
                            it.category.lowercase().replace('_', '/'),
                            it.n
                        ))
                    }
                }
                append("\nFrames are never stored — only these records.\n")
                append("Retention: DashboardActivity only reads; purge via EventDao.deleteOlderThan.")
            }

            runOnUiThread { body.text = text }
        }
    }
}
