package org.crea.reader.ai

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.view.LayoutInflater
import android.widget.Toast
import androidx.core.view.isVisible
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.crea.reader.R
import org.crea.reader.databinding.SheetAiBinding

/** The bottom sheets of the reader's AI helpers: they show progress, the answer, and what to do if it fails. */
object AiSheets {

    /** Explain, simplify, define or translate a passage the reader selected. */
    fun showSelection(
        context: Context,
        inflater: LayoutInflater,
        scope: CoroutineScope,
        ai: AiService,
        selection: String,
        surrounding: String?,
        bookTitle: String?,
    ) {
        val sheet = open(context, inflater)
        sheet.binding.aiTitle.setText(R.string.ai_selection_title)
        sheet.binding.aiQuote.isVisible = true
        sheet.binding.aiQuote.text = "“${selection.take(240).trim()}”"
        sheet.binding.aiModesScroll.isVisible = true
        sheet.binding.aiModes.check(R.id.ai_mode_explain)

        fun modeOf(id: Int?) = when (id) {
            R.id.ai_mode_simplify -> ExplainMode.SIMPLIFY
            R.id.ai_mode_define -> ExplainMode.DEFINE
            R.id.ai_mode_translate -> ExplainMode.TRANSLATE
            else -> ExplainMode.EXPLAIN
        }

        fun run() {
            val mode = modeOf(sheet.binding.aiModes.checkedChipId)
            // Translate towards the other app language: English for Spanish readers and the reverse.
            val target = if (context.resources.configuration.locales[0].language == "es") "inglés" else "español"
            sheet.load(scope, ai, context) { ai.explain(mode, selection, surrounding, bookTitle, target) }
        }
        sheet.binding.aiModes.setOnCheckedStateChangeListener { _, _ -> run() }
        sheet.retry = ::run
        run()
    }

    /** A summary of the chapter being read. */
    fun showChapterSummary(
        context: Context,
        inflater: LayoutInflater,
        scope: CoroutineScope,
        ai: AiService,
        chapterText: String,
        bookTitle: String?,
        chapterTitle: String?,
    ) {
        val sheet = open(context, inflater)
        sheet.binding.aiTitle.setText(R.string.ai_chapter_summary_title)
        sheet.binding.aiModesScroll.isVisible = false
        sheet.retry = { sheet.load(scope, ai, context) { ai.chapterSummary(chapterText, bookTitle, chapterTitle) } }
        sheet.retry()
    }

    /** Comprehension questions, activities and vocabulary for a teacher, from the chapter being read. */
    fun showTeacherGuide(
        context: Context,
        inflater: LayoutInflater,
        scope: CoroutineScope,
        ai: AiService,
        chapterText: String,
        bookTitle: String?,
        chapterTitle: String?,
    ) {
        val sheet = open(context, inflater)
        sheet.binding.aiTitle.setText(R.string.ai_teacher_title)
        sheet.binding.aiModesScroll.isVisible = false
        val labels = TeacherGuideFormatter.Labels(
            questions = context.getString(R.string.ai_teacher_questions),
            activities = context.getString(R.string.ai_teacher_activities),
            vocabulary = context.getString(R.string.ai_teacher_vocabulary),
            hint = context.getString(R.string.ai_teacher_hint),
            type = {
                when (it) {
                    "literal" -> context.getString(R.string.ai_type_literal)
                    "inferencial" -> context.getString(R.string.ai_type_inferential)
                    else -> context.getString(R.string.ai_type_critical)
                }
            },
        )
        sheet.retry = {
            sheet.load(scope, ai, context) {
                when (val outcome = ai.teacherGuide(chapterText, bookTitle, chapterTitle, null)) {
                    is AiOutcome.Success -> AiOutcome.Success(TeacherGuideFormatter.format(outcome.value, labels))
                    is AiOutcome.Failure -> outcome
                }
            }
        }
        sheet.retry()
    }

    private class Sheet(val dialog: BottomSheetDialog, val binding: SheetAiBinding) {
        var retry: () -> Unit = {}
        private var job: Job? = null

        fun load(scope: CoroutineScope, ai: AiService, context: Context, request: suspend () -> AiOutcome<String>) {
            job?.cancel()
            binding.aiLoading.isVisible = true
            binding.aiResult.text = ""
            binding.aiRetry.isVisible = false
            binding.aiCopy.isEnabled = false
            binding.aiShare.isEnabled = false
            job = scope.launch {
                val outcome = request()
                binding.aiLoading.isVisible = false
                when (outcome) {
                    is AiOutcome.Success -> {
                        binding.aiResult.text = outcome.value
                        binding.aiCopy.isEnabled = true
                        binding.aiShare.isEnabled = true
                    }
                    is AiOutcome.Failure -> {
                        binding.aiResult.setText(messageFor(outcome.result))
                        binding.aiRetry.isVisible = outcome.result is AiResult.Failed
                    }
                }
            }
            dialog.setOnDismissListener { job?.cancel() }
        }
    }

    private fun messageFor(result: AiResult): Int = when (result) {
        is AiResult.Unavailable -> R.string.ai_error_unavailable
        AiResult.LimitReached -> R.string.ai_error_limit
        is AiResult.Failed -> when (result.kind) {
            AiResult.Kind.NO_CONNECTION -> R.string.ai_error_no_connection
            AiResult.Kind.SIGN_IN -> R.string.ai_error_sign_in
            AiResult.Kind.BUSY -> R.string.ai_error_busy
            AiResult.Kind.REFUSED -> R.string.ai_error_refused
            AiResult.Kind.ERROR -> R.string.ai_error_generic
        }
        is AiResult.Ok -> R.string.ai_error_generic
    }

    private fun open(context: Context, inflater: LayoutInflater): Sheet {
        val dialog = BottomSheetDialog(context)
        val binding = SheetAiBinding.inflate(inflater)
        val sheet = Sheet(dialog, binding)
        binding.aiModesScroll.isVisible = false
        binding.aiRetry.setOnClickListener { sheet.retry() }
        binding.aiCopy.setOnClickListener {
            context.getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText("CREA Reader", binding.aiResult.text))
            Toast.makeText(context, R.string.crash_copied, Toast.LENGTH_SHORT).show()
        }
        binding.aiShare.setOnClickListener {
            context.startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, binding.aiResult.text.toString()),
                    null
                )
            )
        }
        dialog.setContentView(binding.root)
        dialog.show()
        return sheet
    }
}
