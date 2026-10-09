package org.crea.reader.community

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.annotation.StringRes
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.squareup.picasso.Picasso
import java.io.File
import org.crea.reader.bookshelf.CoverImages
import org.crea.reader.databinding.ItemCommunityBookBinding
import org.crea.reader.databinding.ItemCommunityHeaderBinding
import org.crea.reader.online.CatalogRow
import org.crea.reader.online.GeneratedCover

sealed class CommunityItem {
    abstract val key: String

    data class Header(@StringRes val title: Int, @StringRes val detail: Int?) : CommunityItem() {
        override val key get() = "header-$title"
    }

    /** One book with a line saying why it is there, and the percentage when it is somebody's reading. */
    data class Entry(
        override val key: String,
        val row: CatalogRow,
        val line: String,
        val percent: Int? = null,
    ) : CommunityItem()
}

class CommunityAdapter(
    private val onOpen: (CatalogRow) -> Unit,
) : ListAdapter<CommunityItem, RecyclerView.ViewHolder>(Diff()) {

    override fun getItemViewType(position: Int): Int = if (getItem(position) is CommunityItem.Header) HEADER else ENTRY

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == HEADER) {
            HeaderHolder(ItemCommunityHeaderBinding.inflate(inflater, parent, false))
        } else {
            EntryHolder(ItemCommunityBookBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is CommunityItem.Header -> (holder as HeaderHolder).bind(item)
            is CommunityItem.Entry -> (holder as EntryHolder).bind(item)
        }
    }

    private class HeaderHolder(private val binding: ItemCommunityHeaderBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: CommunityItem.Header) {
            binding.headerTitle.setText(item.title)
            binding.headerDetail.isVisible = item.detail != null
            item.detail?.let { binding.headerDetail.setText(it) }
        }
    }

    private inner class EntryHolder(private val binding: ItemCommunityBookBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: CommunityItem.Entry) {
            val row = item.row
            binding.communityTitle.text = row.book.title
            binding.communityLine.text = item.line
            binding.communityDownloaded.isVisible = row.downloaded
            binding.communityProgress.isVisible = item.percent != null
            binding.communityPercent.isVisible = item.percent != null
            item.percent?.let {
                binding.communityProgress.setProgressCompat(it, false)
                binding.communityPercent.text = "$it%"
            }
            val path = row.local?.cover ?: row.book.coverPath?.takeIf { File(it).exists() }
            if (path != null) {
                CoverImages.request(path)
                    .placeholder(GeneratedCover(row.book.title, row.displayAuthor))
                    .error(GeneratedCover(row.book.title, row.displayAuthor))
                    .into(binding.communityCover)
            } else {
                Picasso.get().cancelRequest(binding.communityCover)
                binding.communityCover.setImageDrawable(GeneratedCover(row.book.title, row.displayAuthor))
            }
            binding.root.setOnClickListener { onOpen(row) }
        }
    }

    private class Diff : DiffUtil.ItemCallback<CommunityItem>() {
        override fun areItemsTheSame(oldItem: CommunityItem, newItem: CommunityItem) = oldItem.key == newItem.key
        override fun areContentsTheSame(oldItem: CommunityItem, newItem: CommunityItem) = oldItem == newItem
    }

    private companion object {
        const val HEADER = 0
        const val ENTRY = 1
    }
}
