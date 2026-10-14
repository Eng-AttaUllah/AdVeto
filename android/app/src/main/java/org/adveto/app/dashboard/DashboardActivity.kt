package org.adveto.app.dashboard

import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import androidx.core.view.updateLayoutParams
import org.adveto.app.R
import org.adveto.app.config.AppPrefs
import org.adveto.app.log.AppSummary
import org.adveto.app.log.CategoryCount
import org.adveto.app.log.Event
import org.adveto.app.log.EventLogger
import org.adveto.app.log.ModalityCount
import org.adveto.app.log.OutcomeCount
import org.adveto.app.log.PackageOutcomeCount
import org.adveto.app.policy.Decision

/**
 * Stage (7) — parent dashboard.
 *
 * A *read-only aggregation* over the event log: which applications repeatedly
 * produced which categories, and what the system did about them. That is the
 * part a parent can actually act on — a flat blocked/unblocked list cannot
 * tell them that one approved game keeps serving gambling ads.
 *
 * Two deliberate restraints:
 *
 *  1. **Time windows.** "Repeatedly" is a claim about a period, so every
 *     aggregate is computed against a since-boundary rather than all-time.
 *     An all-time total cannot separate a recurring offender from a one-off
 *     seen last month.
 *
 *  2. **No implied detection.** Until Batch 4 installs a trained artifact the
 *     classifier is a stub: every row is logged `abstain` with modality
 *     `pending-model`. Presenting that as a decision mix would read as "the
 *     policy examined 42 advertisements and rejected all 42", which is false —
 *     the policy was never given a classification to act on. When the window's
 *     events are all stub-produced, the mix is replaced by an explicit caveat
 *     stating that no model is integrated yet.
 *
 * Anything richer — per-app blocking toggles, trend charts, export — is
 * unmeasured scope creep for Weeks 1-2 and is not built here.
 */
class DashboardActivity : Activity() {

    /** Cap on the recent list; the log is append-only and grows at ~1 Hz. */
    private val recentLimit = 12

    private lateinit var summaryEvents: TextView
    private lateinit var summaryLast: TextView
    private lateinit var summaryBand: TextView
    private lateinit var decisionMix: LinearLayout
    private lateinit var sourcesList: LinearLayout
    private lateinit var recentList: LinearLayout

    private val windowButtons = mutableMapOf<Int, Button>()

    /** Since-boundary for the selected window. 0 means all-time. */
    private var sinceMs: Long = DAY_MS

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dashboard)

        summaryEvents = findViewById(R.id.summaryEvents)
        summaryLast = findViewById(R.id.summaryLast)
        summaryBand = findViewById(R.id.summaryBand)
        decisionMix = findViewById(R.id.decisionMix)
        sourcesList = findViewById(R.id.sourcesList)
        recentList = findViewById(R.id.recentList)

        windowButtons[R.id.window24h] = findViewById(R.id.window24h)
        windowButtons[R.id.window7d] = findViewById(R.id.window7d)
        windowButtons[R.id.windowAll] = findViewById(R.id.windowAll)

        windowButtons.forEach { (id, button) ->
            button.setOnClickListener {
                sinceMs = when (id) {
                    R.id.window24h -> DAY_MS
                    R.id.window7d -> WEEK_MS
                    else -> 0L
                }
                render()
            }
        }

        findViewById<Button>(R.id.refreshButton).setOnClickListener { render() }
        render()
    }

    override fun onResume() {
        super.onResume()
        // The age band can change while this screen is open, and the summary
        // narrates decisions under it — so re-read rather than cache.
        render()
    }

    private fun render() {
        highlightWindow()
        val band = AppPrefs.ageBand(this)
        summaryBand.text = getString(
            R.string.stat_band_format, band.id, AppPrefs.ageYears(this)
        )

        summaryEvents.text = getString(R.string.dashboard_loading)
        summaryLast.text = ""
        sourcesList.removeAllViews()
        recentList.removeAllViews()
        decisionMix.removeAllViews()

        EventLogger.run(this) { dao ->
            val now = System.currentTimeMillis()
            val cutoff = if (sinceMs == 0L) 0L else now - sinceMs

            val total = dao.countSince(cutoff)
            val outcomes = dao.outcomesSince(cutoff)
            val modalities = dao.modalitiesSince(cutoff)
            val apps = dao.appSummariesSince(cutoff)
            val categories = dao.byPackageAndCategorySince(cutoff)
            val packageOutcomes = dao.packageOutcomesSince(cutoff)
            val recent = dao.recent(recentLimit)
                .filter { it.timestamp >= cutoff }
            val lastTs = dao.lastTimestamp()

            runOnUiThread {
                renderSummary(total, outcomes, modalities, lastTs, now)
                renderSources(apps, categories, packageOutcomes, total)
                renderRecent(recent, now)
            }
        }
    }

    private fun renderSummary(
        total: Int,
        outcomes: List<OutcomeCount>,
        modalities: List<ModalityCount>,
        lastTs: Long?,
        now: Long,
    ) {
        summaryEvents.text =
            if (total == 0) getString(R.string.no_events_body)
            else "$total event${if (total == 1) "" else "s"} in window"

        summaryLast.text = if (lastTs == null || lastTs == 0L) {
            "No events recorded yet."
        } else {
            "Last event — ${relative(lastTs, now)}"
        }

        decisionMix.removeAllViews()

        if (total == 0) return

        // Every logged row produced by the stub classifier carries modality
        // `pending-model`. If that is the whole window, a decision mix would
        // misrepresent abstention as a judgement, so it is suppressed.
        val stubEvents = modalities.filter { it.modality == "pending-model" }.sumOf { it.n }
        val realEvents = total - stubEvents

        if (realEvents == 0) {
            decisionMix.addView(caveatView(
                "No trained classifier is installed yet (Batch 4).\n\n" +
                    "All $total decision${if (total == 1) "" else "s"} in this window were logged " +
                    "`abstain` with modality `pending-model` — the policy was never given a " +
                    "classification to act on. These are placeholders, not judgements, and " +
                    "must not be read as \"the policy rejected $total advertisements\"."
            ))
            return
        }

        outcomes.forEach { outcome ->
            decisionMix.addView(mixRow(outcome, total))
        }

        if (stubEvents > 0) {
            decisionMix.addView(caveatView(
                "$stubEvents of $total rows are still `pending-model` and are excluded " +
                    "from the mix above."
            ))
        }
    }

    private fun renderSources(
        apps: List<AppSummary>,
        categories: List<CategoryCount>,
        packageOutcomes: List<PackageOutcomeCount>,
        total: Int,
    ) {
        sourcesList.removeAllViews()

        if (apps.isEmpty()) {
            sourcesList.addView(caveatView(getString(R.string.no_events_title)))
            return
        }

        val categoriesByPackage = categories.groupBy { it.packageName }
        val outcomesByPackage = packageOutcomes.groupBy { it.packageName }

        apps.forEach { app ->
            val row = LayoutInflater.from(this)
                .inflate(R.layout.row_app, sourcesList, false)

            row.findViewById<TextView>(R.id.appLabel).text = appLabel(app.packageName)
            row.findViewById<TextView>(R.id.appPackage).text = app.packageName
            row.findViewById<TextView>(R.id.appCount).text = app.n.toString()

            // Share of the window, so a count of 3 is readable as "3 of 42"
            // rather than as an absolute that means nothing on its own.
            //
            // Sized in doOnLayout rather than post(): post() can run before the
            // card has been measured, which would read the track as 0px wide
            // and silently render every bar empty.
            val share = if (total > 0) app.n.toFloat() / total else 0f
            val bar = row.findViewById<View>(R.id.appShareBar)
            bar.doOnLayout {
                val trackWidth = (bar.parent as? View)?.width ?: 0
                bar.updateLayoutParams<ViewGroup.LayoutParams> {
                    width = (trackWidth * share.coerceIn(0f, 1f)).toInt()
                }
            }

            val meta = buildString {
                val catRows = categoriesByPackage[app.packageName].orEmpty()
                if (catRows.isEmpty()) {
                    append("no categories recorded")
                } else {
                    append(catRows.joinToString(" · ") {
                        "${it.category.lowercase().replace('_', '/')} ${it.n}"
                    })
                }
                append("\nlast seen ${relative(app.lastSeen, System.currentTimeMillis())}")

                val outcomeRows = outcomesByPackage[app.packageName].orEmpty()
                if (outcomeRows.isNotEmpty()) {
                    append("\n")
                    append(outcomeRows.joinToString(" · ") { "${it.outcome} ${it.n}" })
                }
                val avgMs = app.avgDecisionMs
                if (avgMs > 0) {
                    append(" · avg %.0f ms".format(avgMs))
                }
            }
            row.findViewById<TextView>(R.id.appMeta).text = meta

            sourcesList.addView(row)
        }
    }

    private fun renderRecent(recent: List<Event>, now: Long) {
        recentList.removeAllViews()

        if (recent.isEmpty()) {
            recentList.addView(caveatView(getString(R.string.recent_none)))
            return
        }

        recent.forEach { event ->
            val row = LayoutInflater.from(this)
                .inflate(R.layout.row_event, recentList, false)

            row.findViewById<TextView>(R.id.eventTitle).text =
                "${appLabel(event.packageName)} → ${event.category.lowercase().replace('_', '/')}"

            val meta = buildString {
                append(relative(event.timestamp, now))
                append(" · ").append(event.modality)
                append(" · ").append(event.ageBand).append(" band")
                if (event.confidence > 0f) {
                    append(" · conf %.2f".format(event.confidence))
                }
            }
            row.findViewById<TextView>(R.id.eventMeta).text = meta

            val outcomeView = row.findViewById<TextView>(R.id.eventOutcome)
            outcomeView.text = event.outcome
            // Recolour the existing pill rather than setBackgroundColor(), which
            // would replace the rounded shape drawable with a flat rectangle.
            (outcomeView.background.mutate() as? GradientDrawable)?.setColor(
                ContextCompat.getColor(this, outcomeColour(event.outcome))
            )

            recentList.addView(row)
        }
    }

    // --- small view builders ------------------------------------------------

    private fun mixRow(outcome: OutcomeCount, total: Int): View {
        val view = layoutInflater.inflate(R.layout.row_app, decisionMix, false)
        // Reuse the app row's shape (label + count) for the mix lines: same
        // typography and alignment, without a second near-identical layout.
        view.findViewById<TextView>(R.id.appLabel).text = outcome.outcome
        view.findViewById<TextView>(R.id.appPackage).text =
            "%.0f%% of window".format(100f * outcome.n / total)
        view.findViewById<TextView>(R.id.appCount).text = outcome.n.toString()
        view.findViewById<TextView>(R.id.appMeta).text = ""
        // Hide the whole bar container, not just the fill: the track is 6dp
        // tall with an 8dp top margin, and leaving it in place drops a strip of
        // dead space between every decision line.
        view.findViewById<View>(R.id.appShareTrack).visibility = View.GONE
        return view
    }

    private fun caveatView(text: String): View {
        return TextView(this).apply {
            this.text = text
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(ContextCompat.getColor(context, R.color.textSecondary))
            setLineSpacing(0f, 1.15f)
            setPadding(0, dp(8), 0, dp(8))
        }
    }

    // --- helpers -------------------------------------------------------------

    private fun highlightWindow() {
        val active = when (sinceMs) {
            DAY_MS -> R.id.window24h
            WEEK_MS -> R.id.window7d
            else -> R.id.windowAll
        }
        windowButtons.forEach { (id, button) ->
            button.alpha = if (id == active) 1.0f else 0.55f
            button.paint.isFakeBoldText = id == active
        }
    }

    /**
     * Friendly name for a package, falling back to the raw identifier.
     *
     * The fallback matters: several logged packages are not installed apps at
     * all (`usage-access-not-granted`, `usage-stats-error:...`), and inventing
     * a display name for those would hide a real configuration fault behind a
     * plausible-looking label.
     */
    private fun appLabel(packageName: String): String {
        if (packageName.startsWith("usage-") || packageName == "unknown") {
            return packageName
        }
        return try {
            val info = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(info).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            packageName.substringAfterLast('.')
        }
    }

    private fun outcomeColour(outcome: String): Int = when (outcome) {
        Decision.ALLOW.name.lowercase() -> R.color.stateRunning
        Decision.COVER.name.lowercase() -> R.color.accent
        Decision.ABSTAIN.name.lowercase() -> R.color.stateIdle
        else -> R.color.stateError
    }

    private fun relative(timestamp: Long, now: Long): String =
        DateUtils.getRelativeTimeSpanString(
            timestamp, now, DateUtils.MINUTE_IN_MILLIS
        ).toString()

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val DAY_MS = 24L * 60 * 60 * 1000
        private const val WEEK_MS = 7L * 24 * 60 * 60 * 1000
    }
}
