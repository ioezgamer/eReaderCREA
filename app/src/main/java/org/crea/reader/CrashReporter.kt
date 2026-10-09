package org.crea.reader

import android.app.Application
import android.content.Intent
import android.os.Build
import android.os.Process
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess

/**
 * Catches a crash and shows it on a screen of its own (in a separate process, so it works even if
 * the app dies while starting) with the technical details to copy or share. The report stays on
 * the device; nothing is sent anywhere.
 */
object CrashReporter {
    const val EXTRA_REPORT = "report"
    private const val FILE_NAME = "last-crash.txt"

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
            try {
                val report = buildReport(app, thread, error)
                runCatching { File(app.filesDir, FILE_NAME).writeText(report) }
                app.startActivity(
                    Intent(app, CrashActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        .putExtra(EXTRA_REPORT, report)
                )
                // Give the system a moment to start the crash screen before this process goes away.
                Thread.sleep(400)
            } catch (_: Throwable) {
                previous?.uncaughtException(thread, error)
            }
            Process.killProcess(Process.myPid())
            exitProcess(10)
        }
    }

    private fun buildReport(app: Application, thread: Thread, error: Throwable): String {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
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
