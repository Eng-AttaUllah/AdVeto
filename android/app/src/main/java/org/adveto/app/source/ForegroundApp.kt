package org.adveto.app.source

import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager

/**
 * Which application is on screen when a frame is captured — the "source
 * application" the proposal requires in every event row (§7) and the axis the
 * parent dashboard aggregates on.
 *
 * UsageStats rather than QUERY_ALL_PACKAGES: the latter is treated as
 * sensitive by the platform and is unnecessary here. Usage access is a
 * user-granted special access ("Usage access" in settings); if it has not been
 * granted the pipeline still runs and records `usage-access-not-granted`
 * instead of failing or inventing a package name.
 */
object ForegroundApp {

    fun current(context: Context): String {
        if (!hasUsageAccess(context)) return "usage-access-not-granted"
        return try {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            val events = usm.queryEvents(now - 15_000, now)
            val event = UsageEvents.Event()
            var pkg = "unknown"
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                // ACTIVITY_RESUMED is the API-29 successor of the deprecated
                // MOVE_TO_FOREGROUND; both are read so logs stay complete on
                // devices that still emit the older code.
                if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                    pkg = event.packageName ?: pkg
                }
            }
            pkg
        } catch (e: Exception) {
            "usage-stats-error:${e.javaClass.simpleName}"
        }
    }

    private fun hasUsageAccess(context: Context): Boolean {
        if (context.checkSelfPermission(android.Manifest.permission.PACKAGE_USAGE_STATS)
            == PackageManager.PERMISSION_GRANTED
        ) return true

        return try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            // unsafeCheckOpNoThrow is the API-29 replacement for the deprecated
            // checkOpNoThrow; minSdk is 29 so it is always available.
            val mode = appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
            mode == AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) {
            false
        }
    }
}
