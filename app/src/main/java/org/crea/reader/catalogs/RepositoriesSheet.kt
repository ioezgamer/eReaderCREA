package org.crea.reader.catalogs

import android.content.Context
import android.content.Intent
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.net.toUri
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.color.MaterialColors
import org.crea.reader.R
import org.crea.reader.databinding.ItemRepositoryBinding
import org.crea.reader.databinding.SheetRepositoriesBinding

/** "Agregar repositorios": the directory of free EPUB sources, each added to Explorar or opened on the web. */
object RepositoriesSheet {

    fun show(
        context: Context,
        inflater: LayoutInflater,
        added: Set<String>,
        add: (title: String, href: String) -> Unit,
        remove: (href: String) -> Unit,
        custom: () -> Unit,
    ) {
        val dialog = BottomSheetDialog(context)
        val sheet = SheetRepositoriesBinding.inflate(inflater)
        val installed = added.toMutableSet()

        fun render() {
            sheet.reposContainer.removeAllViews()
            FreeRepositories.Group.entries.forEach { group ->
                sheet.reposContainer.addView(header(context, group.title))
                FreeRepositories.all.filter { it.group == group }.forEach { repo ->
                    val row = ItemRepositoryBinding.inflate(inflater, sheet.reposContainer, false)
                    row.repoName.text = repo.name
                    row.repoDescription.text = repo.description
                    val feed = repo.feedHref
                    when {
                        feed == null -> {
                            row.repoAction.setText(R.string.repos_visit)
                            row.repoAction.setOnClickListener { openWeb(context, repo.webUrl) }
                        }
                        feed in installed -> {
                            row.repoAction.setText(R.string.repos_added)
                            row.repoAction.setOnClickListener {
                                installed.remove(feed)
                                remove(feed)
                                render()
                            }
                        }
                        else -> {
                            row.repoAction.setText(R.string.repos_add)
                            row.repoAction.setOnClickListener {
                                installed.add(feed)
                                add(repo.name, feed)
                                render()
                            }
                        }
                    }
                    sheet.reposContainer.addView(row.root)
                }
            }
        }
        render()

        sheet.reposCustom.setOnClickListener {
            dialog.dismiss()
            custom()
        }
        dialog.setContentView(sheet.root)
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
        dialog.show()
    }

    private fun header(context: Context, text: String) = TextView(context).apply {
        this.text = text
        setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelLarge)
        setTextColor(MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary))
        val pad = (context.resources.displayMetrics.density * 24).toInt()
        setPadding(pad, pad / 2, pad, 0)
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun openWeb(context: Context, url: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
    }
}
