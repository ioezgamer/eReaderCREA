package org.crea.reader.online

import android.content.Context
import android.content.res.ColorStateList
import android.text.format.Formatter
import android.view.LayoutInflater
import android.widget.ImageView
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import com.squareup.picasso.Picasso
import java.io.File
import org.crea.reader.R
import org.crea.reader.bookshelf.BookshelfAdapter
import org.crea.reader.bookshelf.CoverImages
import org.crea.reader.databinding.ItemCatalogBookBinding
import org.crea.reader.databinding.ItemCatalogCoverBinding
import org.crea.reader.databinding.ItemCatalogGridBinding
import com.google.android.material.card.MaterialCardView
import org.crea.reader.views.ViewMode

class OnlineBookAdapter(
    private val onOpenDetails: (CatalogRow) -> Unit,
    private val onUpdate: (CatalogRow) -> Unit,
    private val onToggleSelect: (CatalogRow) -> Unit,
    private val onRead: (CatalogRow) -> Unit,
    private val onDownload: (CatalogRow) -> Unit,
    private val onCancel: (CatalogRow) -> Unit,
) : ListAdapter<CatalogRow, RecyclerView.ViewHolder>(Diff()) {

    /** Changing it re-creates the visible items with the new layout. */
    var mode: ViewMode = ViewMode.LIST
        set(value) {
            if (field == value) return
            field = value
            @Suppress("NotifyDataSetChanged")
            notifyDataSetChanged()
        }

    /** Ids of the books picked for a multiple download; while it is not empty, taps pick instead of open. */
    var selection: Set<String> = emptySet()
        set(value) {
            if (field == value) return
            field = value
            @Suppress("NotifyDataSetChanged")
            notifyDataSetChanged()
        }

    private fun bindSelection(card: MaterialCardView, row: CatalogRow) {
        val selecting = selection.isNotEmpty()
        card.isCheckable = selecting
        card.isChecked = row.book.driveFileId in selection
        // The card checks itself when tapped; the selection is ours to decide.
        card.setOnClickListener {
            if (selection.isNotEmpty()) onToggleSelect(row) else onOpenDetails(row)
        }
        card.setOnLongClickListener {
            if (selection.isEmpty()) onToggleSelect(row)
            true
        }
    }

    override fun getItemViewType(position: Int): Int = mode.ordinal

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (ViewMode.entries[viewType]) {
            ViewMode.LIST -> ListHolder(ItemCatalogBookBinding.inflate(inflater, parent, false))
            ViewMode.GRID -> GridHolder(ItemCatalogGridBinding.inflate(inflater, parent, false))
            ViewMode.COVERS -> CoverHolder(ItemCatalogCoverBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val row = getItem(position)
        when (holder) {
            is ListHolder -> holder.bind(row)
            is GridHolder -> holder.bind(row)
            is CoverHolder -> holder.bind(row)
        }
        // The grids have no buttons: tapping a book opens the same sheet of actions.
        bindSelection(holder.itemView as MaterialCardView, row)
    }

    /** A downloaded book has its real cover; otherwise Drive's thumbnail or the one read from the EPUB. */
    private fun loadCover(view: ImageView, row: CatalogRow) {
        val path = row.local?.cover ?: row.book.coverPath?.takeIf { File(it).exists() }
        if (path != null) {
            CoverImages.request(path)
                .placeholder(GeneratedCover(row.book.title, row.book.author))
                .error(GeneratedCover(row.book.title, row.book.author))
                .into(view)
        } else {
            // No cover anywhere (yet): draw one from the title so the card never looks empty.
            Picasso.get().cancelRequest(view)
            view.setImageDrawable(GeneratedCover(row.book.title, row.book.author))
        }
    }

    private inner class GridHolder(private val binding: ItemCatalogGridBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(row: CatalogRow) {
            val context = binding.root.context
            loadCover(binding.gridCover, row)
            binding.gridTitle.text = row.book.title
            binding.gridFormat.text = row.book.format
            ViewCompat.setBackgroundTintList(
                binding.gridFormat,
                ColorStateList.valueOf(
                    ContextCompat.getColor(
                        context,
                        if (row.book.format == "PDF") R.color.crea_badge_pdf else R.color.crea_badge_epub
                    )
                )
            )
            binding.gridDownloaded.isVisible = row.downloaded
            bindProgress(binding.gridProgress, row)

            val transfer = row.transfer
            val muted = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOnSurfaceVariant)
            val green = ContextCompat.getColor(context, R.color.crea_green)
            val (text, color) = when {
                transfer?.failure != null ->
                    context.getString(OnlineMessages.failure(transfer.failure)) to
                        MaterialColors.getColor(binding.root, androidx.appcompat.R.attr.colorError)
                transfer != null -> context.getString(
                    OnlineMessages.busy(transfer.purpose),
                    transfer.percent
                ) to MaterialColors.getColor(binding.root, androidx.appcompat.R.attr.colorPrimary)
                row.downloaded && row.updateAvailable ->
                    context.getString(R.string.catalog_update_available) to ContextCompat.getColor(context, R.color.crea_tertiary)
                row.downloaded -> context.getString(R.string.catalog_downloaded) to green
                else -> context.getString(R.string.catalog_available_online) to muted
            }
            binding.gridStatus.text = text
            binding.gridStatus.setTextColor(color)
        }
    }

    private inner class CoverHolder(private val binding: ItemCatalogCoverBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(row: CatalogRow) {
            loadCover(binding.coverImage, row)
            binding.coverImage.contentDescription = row.book.title
            binding.coverDownloaded.isVisible = row.downloaded
            bindProgress(binding.coverProgress, row)
        }
    }

    /** The bar under a cover: transfer progress while one is running, otherwise how far the book has been read. */
    private fun bindProgress(bar: com.google.android.material.progressindicator.LinearProgressIndicator, row: CatalogRow) {
        val transfer = row.transfer
        when {
            transfer?.active == true -> {
                bar.isVisible = true
                if (transfer.total > 0 && transfer.bytes > 0) {
                    bar.isIndeterminate = false
                    bar.setProgressCompat(transfer.percent, false)
                } else {
                    bar.isIndeterminate = true
                }
            }
            row.generalPercent in 1..99 -> {
                bar.isVisible = true
                bar.isIndeterminate = false
                bar.setProgressCompat(row.generalPercent, false)
            }
            else -> bar.isVisible = false
        }
    }


    inner class ListHolder(private val binding: ItemCatalogBookBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(row: CatalogRow) {
            val context = binding.root.context
            val book = row.book
            val transfer = row.transfer

            binding.catalogTitle.text = book.title
            binding.catalogAuthor.text = book.author.orEmpty()
            binding.catalogAuthor.isVisible = !book.author.isNullOrBlank()
            bindCover(row)
            binding.catalogFormat.text = book.format
            ViewCompat.setBackgroundTintList(
                binding.catalogFormat,
                ColorStateList.valueOf(
                    ContextCompat.getColor(
                        context,
                        if (book.format == "PDF") R.color.crea_badge_pdf else R.color.crea_badge_epub
                    )
                )
            )
            bindStatus(context, row)
            bindMeta(context, row)
            bindDevices(context, row)

            val busy = transfer?.active == true
            val selecting = selection.isNotEmpty()
            binding.catalogActions.isVisible = !busy && !selecting
            binding.catalogBusy.isVisible = busy
            if (busy && transfer != null) bindBusy(context, transfer) else bindActions(row)

            binding.catalogBusyButton.setOnClickListener { onCancel(row) }
        }

        private fun bindCover(row: CatalogRow) = loadCover(binding.catalogCover, row)

        /** Where each device of the account is in the book; only shown when another device has read it. */
        private fun bindDevices(context: Context, row: CatalogRow) {
            val others = row.others.take(MAX_DEVICES_SHOWN)
            if (others.isEmpty()) {
                binding.catalogDevices.isVisible = false
                return
            }
            val own = row.ownPercent.takeIf { it > 0 }
                ?.let { context.getString(R.string.catalog_device_progress, context.getString(R.string.catalog_this_device), it) }
            binding.catalogDevices.text = (listOfNotNull(own) + others.map {
                context.getString(R.string.catalog_device_progress, it.deviceName, it.percent)
            }).joinToString(" · ")
            binding.catalogDevices.isVisible = true
        }

        private fun bindStatus(context: Context, row: CatalogRow) {
            val green = ContextCompat.getColor(context, R.color.crea_green)
            if (row.downloaded) {
                binding.catalogStatusIcon.setImageResource(R.drawable.ic_check_circle)
                binding.catalogStatusIcon.imageTintList = ColorStateList.valueOf(green)
                if (row.updateAvailable) {
                    val tertiary = ContextCompat.getColor(context, R.color.crea_tertiary)
                    binding.catalogStatusIcon.imageTintList = ColorStateList.valueOf(tertiary)
                    binding.catalogStatusText.setText(R.string.catalog_update_available)
                    binding.catalogStatusText.setTextColor(tertiary)
                } else {
                    binding.catalogStatusText.setText(R.string.catalog_available_offline)
                    binding.catalogStatusText.setTextColor(green)
                }
            } else {
                val muted = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOnSurfaceVariant)
                binding.catalogStatusIcon.setImageResource(R.drawable.ic_nav_online_outline)
                binding.catalogStatusIcon.imageTintList = ColorStateList.valueOf(muted)
                binding.catalogStatusText.setText(R.string.catalog_available_online)
                binding.catalogStatusText.setTextColor(muted)
            }
        }

        private fun bindMeta(context: Context, row: CatalogRow) {
            val failure = row.transfer?.failure
            if (failure != null) {
                binding.catalogMeta.setText(OnlineMessages.failure(failure))
                binding.catalogMeta.setTextColor(
                    MaterialColors.getColor(binding.root, androidx.appcompat.R.attr.colorError)
                )
                return
            }
            binding.catalogMeta.setTextColor(
                MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOnSurfaceVariant)
            )
            val progress = row.generalPercent
            binding.catalogMeta.text = listOfNotNull(
                row.book.size.takeIf { it > 0 }?.let { Formatter.formatShortFileSize(context, it) },
                row.book.category,
                when {
                    progress >= 100 -> context.getString(R.string.book_finished_label)
                    progress > 0 -> context.getString(R.string.book_progress_label, progress)
                    else -> null
                },
            ).joinToString(" · ")
        }

        private fun bindBusy(context: Context, transfer: OnlineBookManager.Transfer) {
            val percent = transfer.percent
            binding.catalogBusyText.text = context.getString(
                OnlineMessages.busy(transfer.purpose),
                percent
            )
            if (transfer.total > 0 && transfer.bytes > 0) {
                binding.catalogBusyProgress.isIndeterminate = false
                binding.catalogBusyProgress.setProgressCompat(percent, true)
            } else {
                binding.catalogBusyProgress.isIndeterminate = true
            }
        }

        private fun bindActions(row: CatalogRow) {
            val context = binding.root.context
            if (row.downloaded && row.updateAvailable) {
                binding.catalogReadButton.setText(R.string.catalog_open)
                binding.catalogSaveButton.setText(R.string.catalog_update)
                binding.catalogSaveButton.setIconResource(R.drawable.ic_sync)
                val primary = ColorStateList.valueOf(
                    MaterialColors.getColor(binding.root, androidx.appcompat.R.attr.colorPrimary)
                )
                binding.catalogSaveButton.setTextColor(primary)
                binding.catalogSaveButton.iconTint = primary
                binding.catalogSaveButton.isClickable = true
                binding.catalogSaveButton.setOnClickListener { onUpdate(row) }
            } else if (row.downloaded) {
                binding.catalogReadButton.setText(R.string.catalog_open)
                binding.catalogSaveButton.setText(R.string.catalog_downloaded)
                binding.catalogSaveButton.setIconResource(R.drawable.ic_download_done)
                val green = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.crea_green))
                binding.catalogSaveButton.setTextColor(green)
                binding.catalogSaveButton.iconTint = green
                binding.catalogSaveButton.isClickable = false
                binding.catalogSaveButton.setOnClickListener(null)
            } else {
                binding.catalogReadButton.setText(R.string.catalog_read_online)
                binding.catalogSaveButton.setText(R.string.catalog_download)
                binding.catalogSaveButton.setIconResource(R.drawable.ic_download)
                val primary = ColorStateList.valueOf(
                    MaterialColors.getColor(binding.root, androidx.appcompat.R.attr.colorPrimary)
                )
                binding.catalogSaveButton.setTextColor(primary)
                binding.catalogSaveButton.iconTint = primary
                binding.catalogSaveButton.isClickable = true
                binding.catalogSaveButton.setOnClickListener { onDownload(row) }
            }
            binding.catalogReadButton.setOnClickListener { onRead(row) }
        }
    }

    private companion object {
        const val MAX_DEVICES_SHOWN = 3
    }

    private class Diff : DiffUtil.ItemCallback<CatalogRow>() {
        override fun areItemsTheSame(oldItem: CatalogRow, newItem: CatalogRow) =
            oldItem.book.driveFileId == newItem.book.driveFileId

        override fun areContentsTheSame(oldItem: CatalogRow, newItem: CatalogRow) = oldItem == newItem
    }
}
