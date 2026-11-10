package org.crea.reader

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess

/**
 * Keeps the technical details of a crash and shows them the next time the app opens, on a screen of
 * its own (in a separate process, with plain views) to copy or share them. The report stays on the
 * device; nothing is sent anywhere by this class.
 *
 * The crash itself is handed on to the system's handler, so Android records it as usual: Play
 * Console's Android vitals see it, and a crash in the background (a sync, a download) is never
 * swallowed silently. Android 10+ does not let a background process open a screen, which is why the
 * report waits for the next launch instead of being shown right away.
 */
object CrashReporter {
    const val EXTRA_REPORT = "report"
    private const val FILE_NAME = "last-crash.txt"
    private const val PENDING_FILE_NAME = "pending-crash.txt"

    /** True in the process that only hosts [CrashActivity]; the app must not initialise itself there. */
    fun isCrashProcess(): Boolean = processName().endsWith(":crash")

    private fun processName(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Application.getProcessName().orEmpty()
        } else {
            runCatching { File("/proc/self/cmdline").readText().trim('\u0000') }.getOrDefault("")
        }

    fun install(app: Application) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { savePending(app.filesDir, buildReport(thread, error)) }
            if (previous != null) {
                // The system handler logs the crash (Android vitals) and ends the process.
                previous.uncaughtException(thread, error)
            } else {
                Process.killProcess(Process.myPid())
                exitProcess(10)
            }
        }
    }

    /**
     * The report of a crash not shown yet, or null. Taking it marks it as shown (it stays as
     * [FILE_NAME] for reference), so it appears only once.
     */
    fun takePendingReport(context: Context): String? = takePending(context.filesDir)

    /** Opens the crash screen if the last run ended in a crash. Returns true when it did. */
    fun showPendingReport(activity: Activity): Boolean {
        val report = takePendingReport(activity) ?: return false
        return runCatching {
            activity.startActivity(Intent(activity, CrashActivity::class.java).putExtra(EXTRA_REPORT, report))
        }.isSuccess
    }

    internal fun savePending(dir: File, report: String) {
        File(dir, PENDING_FILE_NAME).writeText(report)
    }

    internal fun takePending(dir: File): String? {
        val pending = File(dir, PENDING_FILE_NAME)
        if (!pending.isFile) return null
        val report = runCatching { pending.readText() }.getOrNull()
        val shown = File(dir, FILE_NAME)
        shown.delete()
        if (!pending.renameTo(shown)) pending.delete()
        return report?.takeIf { it.isNotBlank() }
    }

    internal fun buildReport(thread: Thread, error: Throwable, now: Date = Date()): String {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(now)
        return buildString {
            appendLine("CREA Reader ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("$time · thread ${thread.name}")
            appendLine("${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine()
            append(error.stackTraceToString().take(MAX_TRACE))
        }
    }

    private const val MAX_TRACE = 12_000
}
