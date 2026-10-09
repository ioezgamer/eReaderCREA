/*
 * Copyright 2021 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

package org.crea.reader.bookshelf

import android.content.Context
import android.content.res.ColorStateList
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import org.crea.reader.R
import org.crea.reader.data.model.Book
import org.crea.reader.databinding.ItemLibraryCoverBinding
import org.crea.reader.databinding.ItemLibraryListBinding
import org.crea.reader.databinding.ItemRecycleBookBinding
import org.crea.reader.utils.ReadingProgress
import org.crea.reader.utils.singleClick
import org.crea.reader.views.ViewMode

/** Draws "Mi biblioteca" as a list, a grid of cards or just covers, depending on [mode]. */
class BookshelfAdapter(
    private val onBookClick: (Book) -> Unit,
    private val onBookLongClick: (Book) -> Unit,
) : ListAdapter<Book, RecyclerView.ViewHolder>(BookListDiff()) {

    /** Changing it re-creates the visible items with the new layout. */
    var mode: ViewMode = ViewMode.GRID
        set(value) {
            if (field == value) return
            field = value
            @Suppress("NotifyDataSetChanged")
            notifyDataSetChanged()
        }

    override fun getItemViewType(position: Int): Int = mode.ordinal

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (ViewMode.entries[viewType]) {
            ViewMode.LIST -> ListHolder(ItemLibraryListBinding.inflate(inflater, parent, false))
            ViewMode.COVERS -> CoverHolder(ItemLibraryCoverBinding.inflate(inflater, parent, false))
            ViewMode.GRID -> GridHolder(ItemRecycleBookBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val book = getItem(position)
        when (holder) {
            is ListHolder -> holder.bind(book)
            is CoverHolder -> holder.bind(book)
            is GridHolder -> holder.bind(book)
        }
        holder.itemView.singleClick { onBookClick(book) }
        holder.itemView.setOnLongClickListener {
            onBookLongClick(book)
            true
        }
    }

    private inner class GridHolder(private val binding: ItemRecycleBookBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(book: Book) {
            val context = binding.root.context
            binding.bookshelfTitleText.text = book.title
            binding.bookshelfAuthorText.text = book.author.orEmpty()
            binding.bookshelfAuthorText.visibility = if (book.author.isNullOrBlank()) View.INVISIBLE else View.VISIBLE
            val progress = progressPercent(book.progression)
            binding.bookshelfProgress.visibility = if (progress in 1..99) View.VISIBLE else View.GONE
            binding.bookshelfProgress.setProgressCompat(progress, false)
            bindFormatBadge(context, binding.bookshelfFormatBadge, book)
            binding.bookshelfFavoriteBadge.isVisible = book.favorite
            binding.bookshelfMetaText.text = progressLabel(context, progress)
            CoverImages.request(book.cover).placeholder(R.drawable.cover).into(binding.bookshelfCoverImage)
        }
    }

    private inner class ListHolder(private val binding: ItemLibraryListBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(book: Book) {
            val context = binding.root.context
            binding.listTitle.text = book.title
            binding.listAuthor.text = book.author.orEmpty()
            binding.listAuthor.isVisible = !book.author.isNullOrBlank()
            val progress = progressPercent(book.progression)
            binding.listProgress.isVisible = progress in 1..99
            binding.listProgress.setProgressCompat(progress, false)
            binding.listMeta.text = listOf(
                progressLabel(context, progress),
                Formatter.formatShortFileSize(context, book.fileSize),
            ).joinToString(" · ")
            bindFormatBadge(context, binding.listFormat, book)
            binding.listFavorite.setImageResource(if (book.favorite) R.drawable.ic_favorite else R.drawable.ic_favorite_outline)
            binding.listFavorite.imageTintList = ColorStateList.valueOf(
                if (book.favorite) ContextCompat.getColor(context, R.color.crea_secondary)
                else MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOnSurfaceVariant)
            )
            binding.listFavorite.setOnClickListener { onFavoriteToggle?.invoke(book) }
            CoverImages.request(book.cover).placeholder(R.drawable.cover).into(binding.listCover)
        }
    }

    private inner class CoverHolder(private val binding: ItemLibraryCoverBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(book: Book) {
            val progress = progressPercent(book.progression)
            binding.coverProgress.isVisible = progress in 1..99
            binding.coverProgress.setProgressCompat(progress, false)
            binding.coverFavorite.isVisible = book.favorite
            binding.coverImage.contentDescription = book.title
            CoverImages.request(book.cover).placeholder(R.drawable.cover).into(binding.coverImage)
        }
    }

    /** Set by the screen so the heart in the list view can be tapped. */
    var onFavoriteToggle: ((Book) -> Unit)? = null

    private fun bindFormatBadge(context: Context, badge: android.widget.TextView, book: Book) {
        badge.text = formatLabel(book)
        ViewCompat.setBackgroundTintList(
            badge,
            ColorStateList.valueOf(
                ContextCompat.getColor(
                    context,
                    if (formatLabel(book) == "PDF") R.color.crea_badge_pdf else R.color.crea_badge_epub
                )
            )
        )
    }

    private fun progressLabel(context: Context, progress: Int): String = when {
        progress >= 100 -> context.getString(R.string.book_finished_label)
        progress > 0 -> context.getString(R.string.book_progress_label, progress)
        else -> context.getString(R.string.book_new_label)
    }

    private class BookListDiff : DiffUtil.ItemCallback<Book>() {
        override fun areItemsTheSame(oldItem: Book, newItem: Book): Boolean = oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: Book, newItem: Book): Boolean = oldItem == newItem
    }

    companion object {
        fun formatLabel(book: Book): String = when {
            book.rawMediaType.contains("pdf", ignoreCase = true) -> "PDF"
            book.rawMediaType.contains("epub", ignoreCase = true) -> "EPUB"
            else -> book.rawMediaType.substringAfterLast('/').uppercase()
        }

        fun progressPercent(rawLocator: String?): Int = ReadingProgress.percent(rawLocator)
    }
}
