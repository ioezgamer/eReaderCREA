package org.crea.reader.stats

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import org.crea.reader.databinding.FragmentStatsBinding

/** Reading time, pages, streak and finished books. Everything is computed from this device. */
class StatsFragment : Fragment() {
    private var _binding: FragmentStatsBinding? = null
    private val binding get() = requireNotNull(_binding)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentStatsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onStart() {
        super.onStart()
        StatsView.render(binding, requireContext(), viewLifecycleOwner.lifecycleScope)
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
