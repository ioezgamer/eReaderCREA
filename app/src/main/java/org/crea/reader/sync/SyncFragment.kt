package org.crea.reader.sync

import android.app.Activity
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.crea.reader.Application
import org.crea.reader.R
import org.crea.reader.databinding.FragmentSyncBinding

class SyncFragment : Fragment() {
    private var _binding: FragmentSyncBinding? = null
    private val binding get() = requireNotNull(_binding)
    private val app get() = requireContext().applicationContext as Application

    private var authorizing = false

    /** The kind of account being connected: a CREA one, or a personal one with its own folder. */
    private var connectingPersonal = false

    private val consentLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            runCatching { authorizationFor(connectingPersonal).tokenFromIntent(requireNotNull(result.data)) }
                .onSuccess { token -> viewLifecycleOwner.lifecycleScope.launch { confirmAccount(token) } }
                .onFailure { showError(it.message ?: getString(R.string.sync_authorization_failed)) }
        } else {
            showError(getString(R.string.sync_authorization_cancelled))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        connectingPersonal = savedInstanceState?.getBoolean(STATE_PERSONAL)
            ?: app.syncStateStore.isPersonal
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_PERSONAL, connectingPersonal)
    }

    private fun authorizationFor(personal: Boolean) = DriveAuthorizationProvider(requireContext(), personal)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentSyncBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.syncPrimaryButton.setOnClickListener {
            // Connected: sync the same account again. Not connected: the CREA account.
            val store = app.syncStateStore
            requestAuthorizationAndSync(personal = store.isEnabled && store.isPersonal)
        }
        binding.syncPersonalButton.setOnClickListener { requestAuthorizationAndSync(personal = true) }
        binding.syncDisableButton.setOnClickListener {
            app.syncStateStore.setEnabled(false)
            Snackbar.make(binding.root, R.string.sync_disconnected, Snackbar.LENGTH_LONG).show()
        }
        binding.syncOpenFolderButton.setOnClickListener {
            val opened = SyncUi.openDriveFolder(requireContext(), app.syncStateStore.snapshot().folderId)
            if (!opened) Snackbar.make(binding.root, R.string.sync_open_folder_failed, Snackbar.LENGTH_LONG).show()
        }
        bindStep(binding.syncStep1.syncStepNumber, binding.syncStep1.syncStepText, 1, R.string.sync_what_detail)
        bindStep(binding.syncStep2.syncStepNumber, binding.syncStep2.syncStepText, 2, R.string.sync_how_2)
        bindStep(binding.syncStep3.syncStepNumber, binding.syncStep3.syncStepText, 3, R.string.sync_how_3)
        bindStep(binding.syncStep4.syncStepNumber, binding.syncStep4.syncStepText, 4, R.string.sync_how_4)

        render(app.syncStateStore.snapshot(), app.syncEngine.progress.value)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(app.syncStateStore.state, app.syncEngine.progress) { state, progress ->
                    state to progress
                }.collect { (state, progress) -> render(state, progress) }
            }
        }
    }

    private fun bindStep(number: android.widget.TextView, text: android.widget.TextView, index: Int, textRes: Int) {
        number.text = index.toString()
        text.setText(textRes)
    }

    private fun requestAuthorizationAndSync(personal: Boolean) {
        if (authorizing) return
        authorizing = true
        connectingPersonal = personal
        setBusy(true)
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching { authorizationFor(personal).requestToken() }
                .onSuccess { result ->
                    when (result) {
                        is DriveAuthorizationProvider.TokenResult.Authorized -> confirmAccount(result.accessToken)
                        is DriveAuthorizationProvider.TokenResult.ConsentRequired -> {
                            setBusy(false)
                            consentLauncher.launch(
                                IntentSenderRequest.Builder(result.pendingIntent.intentSender).build()
                            )
                        }
                    }
                }
                .onFailure {
                    showError(it.message ?: getString(R.string.sync_authorization_failed))
                }
            authorizing = false
        }
    }

    /**
     * Checks that the account Google returned matches the button used: a CREA account always gets the
     * shared library, and an outside account cannot ask for access to the whole Drive.
     */
    private suspend fun confirmAccount(token: String) {
        val email = runCatching { GoogleDriveRestClient(token).currentUser()?.emailAddress }.getOrNull()
        if (_binding == null) return
        when {
            email == null -> showError(getString(R.string.sync_authorization_failed))
            DriveAuthorizationProvider.isCreaAccount(email) == connectingPersonal -> showError(
                getString(if (connectingPersonal) R.string.sync_wrong_account_crea else R.string.sync_wrong_account_personal, email)
            )
            else -> {
                app.syncStateStore.connect(email, connectingPersonal)
                startSync()
            }
        }
    }

    /** The worker owns the actual sync, so it keeps going when this screen is closed. */
    private fun startSync() {
        SyncScheduler.runNow(requireContext())
        setBusy(false)
    }

    private fun render(state: SyncStateStore.Snapshot, progress: LibrarySyncEngine.Progress?) {
        val running = progress != null
        binding.syncStatusSpinner.isVisible = running
        binding.syncStatusIcon.alpha = if (running) 0.0f else 1.0f
        binding.syncProgress.isVisible = running
        binding.syncPrimaryButton.isEnabled = !running && !authorizing
        binding.syncDisableButton.isVisible = state.enabled && !running
        binding.syncStatusExtra.isVisible = false

        binding.syncAccountRow.isVisible = state.enabled && state.account != null
        binding.syncAccountText.text = state.account.orEmpty()
        binding.syncOpenFolderButton.isVisible = state.enabled
        binding.syncFolderDetail.setText(
            when {
                !state.enabled -> R.string.sync_connect_first
                state.personal -> R.string.sync_folder_detail_personal
                else -> R.string.sync_folder_detail
            }
        )
        binding.syncPersonalButton.isVisible = !state.enabled && !running
        binding.syncPersonalButton.isEnabled = !authorizing
        binding.syncPersonalHint.isVisible = !state.enabled && !running
        binding.syncStep1.syncStepText.setText(
            if (state.enabled && state.personal) R.string.sync_what_detail_personal else R.string.sync_what_detail
        )

        when {
            progress != null -> {
                binding.syncStatusTitle.setText(R.string.sync_status_running)
                binding.syncStatusDetail.text = buildString {
                    append(SyncUi.stageText(requireContext(), progress))
                    if (!progress.title.isNullOrBlank()) append("\n").append(progress.title)
                }
                if (progress.total > 0) {
                    binding.syncProgress.isIndeterminate = false
                    binding.syncProgress.max = progress.total
                    binding.syncProgress.setProgressCompat(progress.current, true)
                } else {
                    binding.syncProgress.isIndeterminate = true
                }
            }
            !state.enabled -> {
                binding.syncStatusIcon.setImageResource(R.drawable.ic_cloud_off)
                binding.syncStatusTitle.setText(R.string.sync_status_off)
                binding.syncStatusDetail.setText(R.string.sync_off_detail)
                binding.syncPrimaryButton.setText(R.string.sync_connect_crea)
            }
            state.status == SyncStateStore.STATUS_ERROR -> {
                binding.syncStatusIcon.setImageResource(R.drawable.ic_error)
                binding.syncStatusTitle.setText(R.string.sync_status_attention)
                binding.syncStatusDetail.text = state.error.orEmpty()
                binding.syncPrimaryButton.setText(R.string.sync_now)
            }
            else -> {
                binding.syncStatusIcon.setImageResource(R.drawable.ic_cloud_done)
                binding.syncStatusTitle.setText(R.string.library_sync_on_title)
                binding.syncPrimaryButton.setText(R.string.sync_now)
                if (state.lastSync > 0) {
                    binding.syncStatusDetail.text = getString(
                        R.string.library_sync_ok_detail,
                        SyncUi.relativeTime(state.lastSync),
                        state.bookCount
                    )
                    val summary = state.lastSummary
                    val catalogChanged = summary.added + summary.updated + summary.removed > 0
                    binding.syncStatusExtra.text = listOfNotNull(
                        when {
                            catalogChanged -> getString(
                                R.string.sync_summary_changes,
                                summary.added,
                                summary.updated,
                                summary.removed
                            )
                            !summary.hasChanges -> getString(R.string.sync_summary_uptodate)
                            else -> null
                        },
                        summary.uploaded.takeIf { it > 0 }
                            ?.let { getString(R.string.sync_summary_uploaded, it) },
                        summary.alreadyInDrive.takeIf { it > 0 }
                            ?.let { getString(R.string.sync_summary_already_in_drive, it) }
                    ).joinToString("\n")
                    binding.syncStatusExtra.isVisible = true
                } else {
                    binding.syncStatusDetail.setText(R.string.sync_waiting_first_run)
                }
            }
        }
    }

    private fun setBusy(busy: Boolean) {
        binding.syncPrimaryButton.isEnabled = !busy
        binding.syncPersonalButton.isEnabled = !busy
        binding.syncProgress.isVisible = busy
        if (busy) binding.syncProgress.isIndeterminate = true
    }

    private fun showError(message: String) {
        authorizing = false
        app.syncStateStore.markError(message)
        setBusy(false)
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private companion object {
        const val STATE_PERSONAL = "connecting_personal"
    }
}
