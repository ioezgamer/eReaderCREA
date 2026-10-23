package org.crea.reader.community

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.crea.reader.Application
import org.crea.reader.R
import org.crea.reader.bookdetail.BookDetailDialog
import org.crea.reader.databinding.FragmentCommunityBinding
import org.crea.reader.online.CatalogRow
import org.crea.reader.online.OnlineBookManager
import org.crea.reader.online.OnlineCatalogViewModel
import org.crea.reader.reader.ReaderActivityContract
import org.crea.reader.sync.SyncScheduler
import org.crea.reader.sync.SyncUi
import org.crea.reader.utils.viewLifecycle

/**
 * "Comunidad": what the other people who share their reading are reading, what is read the most,
 * and what to read next. Nothing is shown (or published) until the user opts in.
 */
class CommunityFragment : Fragment() {

    private val viewModel: OnlineCatalogViewModel by activityViewModels()
    private var binding: FragmentCommunityBinding by viewLifecycle()
    private lateinit var adapter: CommunityAdapter

    private val app: Application
        get() = requireContext().applicationContext as Application

    /** Suggested the first time, so joining takes one tap; the person can type their own. */
    private var suggestedNickname = ""

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        binding = FragmentCommunityBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        suggestedNickname = app.systemDeviceName().take(MAX_NICKNAME)
        viewModel.channel.receive(viewLifecycleOwner) { handleEvent(it) }
        adapter = CommunityAdapter(onOpen = ::showBookSheet)
        binding.communityList.layoutManager = LinearLayoutManager(requireContext())
        binding.communityList.adapter = adapter
        binding.communityList.itemAnimator = null

        val settings = app.communityStore.settings.value
        binding.communityNickname.setText(settings.nickname.ifBlank { suggestedNickname })
        binding.communityNickname.doAfterTextChanged { renderCard(app.communityStore.settings.value) }
        binding.communityPrimary.setOnClickListener { onPrimary() }
        binding.communitySecondary.setOnClickListener { confirmStopSharing() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    combine(
                        app.communityStore.settings,
                        app.communityStore.members,
                        viewModel.rowsById,
                    ) { current, members, rows -> Triple(current, members, rows) }
                        .collect { (current, members, rows) ->
                            renderCard(current)
                            renderFeed(current, CommunityRules.build(members, rows), rows.isEmpty())
                        }
                }
                launch { app.onlineBooks.readyToRead.collect { event -> event?.let(::openWhenReady) } }
            }
        }
    }

    private fun renderCard(settings: org.crea.reader.sync.CommunityStore.Settings) {
        val typed = binding.communityNickname.text?.toString().orEmpty().trim()
        val valid = typed.length in MIN_NICKNAME..MAX_NICKNAME
        if (settings.enabled) {
            binding.communityCardTitle.text = getString(R.string.community_on_title, settings.nickname)
            binding.communityCardDetail.setText(R.string.community_on_detail)
            binding.communityPrimary.setText(R.string.community_save_nickname)
            binding.communityPrimary.isEnabled = valid && typed != settings.nickname
            binding.communitySecondary.isVisible = true
            binding.communitySecondary.setText(R.string.community_stop)
        } else {
            binding.communityCardTitle.setText(R.string.community_off_title)
            binding.communityCardDetail.setText(R.string.community_off_detail)
            binding.communityPrimary.setText(R.string.community_start)
            binding.communityPrimary.isEnabled = valid
            binding.communitySecondary.isVisible = false
        }
    }

    private fun renderFeed(settings: org.crea.reader.sync.CommunityStore.Settings, feed: CommunityFeed, catalogEmpty: Boolean) {
        val items = buildList {
            if (feed.recommended.isNotEmpty()) {
                add(CommunityItem.Header(R.string.community_recommended, R.string.community_recommended_detail))
                feed.recommended.forEach { add(CommunityItem.Entry("rec-${it.row.book.driveFileId}", it.row, interestLine(it, withSimilar = true))) }
            }
            if (feed.readingNow.isNotEmpty()) {
                add(CommunityItem.Header(R.string.community_reading_now, R.string.community_reading_now_detail))
                feed.readingNow.forEach {
                    add(
                        CommunityItem.Entry(
                            "now-${it.nickname}-${it.row.book.driveFileId}",
                            it.row,
                            getString(R.string.community_reader_line, it.nickname, SyncUi.relativeTime(it.updatedAt)),
                            it.percent
                        )
                    )
                }
            }
            if (feed.popular.isNotEmpty()) {
                add(CommunityItem.Header(R.string.community_popular, R.string.community_popular_detail))
                feed.popular.forEach { add(CommunityItem.Entry("pop-${it.row.book.driveFileId}", it.row, interestLine(it, withSimilar = false))) }
            }
        }
        adapter.submitList(if (settings.enabled) items else emptyList())

        val message = when {
            !settings.enabled -> R.string.community_empty_off
            catalogEmpty -> R.string.community_empty_catalog
            feed.isEmpty -> R.string.community_empty_nobody
            else -> null
        }
        binding.communityEmpty.isVisible = message != null
        message?.let { binding.communityEmpty.setText(it) }
        binding.communityList.isVisible = message == null
    }

    private fun interestLine(item: BookInterest, withSimilar: Boolean): String {
        val resources = requireContext().resources
        val parts = listOfNotNull(
            if (withSimilar && item.similarReaders) getString(R.string.community_similar) else null,
            resources.getQuantityString(R.plurals.community_readers, item.readers, item.readers),
            if (item.finishers > 0) resources.getQuantityString(R.plurals.community_finished, item.finishers, item.finishers) else null,
        )
        return parts.joinToString(" · ")
    }

    private fun onPrimary() {
        val typed = binding.communityNickname.text?.toString().orEmpty().trim()
        if (typed.length !in MIN_NICKNAME..MAX_NICKNAME) return
        val store = app.communityStore
        if (store.enabled) {
            store.changeNickname(typed)
        } else {
            if (!app.syncStateStore.isEnabled) {
                Snackbar.make(binding.root, R.string.community_needs_drive, Snackbar.LENGTH_LONG)
                    .setAction(R.string.library_connect_action) { findNavController().navigate(R.id.sync) }
                    .show()
                return
            }
            store.enable(typed)
        }
        // Publish the nickname and read the others right away.
        SyncScheduler.runNow(requireContext())
    }

    private fun confirmStopSharing() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.community_stop_title)
            .setMessage(R.string.community_stop_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.community_stop) { _, _ ->
                app.communityStore.disable()
                // Empties the shared file so nobody keeps seeing this person's reading.
                SyncScheduler.runNow(requireContext())
            }
            .show()
    }

    private fun showBookSheet(row: CatalogRow) {
        BookDetailDialog.show(childFragmentManager, driveFileId = row.book.driveFileId)
    }

    private fun openWhenReady(event: OnlineBookManager.Event.ReadyToRead) {
        app.onlineBooks.consumeReady(event)
        if (System.currentTimeMillis() - event.at <= READY_MAX_AGE_MS) viewModel.openPublication(event.readerId)
    }

    private fun handleEvent(event: OnlineCatalogViewModel.Event) {
        when (event) {
            is OnlineCatalogViewModel.Event.OpenPublicationError -> event.error.toUserError().show(requireActivity())
            is OnlineCatalogViewModel.Event.LaunchReader ->
                startActivity(ReaderActivityContract().createIntent(requireContext(), event.arguments))
        }
    }

    private companion object {
        const val MIN_NICKNAME = 2
        const val MAX_NICKNAME = 24
        const val READY_MAX_AGE_MS = 2 * 60 * 1000L
    }
}
