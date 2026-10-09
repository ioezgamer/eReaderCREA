/*
 * Copyright 2021 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

package org.crea.reader.catalogs

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.squareup.picasso.Picasso
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.opds.images
import org.crea.reader.MainActivity
import org.crea.reader.databinding.FragmentPublicationDetailBinding

class PublicationDetailFragment : Fragment() {

    private var publication: Publication? = null
    private val catalogViewModel: CatalogViewModel by activityViewModels()

    private var _binding: FragmentPublicationDetailBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentPublicationDetailBinding.inflate(
            inflater,
            container,
            false
        )
        publication = catalogViewModel.publication
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        (activity as MainActivity).supportActionBar?.title = publication?.metadata?.title

        publication?.images?.firstOrNull()
            ?.let { Picasso.get().load(it.href.toString()) }
            ?.into(binding.catalogDetailCoverImage)

        val description = publication?.metadata?.description
        binding.catalogDetailDescriptionText.text = description
        binding.catalogDetailDescriptionText.visibility =
            if (description.isNullOrBlank()) android.view.View.GONE else android.view.View.VISIBLE
        binding.catalogDetailTitleText.text = publication?.metadata?.title

        binding.catalogDetailDownloadButton.setOnClickListener {
            publication?.let { it1 ->
                catalogViewModel.downloadPublication(
                    it1
                )
            }
        }
    }
}

