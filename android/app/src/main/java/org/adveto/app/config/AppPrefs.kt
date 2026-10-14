package org.adveto.app.config

import android.content.Context
import org.adveto.app.policy.AgeBand

/**
 * The configured child age — one reader, used by both the policy and the
 * dashboard.
 *
 * This exists to remove a duplication that would have been a genuine defect
 * rather than a style nit: [org.adveto.app.capture.ScreenCaptureService]
 * evaluates the age-aware policy against this preference on every frame, while
 * the parent dashboard has to *report* which band produced the verdicts it is
 * showing. If each held its own copy of the `"adveto"` / `"age_years"` keys,
 * renaming either one would leave the dashboard quietly narrating decisions
 * under a band the policy never used — a wrong answer that still looks
 * authoritative, which is the worst shape of bug for a parental tool.
 */
object AppPrefs {

    private const val FILE = "adveto"
    private const val KEY_AGE_YEARS = "age_years"

    /**
     * Default 8 years, i.e. the 6-9 band. Chosen as the midpoint of the
     * proposal's minor range rather than an arbitrary value; it is a
     * configuration default, not a model output.
     */
    private const val DEFAULT_AGE_YEARS = 8

    fun ageYears(context: Context): Int =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getInt(KEY_AGE_YEARS, DEFAULT_AGE_YEARS)

    /** The band the policy is currently deciding against. */
    fun ageBand(context: Context): AgeBand = AgeBand.of(ageYears(context))
}
