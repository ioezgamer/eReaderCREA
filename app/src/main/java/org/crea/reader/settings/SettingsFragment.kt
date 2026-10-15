package org.crea.reader.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import org.crea.reader.AppLanguage
import org.crea.reader.BuildConfig
import org.crea.reader.R
import org.crea.reader.databinding.FragmentSettingsBinding

class SettingsFragment : Fragment() {
    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = requireNotNull(_binding)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val current = AppLanguage.saved(requireContext())
        binding.settingsLanguageGroup.check(
            if (current == AppLanguage.ENGLISH) R.id.settings_language_en else R.id.settings_language_es
        )
        // The screen is recreated by the language switch, so only act on a real change.
        binding.settingsLanguageGroup.setOnCheckedChangeListener { _, checkedId ->
            val tag = if (checkedId == R.id.settings_language_en) AppLanguage.ENGLISH else AppLanguage.SPANISH
            if (tag != AppLanguage.saved(requireContext())) AppLanguage.select(requireContext(), tag)
        }

        binding.settingsDriveRow.setOnClickListener {
            findNavController().navigate(R.id.sync)
        }
        binding.settingsStatsRow.setOnClickListener {
            findNavController().navigate(R.id.navigation_stats)
        }
        val ai = (requireContext().applicationContext as org.crea.reader.Application).aiService.availability
        // Without a configured server this build has no AI features, so there is nothing to switch.
        binding.settingsAiRow.isVisible = ai.configured
        binding.settingsAiSwitch.isChecked = ai.userEnabled.value
        binding.settingsAiSwitch.setOnCheckedChangeListener { _, checked -> ai.setUserEnabled(checked) }
        binding.settingsVersion.text = getString(R.string.settings_version, BuildConfig.VERSION_NAME)
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
