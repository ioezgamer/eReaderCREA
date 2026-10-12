package org.crea.reader.online

import android.view.LayoutInflater
import android.view.View
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import org.crea.reader.R

/**
 * Category chips for the catalog and "Mi biblioteca": the top-level folders and, under the chosen
 * one, its sub-folders ("› Biología"). Tapping the chosen chip again shows everything.
 */
object CategoryChips {

    fun render(
        group: ChipGroup,
        inflater: LayoutInflater,
        paths: Collection<String>,
        selected: String?,
        onSelect: (String?) -> Unit,
    ) {
        group.setOnCheckedStateChangeListener(null)
        group.removeAllViews()
        val byId = mutableMapOf<Int, String>()
        for (path in CategoryPaths.chips(paths, selected)) {
            val chip = inflater.inflate(R.layout.item_filter_chip, group, false) as Chip
            chip.id = View.generateViewId()
            val depth = CategoryPaths.depth(path)
            chip.text = if (depth == 0) path else "›".repeat(depth) + " " + CategoryPaths.leaf(path)
            // TalkBack reads the whole path ("Ciencias, Biología") instead of the arrows.
            chip.contentDescription = CategoryPaths.segments(path).joinToString(", ")
            chip.isChecked = path == selected
            byId[chip.id] = path
            group.addView(chip)
        }
        group.setOnCheckedStateChangeListener { _, checkedIds ->
            onSelect(checkedIds.firstOrNull()?.let { byId[it] })
        }
    }
}
