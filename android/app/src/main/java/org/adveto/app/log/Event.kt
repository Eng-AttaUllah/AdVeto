package org.adveto.app.log

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import java.util.concurrent.Executors

/**
 * Stage (7) — local event log.
 *
 * One row per decision, holding exactly what the proposal §7 specifies:
 * decision, source application, category, confidence, timestamp — plus the
 * provenance needed to reproduce a report later (modality, region source,
 * timings, reason).
 *
 * Captured frames and region crops are deliberately NOT stored: only the
 * derived record persists, and crops are dropped when the evaluation window
 * closes (proposal §8, privacy and retention).
 */
@Entity(tableName = "events")
data class Event(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    /** Application that produced the advertisement. */
    val packageName: String,
    /** RiskCategory.name — kept as text so the schema survives enum changes. */
    val category: String,
    val confidence: Float,
    /** allow | cover | abstain | not-processed | detection-only. */
    val outcome: String,
    /** Why this outcome (rule applied, threshold, failure code). */
    val reason: String,
    /** image | text | fusion | image-only | pending-model. */
    val modality: String,
    /** How the region was proposed: BANNER_BOTTOM, MREC, ... */
    val regionSource: String,
    /** Stage-2 localisation time, ms. */
    val detectMs: Double,
    /** Capture-to-logged-decision time, ms (the end-to-end budget metric). */
    val decisionMs: Double,
    /** Target age band id the policy evaluated against. */
    val ageBand: String,
)

/** Row shape for the "which apps keep showing this" dashboard query. */
data class CategoryCount(
    val packageName: String,
    val category: String,
    val n: Int,
)

data class OutcomeCount(val outcome: String, val n: Int)

/** Distinct modality counts — the evidence behind the "no model yet" caveat. */
data class ModalityCount(val modality: String, val n: Int)

/**
 * Per-application rollup over a time window.
 *
 * `avgConfidence`/`avgDecisionMs` are returned as Double because SQLite's
 * AVG() always yields REAL regardless of the column's declared type; asking
 * Room for a Float there is a silent narrowing, so the conversion is done
 * explicitly at the call site instead.
 */
data class AppSummary(
    val packageName: String,
    val n: Int,
    /** Most recent event for this application inside the window. */
    val lastSeen: Long,
    val avgConfidence: Double,
    val avgDecisionMs: Double,
)

data class PackageOutcomeCount(
    val packageName: String,
    val outcome: String,
    val n: Int,
)

@Dao
interface EventDao {
    @Insert
    fun insert(event: Event): Long

    @Query("SELECT * FROM events ORDER BY timestamp DESC LIMIT :limit")
    fun recent(limit: Int): List<Event>

    @Query(
        "SELECT packageName, category, COUNT(*) AS n FROM events " +
            "GROUP BY packageName, category ORDER BY n DESC"
    )
    fun byPackageAndCategory(): List<CategoryCount>

    @Query("SELECT outcome, COUNT(*) AS n FROM events GROUP BY outcome ORDER BY n DESC")
    fun outcomes(): List<OutcomeCount>

    @Query("SELECT COUNT(*) FROM events")
    fun count(): Int

    // --- windowed variants -------------------------------------------------
    // "Which applications *repeatedly* produced which categories" is a claim
    // about a period, not about all time. All-time totals cannot separate a
    // recurring offender from a one-off that was seen weeks ago, so every
    // dashboard aggregate is also offered against a since-boundary. A caller
    // wanting all-time passes 0.

    @Query("SELECT COUNT(*) FROM events WHERE timestamp >= :since")
    fun countSince(since: Long): Int

    @Query(
        "SELECT outcome, COUNT(*) AS n FROM events WHERE timestamp >= :since " +
            "GROUP BY outcome ORDER BY n DESC"
    )
    fun outcomesSince(since: Long): List<OutcomeCount>

    @Query(
        "SELECT modality, COUNT(*) AS n FROM events WHERE timestamp >= :since " +
            "GROUP BY modality ORDER BY n DESC"
    )
    fun modalitiesSince(since: Long): List<ModalityCount>

    @Query(
        "SELECT packageName, category, COUNT(*) AS n FROM events " +
            "WHERE timestamp >= :since GROUP BY packageName, category ORDER BY n DESC"
    )
    fun byPackageAndCategorySince(since: Long): List<CategoryCount>

    @Query(
        "SELECT packageName, COUNT(*) AS n, MAX(timestamp) AS lastSeen, " +
            "AVG(confidence) AS avgConfidence, AVG(decisionMs) AS avgDecisionMs " +
            "FROM events WHERE timestamp >= :since " +
            "GROUP BY packageName ORDER BY n DESC, lastSeen DESC"
    )
    fun appSummariesSince(since: Long): List<AppSummary>

    @Query(
        "SELECT packageName, outcome, COUNT(*) AS n FROM events " +
            "WHERE timestamp >= :since GROUP BY packageName, outcome"
    )
    fun packageOutcomesSince(since: Long): List<PackageOutcomeCount>

    @Query("SELECT MAX(timestamp) FROM events")
    fun lastTimestamp(): Long?

    /** For the privacy/retention procedure: purge at the close of a window. */
    @Query("DELETE FROM events WHERE timestamp < :before")
    fun deleteOlderThan(before: Long): Int
}

@Database(entities = [Event::class], version = 1, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun eventDao(): EventDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "adveto.db"
                ).build().also { instance = it }
            }
    }
}

/**
 * Single-writer logger. Room forbids database work on the main thread, and the
 * capture thread must never block on disk, so writes go to one dedicated
 * executor — ordering is preserved and drops are impossible without being
 * visible as a growing queue (bounded by the 1 Hz frame rate).
 */
object EventLogger {
    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "adveto-event-log").apply { isDaemon = true }
    }

    fun log(context: Context, event: Event) {
        val dao = AppDatabase.get(context).eventDao()
        io.execute {
            try {
                dao.insert(event)
            } catch (e: Exception) {
                android.util.Log.e("AdVetoLog", "insert failed: ${e.message}")
            }
        }
    }

    fun run(context: Context, block: (EventDao) -> Unit) {
        val dao = AppDatabase.get(context).eventDao()
        io.execute {
            try {
                block(dao)
            } catch (e: Exception) {
                android.util.Log.e("AdVetoLog", "query failed: ${e.message}")
            }
        }
    }
}
