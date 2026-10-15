package org.crea.reader.stats

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import org.crea.reader.databinding.FragmentStatsBinding

/** The statistics, opened over the reader without leaving the book. */
class StatsBottomSheetDialogFragment : BottomSheetDialogFragment() {
    private var _binding: FragmentStatsBinding? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val binding = FragmentStatsBinding.inflate(inflater, container, false)
        _binding = binding
        return binding.root
    }

    override fun onStart() {
        super.onStart()
        _binding?.let { StatsView.render(it, requireContext(), viewLifecycleOwner.lifecycleScope) }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
