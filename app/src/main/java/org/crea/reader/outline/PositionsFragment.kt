package org.crea.reader.outline

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.setFragmentResult
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch
import org.crea.reader.R
import org.crea.reader.databinding.FragmentListviewBinding
import org.crea.reader.reader.ReaderViewModel
import org.crea.reader.utils.viewLifecycle
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.positions

/**
 * Page list for books that do not ship one: every position of the book, numbered like the page
 * indicator in the reader ("Página 12"), with the chapter it belongs to when known.
 */
class PositionsFragment : Fragment() {

    private lateinit var publication: Publication
    private var binding: FragmentListviewBinding by viewLifecycle()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        publication = ViewModelProvider(requireActivity())[ReaderViewModel::class.java].publication
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        binding = FragmentListviewBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.listView.layoutManager = LinearLayoutManager(requireContext())
        binding.listView.addItemDecoration(DividerItemDecoration(requireContext(), LinearLayoutManager.VERTICAL))
        viewLifecycleOwner.lifecycleScope.launch {
            val positions = publication.positions()
            binding.listView.adapter = PositionsAdapter(positions) { locator ->
                setFragmentResult(OutlineContract.REQUEST_KEY, OutlineContract.createResult(locator))
            }
            binding.showEmpty(positions.isEmpty(), R.string.outline_empty_pages)
        }
    }
}

private class PositionsAdapter(
    private val positions: List<Locator>,
    private val onSelected: (Locator) -> Unit,
) : RecyclerView.Adapter<PositionsAdapter.Holder>() {

    class Holder(val text: TextView) : RecyclerView.ViewHolder(text)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_recycle_position, parent, false) as TextView
        return Holder(view)
    }

    override fun getItemCount(): Int = positions.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val locator = positions[position]
        val number = locator.locations.position ?: (position + 1)
        val label = holder.text.context.getString(R.string.outline_page_number, number)
        holder.text.text = locator.title?.takeIf { it.isNotBlank() }?.let { "$label · $it" } ?: label
        holder.text.setOnClickListener { onSelected(locator) }
    }
}
