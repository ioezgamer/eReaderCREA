package org.crea.reader.online

import java.text.Collator

/**
 * Categories are Drive sub-folders and may be nested: "Ciencias / Biología" is the folder
 * "Biología" inside "Ciencias". Choosing a category also shows the books of its sub-categories.
 * Free of Android so it can be tested.
 */
object CategoryPaths {
    const val SEPARATOR = " / "

    fun join(parent: String?, name: String): String {
        val clean = name.trim()
        return if (parent.isNullOrEmpty()) clean else parent + SEPARATOR + clean
    }

    fun segments(path: String): List<String> = path.split(SEPARATOR)

    /** The folder's own name, for chips that already show where they sit. */
    fun leaf(path: String): String = segments(path).last()

    fun parent(path: String): String? =
        segments(path).dropLast(1).takeIf { it.isNotEmpty() }?.joinToString(SEPARATOR)

    /** [path] is [selected] or lies somewhere below it. */
    fun matches(path: String?, selected: String?): Boolean = when {
        selected == null -> true
        path == null -> false
        else -> path == selected || path.startsWith(selected + SEPARATOR)
    }

    /**
     * The chips to show: the top-level categories and, for the chosen one, its sub-categories down
     * to the chosen level, so a deep tree never floods the screen.
     * Every intermediate folder appears even when it holds no book directly.
     */
    fun chips(paths: Collection<String>, selected: String?): List<String> {
        val all = paths.flatMap { ancestorsAndSelf(it) }.toSet()
        val collator = Collator.getInstance().apply { strength = Collator.PRIMARY }
        val open = selected?.let { ancestorsAndSelf(it).toSet() }.orEmpty()
        val result = mutableListOf<String>()
        fun add(parent: String?) {
            val children = all.filter { parent(it) == parent }.sortedWith(compareBy(collator) { leaf(it) })
            for (child in children) {
                result += child
                if (child in open) add(child)
            }
        }
        add(null)
        return result
    }

    fun depth(path: String): Int = segments(path).size - 1

    private fun ancestorsAndSelf(path: String): List<String> {
        val parts = segments(path)
        return parts.indices.map { parts.subList(0, it + 1).joinToString(SEPARATOR) }
    }
}
