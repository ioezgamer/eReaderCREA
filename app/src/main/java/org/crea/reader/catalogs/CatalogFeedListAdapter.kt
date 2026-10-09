/*
 * Copyright 2021 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

package org.crea.reader.catalogs

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.net.toUri
import androidx.core.os.bundleOf
import androidx.core.view.ViewCompat
import androidx.navigation.Navigation
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import org.crea.reader.R
import org.crea.reader.data.model.Catalog
import org.crea.reader.databinding.ItemCatalogCardBinding

class CatalogFeedListAdapter(private val onLongClick: (Catalog) -> Unit) :
    ListAdapter<Catalog, CatalogFeedListAdapter.ViewHolder>(CatalogListDiff()) {

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): ViewHolder {
        return ViewHolder(
            ItemCatalogCardBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        )
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, position: Int) {
        val catalog = getItem(position)

        viewHolder.bind(catalog)
    }

    inner class ViewHolder(private val binding: ItemCatalogCardBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(catalog: Catalog) {
            val context = binding.root.context
            val entry = DefaultCatalogs.find(catalog.href)

            binding.catalogCardTitle.text = catalog.title
            binding.catalogCardSubtitle.text = if (entry != null) {
                "${entry.description}\n${context.getString(entry.source.subtitleRes)}"
            } else {
                val host = catalog.href.toUri().host.orEmpty()
                "${context.getString(R.string.catalog_custom_subtitle)}\n$host"
            }
            binding.catalogCardIcon.setImageResource(
                if (entry?.person == true) R.drawable.ic_person else R.drawable.ic_menu_book
            )
            // Logo colors: blue for textos.info, orange for Gutenberg, pink for the user's own.
            val accent = when (entry?.source) {
                DefaultCatalogs.Source.TEXTOS -> androidx.appcompat.R.attr.colorPrimary
                DefaultCatalogs.Source.GUTENBERG -> com.google.android.material.R.attr.colorTertiary
                null -> com.google.android.material.R.attr.colorSecondary
            }
            ViewCompat.setBackgroundTintList(
                binding.catalogCardIcon,
                ColorStateList.valueOf(MaterialColors.getColor(binding.root, accent))
            )

            binding.root.setOnClickListener {
                val bundle = bundleOf(CATALOGFEED to catalog)
                Navigation.findNavController(it)
                    .navigate(R.id.action_navigation_catalog_list_to_navigation_catalog, bundle)
            }
            binding.root.setOnLongClickListener {
                onLongClick(catalog)
                true
            }
        }
    }

    companion object {
        const val CATALOGFEED = "catalogFeed"
    }

    private class CatalogListDiff : DiffUtil.ItemCallback<Catalog>() {

        override fun areItemsTheSame(
            oldItem: Catalog,
            newItem: Catalog,
        ): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(
            oldItem: Catalog,
            newItem: Catalog,
        ): Boolean {
            return oldItem.title == newItem.title &&
                oldItem.href == newItem.href &&
                oldItem.type == newItem.type
        }
    }
}
