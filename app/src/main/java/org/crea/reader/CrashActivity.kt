package org.crea.reader

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Shown when the app crashes: the technical report with buttons to copy it, share it or reopen the
 * app. It runs in its own process and uses only plain views, so it can appear even if the app's own
 * start-up is what failed.
 */
class CrashActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val report = intent.getStringExtra(CrashReporter.EXTRA_REPORT).orEmpty()
        val padding = (16 * resources.displayMetrics.density).toInt()

        val title = TextView(this).apply {
            text = getString(R.string.crash_title)
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
        }
        val detail = TextView(this).apply {
            text = getString(R.string.crash_detail)
            setPadding(0, padding / 2, 0, padding / 2)
        }
        val trace = TextView(this).apply {
            text = report
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        val scroll = ScrollView(this).apply {
            addView(trace)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }

        fun button(textRes: Int, onClick: () -> Unit) = Button(this).apply {
            setText(textRes)
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(button(R.string.crash_copy) {
                val clipboard = getSystemService(ClipboardManager::class.java)
                clipboard.setPrimaryClip(ClipData.newPlainText("CREA Reader", report))
                Toast.makeText(this@CrashActivity, R.string.crash_copied, Toast.LENGTH_SHORT).show()
            })
            addView(button(R.string.crash_share) {
                startActivity(
                    Intent.createChooser(
                        Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, report),
                        null
                    )
                )
            })
            addView(button(R.string.crash_reopen) {
                startActivity(
                    packageManager.getLaunchIntentForPackage(packageName)
                        ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                )
                finishAndRemoveTask()
            })
        }

        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(padding, padding * 2, padding, padding)
                addView(title)
                addView(detail)
                addView(scroll)
                addView(buttons)
            }
        )
    }
}
