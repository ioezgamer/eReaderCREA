package org.crea.reader.views

import android.content.Context
import android.view.LayoutInflater
import androidx.core.view.isVisible
import com.google.android.material.bottomsheet.BottomSheetDialog
import org.crea.reader.R
import org.crea.reader.databinding.SheetViewOptionsBinding

/** The "Vista" picker shared by Mi biblioteca and the Catálogo en línea; changes apply immediately. */
object ViewOptionsSheet {
    fun show(context: Context, inflater: LayoutInflater, current: ViewOptions, onChange: (ViewOptions) -> Unit) {
        val dialog = BottomSheetDialog(context)
        val sheet = SheetViewOptionsBinding.inflate(inflater)
        var options = current

        fun render() {
            sheet.viewModeGroup.check(
                when (options.mode) {
                    ViewMode.LIST -> R.id.view_mode_list
                    ViewMode.GRID -> R.id.view_mode_grid
                    ViewMode.COVERS -> R.id.view_mode_covers
                }
            )
            sheet.viewSizeGroup.check(
                when (options.size) {
                    CoverSize.SMALL -> R.id.view_size_small
                    CoverSize.MEDIUM -> R.id.view_size_medium
                    CoverSize.LARGE -> R.id.view_size_large
                }
            )
            // The list has one fixed layout, so only the grids have a size.
            val sized = options.mode != ViewMode.LIST
            sheet.viewSizeLabel.isVisible = sized
            sheet.viewSizeGroup.isVisible = sized
        }

        fun change(next: ViewOptions) {
            if (next == options) return
            options = next
            render()
            onChange(next)
        }

        render()
        sheet.viewModeGroup.setOnCheckedStateChangeListener { _, ids ->
            val mode = when (ids.firstOrNull()) {
                R.id.view_mode_list -> ViewMode.LIST
                R.id.view_mode_covers -> ViewMode.COVERS
                else -> ViewMode.GRID
            }
            change(options.copy(mode = mode))
        }
        sheet.viewSizeGroup.setOnCheckedStateChangeListener { _, ids ->
            val size = when (ids.firstOrNull()) {
                R.id.view_size_small -> CoverSize.SMALL
                R.id.view_size_large -> CoverSize.LARGE
                else -> CoverSize.MEDIUM
            }
            change(options.copy(size = size))
        }
        dialog.setContentView(sheet.root)
        dialog.show()
    }
}
