package org.crea.reader.online

import android.content.Context
import android.text.format.Formatter
import android.view.LayoutInflater
import androidx.core.view.isVisible
import com.google.android.material.bottomsheet.BottomSheetDialog
import org.crea.reader.R
import org.crea.reader.databinding.ItemSheetActionBinding
import org.crea.reader.databinding.SheetCatalogBookBinding

/** The actions for one catalog book (read online, download, open, update, cancel), shared by every screen that lists them. */
object CatalogBookSheet {

    class Actions(
        val readOnline: (CatalogRow) -> Unit,
        val download: (CatalogRow) -> Unit,
        val update: (CatalogRow) -> Unit,
        val openLocal: (CatalogRow) -> Unit,
        val cancel: (CatalogRow) -> Unit,
    )

    fun show(context: Context, inflater: LayoutInflater, row: CatalogRow, actions: Actions) {
        val dialog = BottomSheetDialog(context)
        val sheet = SheetCatalogBookBinding.inflate(inflater)
        val book = row.book
        sheet.catalogSheetTitle.text = book.title
        sheet.catalogSheetDetail.text = listOfNotNull(
            book.author,
            book.format,
            book.size.takeIf { it > 0 }?.let { Formatter.formatShortFileSize(context, it) },
            book.category,
        ).joinToString(" · ")

        val busy = row.transfer?.active == true
        sheet.catalogSheetRead.root.isVisible = !busy
        sheet.catalogSheetSave.root.isVisible = !busy && (!row.downloaded || row.updateAvailable)
        sheet.catalogSheetCancel.root.isVisible = busy

        if (row.downloaded) {
            bind(sheet.catalogSheetRead, R.drawable.ic_menu_book, context.getString(R.string.catalog_sheet_open_title), context.getString(R.string.catalog_sheet_open_detail)) {
                dialog.dismiss()
                actions.openLocal(row)
            }
            if (row.updateAvailable) {
                bind(sheet.catalogSheetSave, R.drawable.ic_sync, context.getString(R.string.catalog_sheet_update_title), context.getString(R.string.catalog_sheet_update_detail)) {
                    dialog.dismiss()
                    actions.update(row)
                }
            }
        } else {
            bind(sheet.catalogSheetRead, R.drawable.ic_menu_book, context.getString(R.string.catalog_read_online), context.getString(R.string.catalog_sheet_read_detail)) {
                dialog.dismiss()
                actions.readOnline(row)
            }
            val size = Formatter.formatShortFileSize(context, book.size)
            bind(sheet.catalogSheetSave, R.drawable.ic_download, context.getString(R.string.catalog_sheet_download_title), context.getString(R.string.catalog_sheet_download_detail, size)) {
                dialog.dismiss()
                actions.download(row)
            }
        }
        bind(sheet.catalogSheetCancel, R.drawable.ic_close, context.getString(R.string.catalog_sheet_cancel_title), context.getString(R.string.catalog_sheet_cancel_detail)) {
            dialog.dismiss()
            actions.cancel(row)
        }
        dialog.setContentView(sheet.root)
        dialog.show()
    }

    private fun bind(row: ItemSheetActionBinding, icon: Int, title: String, detail: String, onClick: () -> Unit) {
        row.sheetActionIcon.setImageResource(icon)
        row.sheetActionTitle.text = title
        row.sheetActionDetail.text = detail
        row.root.setOnClickListener { onClick() }
    }
}
