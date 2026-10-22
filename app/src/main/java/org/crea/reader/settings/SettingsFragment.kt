package org.crea.reader.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.media.MediaPlayer
import android.os.Bundle
import android.provider.Settings
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.crea.reader.AppLanguage
import org.crea.reader.AppThemeMode
import org.crea.reader.Application
import org.crea.reader.BuildConfig
import org.crea.reader.R
import org.crea.reader.ThemeMode
import org.crea.reader.reader.tts.NaturalVoice
import org.crea.reader.ai.AiAvailability
import org.crea.reader.ai.AiServerUrl
import org.crea.reader.databinding.FragmentSettingsBinding
import org.crea.reader.databinding.ItemSettingsRowBinding
import org.crea.reader.reader.BarSize

class SettingsFragment : Fragment() {
    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = requireNotNull(_binding)

    private val app get() = requireContext().applicationContext as Application

    /** True while the screen itself changes a switch, so that does not count as a tap. */
    private var updating = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        bindAppearance()
        bindReading()
        bindAi()
        bindLibrary()
        binding.settingsVersion.text = getString(R.string.settings_version, BuildConfig.VERSION_NAME)
    }

    override fun onStart() {
        super.onStart()
        refreshStorage()
    }

    // region Appearance

    private fun bindAppearance() {
        // Theme: the whole app changes at once, so the screen is recreated and reads this again.
        val themeButtons = mapOf(
            ThemeMode.SYSTEM to R.id.settings_theme_system,
            ThemeMode.LIGHT to R.id.settings_theme_light,
            ThemeMode.DARK to R.id.settings_theme_dark,
        )
        binding.settingsThemeGroup.check(themeButtons.getValue(AppThemeMode.saved(requireContext())))
        binding.settingsThemeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            val mode = themeButtons.entries.firstOrNull { it.value == checkedId }?.key
            if (isChecked && mode != null && mode != AppThemeMode.saved(requireContext())) {
                AppThemeMode.select(requireContext(), mode)
            }
        }

        // Language
        val current = AppLanguage.saved(requireContext())
        binding.settingsLanguageGroup.check(
            if (current == AppLanguage.ENGLISH) R.id.settings_language_en else R.id.settings_language_es
        )
        // The screen is recreated by the language switch, so only act on a real change.
        binding.settingsLanguageGroup.setOnCheckedChangeListener { _, checkedId ->
            val tag = if (checkedId == R.id.settings_language_en) AppLanguage.ENGLISH else AppLanguage.SPANISH
            if (tag != AppLanguage.saved(requireContext())) AppLanguage.select(requireContext(), tag)
        }

        // Size of the reader's bars
        val look = app.readerLook
        val barButtons = mapOf(
            BarSize.SMALL to R.id.settings_bars_small,
            BarSize.MEDIUM to R.id.settings_bars_medium,
            BarSize.LARGE to R.id.settings_bars_large,
        )
        binding.settingsBarsGroup.check(barButtons.getValue(look.barSize.value))
        binding.settingsBarsGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            val size = barButtons.entries.firstOrNull { it.value == checkedId }?.key
            if (isChecked && size != null) look.setBarSize(size)
        }
    }

    // endregion

    // region Reading

    private fun bindReading() {
        row(binding.settingsVoiceRow, R.drawable.ic_baseline_headphones_24, R.string.settings_voice_title, getString(R.string.settings_voice_detail))
            .root.setOnClickListener { openVoiceSettings() }

        val voice = app.naturalVoice
        binding.settingsNaturalVoiceSwitch.setOnCheckedChangeListener { _, checked ->
            if (updating) return@setOnCheckedChangeListener
            voice.setEnabled(checked)
            if (checked) viewLifecycleOwner.lifecycleScope.launch { voice.check() }
        }
        binding.settingsNaturalVoiceChoose.setOnClickListener { chooseNaturalVoice(voice) }
        binding.settingsNaturalVoiceSample.setOnClickListener { playNaturalVoiceSample(voice) }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { if (voice.enabled.value) voice.check() }
                launch { voice.enabled.collect { renderNaturalVoice(voice) } }
                launch { voice.serverState.collect { renderNaturalVoice(voice) } }
                launch { voice.voices.collect { renderNaturalVoice(voice) } }
                launch { voice.selectedVoice.collect { renderNaturalVoice(voice) } }
            }
        }
    }

    private fun chooseNaturalVoice(voice: NaturalVoice) {
        val options = voice.voices.value
        if (options.isEmpty()) return
        val current = options.indexOf(voice.currentVoice).coerceAtLeast(0)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.settings_natural_voice_choose_title)
            .setSingleChoiceItems(options.map { it.name }.toTypedArray(), current) { dialog, which ->
                voice.selectVoice(options[which].id)
                dialog.dismiss()
                playNaturalVoiceSample(voice)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private var samplePlayer: MediaPlayer? = null

    /** Plays a short sentence with the chosen voice (costs a few characters the first time, then it is cached). */
    private fun playNaturalVoiceSample(voice: NaturalVoice) {
        val view = _binding ?: return
        view.settingsNaturalVoiceSample.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            val file = voice.sample(getString(R.string.settings_natural_voice_sample_text))
            _binding?.settingsNaturalVoiceSample?.isEnabled = true
            if (file == null) {
                Toast.makeText(requireContext(), R.string.settings_natural_voice_sample_failed, Toast.LENGTH_LONG).show()
                return@launch
            }
            stopNaturalVoiceSample()
            samplePlayer = runCatching {
                MediaPlayer().apply {
                    setDataSource(file.path)
                    setOnCompletionListener { stopNaturalVoiceSample() }
                    prepare()
                    start()
                }
            }.getOrNull()
        }
    }

    private fun stopNaturalVoiceSample() {
        samplePlayer?.let { player ->
            runCatching { player.stop() }
            player.release()
        }
        samplePlayer = null
    }

    private fun renderNaturalVoice(voice: NaturalVoice) {
        val view = _binding ?: return
        val on = voice.enabled.value
        updating = true
        view.settingsNaturalVoiceSwitch.isChecked = on
        updating = false
        view.settingsNaturalVoiceStatus.setText(
            when {
                !on -> R.string.settings_natural_voice_off
                !voice.configured -> R.string.settings_natural_voice_no_server
                !voice.isDriveConnected() -> R.string.settings_natural_voice_needs_drive
                else -> when (voice.serverState.value) {
                    NaturalVoice.ServerState.AVAILABLE -> R.string.settings_natural_voice_on
                    NaturalVoice.ServerState.UNAVAILABLE -> when (voice.serverReason.value) {
                        "no_credits", "suspended" -> R.string.settings_natural_voice_no_credits
                        "budget" -> R.string.settings_natural_voice_budget
                        "misconfigured" -> R.string.settings_natural_voice_misconfigured
                        "voice_not_found" -> R.string.settings_natural_voice_voice_not_found
                        "disabled" -> R.string.settings_natural_voice_no_server
                        else -> R.string.settings_natural_voice_unavailable
                    }
                    NaturalVoice.ServerState.UNREACHABLE -> R.string.settings_natural_voice_unreachable
                    else -> R.string.settings_natural_voice_checking
                }
            }
        )
        // Voice choice: only once the server has said which voices it offers.
        val current = voice.currentVoice
        view.settingsNaturalVoiceChoice.isVisible = on && current != null
        if (current != null) view.settingsNaturalVoiceChoose.text = getString(R.string.settings_natural_voice_choose, current.name)
        view.settingsNaturalVoiceChoose.isEnabled = voice.voices.value.size > 1
    }

    private fun openVoiceSettings() {
        val attempts = listOf(Intent("com.android.settings.TTS_SETTINGS"), Intent(Settings.ACTION_SETTINGS))
        for (intent in attempts) {
            try {
                startActivity(intent)
                return
            } catch (_: ActivityNotFoundException) {
                // try the next one
            }
        }
        Toast.makeText(requireContext(), R.string.settings_voice_unavailable, Toast.LENGTH_LONG).show()
    }

    // endregion

    // region Claude AI

    private fun bindAi() {
        val ai = app.aiService.availability

        binding.settingsAiSwitch.setOnCheckedChangeListener { _, checked ->
            if (updating) return@setOnCheckedChangeListener
            ai.setUserEnabled(checked)
            if (checked) viewLifecycleOwner.lifecycleScope.launch { ai.refresh(force = true) }
        }
        binding.settingsAiReaderSwitch.setOnCheckedChangeListener { _, checked ->
            if (!updating) ai.setReaderEnabled(checked)
        }
        binding.settingsAiCatalogSwitch.setOnCheckedChangeListener { _, checked ->
            if (!updating) ai.setCatalogEnabled(checked)
        }
        binding.settingsAiCheck.setOnClickListener {
            Toast.makeText(requireContext(), R.string.settings_ai_checking, Toast.LENGTH_SHORT).show()
            viewLifecycleOwner.lifecycleScope.launch { ai.refresh(force = true) }
        }
        binding.settingsAiChangeServer.setOnClickListener { showServerDialog(ai) }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { ai.refresh() }
                launch { ai.userEnabled.collect { renderAi(ai) } }
                launch { ai.available.collect { renderAi(ai) } }
                launch { ai.status.collect { renderAi(ai) } }
                launch { ai.readerEnabled.collect { renderAi(ai) } }
                launch { ai.catalogEnabled.collect { renderAi(ai) } }
            }
        }
    }

    private fun renderAi(ai: AiAvailability) {
        val view = _binding ?: return
        val on = ai.userEnabled.value
        updating = true
        view.settingsAiSwitch.isChecked = on
        view.settingsAiReaderSwitch.isChecked = ai.readerEnabled.value
        view.settingsAiCatalogSwitch.isChecked = ai.catalogEnabled.value
        updating = false
        view.settingsAiReaderSwitch.isEnabled = on
        view.settingsAiCatalogSwitch.isEnabled = on

        val status = ai.status.value
        view.settingsAiStatus.setText(
            when {
                !on -> R.string.settings_ai_status_off
                !ai.configured -> R.string.settings_ai_status_no_server
                !ai.isDriveConnected() -> R.string.settings_ai_status_needs_drive
                ai.available.value -> R.string.settings_ai_status_available
                status == null -> R.string.settings_ai_status_unreachable
                else -> R.string.settings_ai_status_unavailable
            }
        )

        val remaining = status?.remaining.orEmpty()
        view.settingsAiQuota.isVisible = on && ai.available.value && remaining.isNotEmpty()
        if (view.settingsAiQuota.isVisible) {
            val names = mapOf(
                "explain" to R.string.settings_ai_task_explain,
                "chapter_summary" to R.string.settings_ai_task_summary,
                "teacher_questions" to R.string.settings_ai_task_teacher,
                "book_info" to R.string.settings_ai_task_catalog,
            )
            val lines = remaining.entries.mapNotNull { (task, left) -> names[task]?.let { "${getString(it)}: $left" } }
            view.settingsAiQuota.text = getString(R.string.settings_ai_quota, lines.joinToString(" · "))
        }

        val server = ai.activeServer()
        view.settingsAiServer.text = when {
            server == null -> getString(R.string.settings_ai_server_none)
            ai.savedServer() != null -> getString(R.string.settings_ai_server_custom, AiServerUrl.hostOf(server) ?: server)
            else -> getString(R.string.settings_ai_server_builtin, AiServerUrl.hostOf(server) ?: server)
        }
    }

    private fun showServerDialog(ai: AiAvailability) {
        val field = EditText(requireContext()).apply {
            setSingleLine()
            hint = getString(R.string.settings_ai_server_hint)
            setText(ai.savedServer().orEmpty())
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            minHeight = (48 * resources.displayMetrics.density).toInt()
        }
        val padding = (24 * resources.displayMetrics.density).toInt()
        val holder = FrameLayout(requireContext()).apply {
            setPadding(padding, padding / 2, padding, 0)
            addView(field)
        }
        val builder = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.settings_ai_server_title)
            .setMessage(R.string.settings_ai_server_warning)
            .setView(holder)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.settings_ai_server_save, null)
        if (ai.savedServer() != null) {
            builder.setNeutralButton(R.string.settings_ai_server_default) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch { ai.changeServer(null) }
            }
        }
        val dialog = builder.show()
        // Validate before closing, so a wrong address keeps the dialog open.
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val url = AiServerUrl.normalize(field.text.toString(), allowLocalHttp = BuildConfig.DEBUG)
            if (url == null) {
                field.error = getString(R.string.settings_ai_server_invalid)
            } else {
                viewLifecycleOwner.lifecycleScope.launch { ai.changeServer(url) }
                dialog.dismiss()
            }
        }
    }

    // endregion

    // region Library

    private fun bindLibrary() {
        renderReadersRow()
        row(binding.settingsDriveRow, R.drawable.ic_cloud_done, R.string.settings_drive_title, getString(R.string.settings_drive_detail))
            .root.setOnClickListener { findNavController().navigate(R.id.sync) }

        row(binding.settingsStorageRow, R.drawable.ic_folder, R.string.settings_storage_title, getString(R.string.settings_storage_loading)).also {
            it.rowChevron.isVisible = false
            it.root.setOnClickListener { confirmClearTemporary() }
        }

        row(binding.settingsStatsRow, R.drawable.ic_stats, R.string.settings_stats_title, getString(R.string.settings_stats_detail))
            .root.setOnClickListener { findNavController().navigate(R.id.navigation_stats) }
    }

    private fun refreshStorage() {
        viewLifecycleOwner.lifecycleScope.launch {
            val books = app.bookRepository.books().first().sumOf { it.fileSize }
            val temporary = withContext(Dispatchers.IO) { app.onlineCache.sizeBytes() }
            val view = _binding ?: return@launch
            view.settingsStorageRow.rowDetail.text = getString(
                R.string.settings_storage_detail,
                Formatter.formatShortFileSize(requireContext(), books),
                Formatter.formatShortFileSize(requireContext(), temporary),
            )
        }
    }

    private fun confirmClearTemporary() {
        viewLifecycleOwner.lifecycleScope.launch {
            val temporary = withContext(Dispatchers.IO) { app.onlineCache.sizeBytes() }
            if (temporary <= 0) {
                Toast.makeText(requireContext(), R.string.settings_storage_nothing, Toast.LENGTH_SHORT).show()
                return@launch
            }
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.settings_storage_clear_title)
                .setMessage(getString(R.string.settings_storage_clear_message, Formatter.formatShortFileSize(requireContext(), temporary)))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.settings_storage_clear) { _, _ ->
                    viewLifecycleOwner.lifecycleScope.launch {
                        val freed = withContext(Dispatchers.IO) { app.onlineCache.clear() }
                        Toast.makeText(
                            requireContext(),
                            getString(R.string.settings_storage_freed, Formatter.formatShortFileSize(requireContext(), freed)),
                            Toast.LENGTH_SHORT,
                        ).show()
                        refreshStorage()
                    }
                }
                .show()
        }
    }

    // endregion

    /** Group tablet (several readers) or one person: automatic, or forced here. */
    private fun renderReadersRow() {
        val readers = app.readers
        val override = readers.modeOverride.value
        val detail = when (override) {
            true -> getString(R.string.settings_readers_group)
            false -> getString(R.string.settings_readers_single)
            null -> if (readers.isGroupTablet) {
                getString(R.string.settings_readers_auto_group, app.syncStateStore.accountDevices)
            } else {
                getString(R.string.settings_readers_auto_single)
            }
        }
        row(binding.settingsReadersRow, R.drawable.ic_nav_profile_outline, R.string.settings_readers_title, detail)
            .root.setOnClickListener {
                val options = arrayOf(
                    getString(R.string.settings_readers_auto),
                    getString(R.string.settings_readers_group),
                    getString(R.string.settings_readers_single),
                )
                val checked = when (readers.modeOverride.value) { null -> 0; true -> 1; false -> 2 }
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.settings_readers_title)
                    .setSingleChoiceItems(options, checked) { dialog, which ->
                        readers.setModeOverride(when (which) { 1 -> true; 2 -> false; else -> null })
                        dialog.dismiss()
                        renderReadersRow()
                    }
                    .show()
            }
    }

    private fun row(row: ItemSettingsRowBinding, icon: Int, title: Int, detail: String): ItemSettingsRowBinding {
        row.rowIcon.setImageResource(icon)
        row.rowTitle.setText(title)
        row.rowDetail.text = detail
        return row
    }

    override fun onStop() {
        stopNaturalVoiceSample()
        super.onStop()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
