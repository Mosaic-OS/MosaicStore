/*
 * Copyright (C) 2026 The MosaicOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package app.grapheneos.apps.models

import android.content.ClipData
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Bundle
import android.os.StatFs
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.grapheneos.apps.R
import app.grapheneos.apps.databinding.OfflineModelItemBinding
import app.grapheneos.apps.databinding.OfflineModelsScreenBinding
import app.grapheneos.apps.ui.ViewBindingFragment
import app.mosaicos.models.IModelStore
import app.mosaicos.models.ModelCatalog
import app.mosaicos.models.ModelSpec
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class OfflineModelsFragment : ViewBindingFragment<OfflineModelsScreenBinding>() {

    private class Row(val entry: ModelSpec, val views: OfflineModelItemBinding) {
        var partial = false
        val bundled = File(BUNDLED_DIR, entry.file).isFile
    }

    private class Snapshot(val state: Int, val allocated: Long, val bytes: Long, val partial: Boolean)

    private val repository by lazy { ModelRepository.get(requireContext()) }
    private val rows = mutableListOf<Row>()
    private var pendingImport: String? = null
    private var selecting = false

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val id = pendingImport
        pendingImport = null
        if (uri != null && id != null) startImport(id, uri)
    }

    override fun inflate(inflater: LayoutInflater, container: ViewGroup?, attach: Boolean) =
        OfflineModelsScreenBinding.inflate(inflater, container, attach)

    override fun onViewsCreated(views: OfflineModelsScreenBinding, savedInstanceState: Bundle?) {
        rows.clear()
        selecting = false
        val catalog = repository.catalog
        if (catalog == null) {
            views.modelsScroll.visibility = View.GONE
            views.modelsPlaceholder.visibility = View.VISIBLE
            return
        }
        val inflater = LayoutInflater.from(views.root.context)
        catalog.models.forEach { entry ->
            val item = OfflineModelItemBinding.inflate(inflater, views.modelsContainer, true)
            rows += Row(entry, item)
            bindStatic(rows.last(), entry.id == catalog.recommendedChat)
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    refresh()
                    delay(POLL_INTERVAL_MS)
                }
            }
        }
    }

    private fun size(bytes: Long) = Formatter.formatFileSize(requireContext(), bytes)

    private fun bindStatic(row: Row, recommended: Boolean) {
        val entry = row.entry
        val item = row.views
        val name = when (entry.id) {
            "chat-2b" -> R.string.models_chat_2b
            "chat-4b" -> R.string.models_chat_4b
            else -> R.string.models_speech
        }
        item.modelName.text = getString(name) +
            if (recommended) " " + getString(R.string.models_recommended) else ""
        item.modelDetail.text = getString(R.string.models_expected, entry.file, size(entry.size), entry.size)
        val note = when {
            isOversized(entry) -> R.string.models_larger_not_rated
            entry.id == "chat-2b" && repository.catalog?.recommendedChat == "chat-4b" ->
                R.string.models_smaller_capable
            else -> null
        }
        item.modelNote.visibility = if (note == null) View.GONE else View.VISIBLE
        note?.let { item.modelNote.setText(it) }
        item.modelDownload.setOnClickListener {
            if (repository.busy.get()) return@setOnClickListener
            if (row.partial) confirmResume(entry)
            else confirmModelChoice(entry) { confirmDownload(entry) }
        }
        item.modelPause.setOnClickListener { command(entry.id, "pause") }
        item.modelCancel.setOnClickListener { command(entry.id, "cancel") }
        item.modelImport.setOnClickListener {
            confirmModelChoice(entry) {
                pendingImport = entry.id
                runCatching { picker.launch(arrayOf("*/*")) }
                    .onFailure { pendingImport = null; showOutcome(R.string.models_unavailable) }
            }
        }
        item.modelImportBundled.setOnClickListener {
            confirmModelChoice(entry) {
                startService(Intent(requireContext(), ModelImportService::class.java)
                    .putExtra("modelId", entry.id).putExtra("bundled", true))
            }
        }
        item.modelDelete.setOnClickListener { confirmDelete(entry) }
        item.modelChoice.contentDescription = getString(R.string.models_chat_choice_description, getString(name))
        item.modelChoice.setOnClickListener {
            if (selecting || repository.busy.get()) return@setOnClickListener
            repository.lastOutcome.set(null)
            selecting = true
            rows.forEach { it.views.modelChoice.isEnabled = false }
            viewLifecycleOwner.lifecycleScope.launch {
                val success = withContext(Dispatchers.IO) {
                    runCatching {
                        check(repository.state(entry.id) == IModelStore.READY)
                        repository.selectChatModel(entry.id)
                    }.isSuccess
                }
                selecting = false
                refresh()
                if (!success) showOutcome(R.string.models_selection_failed)
            }
        }
    }

    private suspend fun refresh() {
        val data = withContext(Dispatchers.IO) {
            val snapshots = rows.map { row ->
                val id = row.entry.id
                Snapshot(repository.state(id), repository.usedBytes(id), repository.partialBytes(id),
                    repository.hasPartial(id))
            }
            val free = StatFs(requireContext().noBackupFilesDir.absolutePath).availableBytes
            Triple(snapshots, free, Triple(repository.busy.get(), repository.activeId, repository.selectedChatModelId()))
        }
        val views = views()
        views.modelsFree.text = getString(R.string.models_free, size(data.second))
        val outcome = repository.lastOutcome.get()
        val transient = outcome == R.string.models_deleted ||
            outcome == R.string.models_import_done || outcome == R.string.models_cancelled
        views.modelsOutcome.visibility = if (outcome == null || transient) View.GONE else View.VISIBLE
        if (transient) {
            if (repository.lastOutcome.compareAndSet(outcome, null)) {
                Snackbar.make(views.root, checkNotNull(outcome), Snackbar.LENGTH_SHORT).show()
            }
        } else {
            outcome?.let { views.modelsOutcome.setText(it) }
        }
        val (busy, active, selected) = data.third
        val readyChats = rows.zip(data.first).count { (row, snapshot) ->
            row.entry.kind == "chat" && snapshot.state == IModelStore.READY
        }
        rows.zip(data.first).forEach { (row, snapshot) ->
            bindState(row, snapshot, busy, active)
            val chosen = row.entry.id == selected && snapshot.state == IModelStore.READY
            row.views.modelSelected.visibility = if (chosen) View.VISIBLE else View.GONE
            row.views.modelChoice.visibility = if (readyChats > 1 && row.entry.kind == "chat" &&
                snapshot.state == IModelStore.READY) View.VISIBLE else View.GONE
            row.views.modelChoice.isChecked = chosen
            row.views.modelChoice.isEnabled = !busy && !selecting
        }
    }

    private fun bindState(row: Row, snapshot: Snapshot, busy: Boolean, active: String?) {
        val item = row.views
        row.partial = snapshot.partial
        val state = snapshot.state
        val running = active == row.entry.id
        val usable = state != IModelStore.INCOMPATIBLE && state != IModelStore.UNAVAILABLE
        val paused = snapshot.partial && !running &&
            (state == IModelStore.ABSENT || state == IModelStore.DOWNLOADING || state == IModelStore.READY)

        val label = getString(if (paused) R.string.models_paused else stateLabel(state))
        val hasProgress = snapshot.bytes > 0 && state != IModelStore.REMOVING ||
            running && (state == IModelStore.DOWNLOADING || state == IModelStore.IMPORTING)
        item.modelStatus.text = if (hasProgress)
            ModelProgressText.format(requireContext(), label, snapshot.bytes, row.entry.size)
        else label
        item.modelProgress.isIndeterminate = running &&
            (state == IModelStore.VERIFYING || state == IModelStore.REMOVING)
        if (!item.modelProgress.isIndeterminate) {
            item.modelProgress.progress = if (state == IModelStore.READY && !snapshot.partial) 1000
                else (snapshot.bytes * 1000 / row.entry.size).toInt()
        }
        val offerDownload = usable && !busy && !running && (state != IModelStore.READY || snapshot.partial)
        item.modelDownload.visibility = if (offerDownload) View.VISIBLE else View.GONE
        item.modelDownload.setText(if (snapshot.partial) R.string.models_resume else R.string.models_download)
        item.modelPause.visibility =
            if (running && state == IModelStore.DOWNLOADING) View.VISIBLE else View.GONE
        val offerCancel = running && state != IModelStore.REMOVING || !busy && snapshot.partial
        item.modelCancel.visibility = if (offerCancel) View.VISIBLE else View.GONE
        val offerImport = usable && !busy && !running
        item.modelImport.visibility = if (offerImport) View.VISIBLE else View.GONE
        item.modelImportBundled.visibility = if (offerImport && row.bundled) View.VISIBLE else View.GONE
        val offerDelete = (!busy || running) && state != IModelStore.REMOVING &&
            (snapshot.allocated > 0 || state == IModelStore.CORRUPT || running)
        item.modelDelete.visibility = if (offerDelete) View.VISIBLE else View.GONE
        item.modelActions.visibility = visibleIfAny(item.modelDownload, item.modelPause, item.modelCancel)
        item.modelSecondaryActions.visibility =
            visibleIfAny(item.modelImport, item.modelImportBundled, item.modelDelete)
    }

    private fun visibleIfAny(vararg children: View) =
        if (children.any { it.visibility == View.VISIBLE }) View.VISIBLE else View.GONE

    private fun isOversized(entry: ModelSpec) =
        entry.id == "chat-4b" && repository.catalog?.recommendedChat == "chat-2b"

    private fun confirmModelChoice(entry: ModelSpec, proceed: () -> Unit) {
        if (repository.busy.get()) return
        if (!isOversized(entry)) {
            proceed()
            return
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.models_oversized_title)
            .setMessage(R.string.models_oversized_warning)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.models_continue) { _, _ ->
                if (!repository.busy.get()) proceed()
            }.show()
    }

    private fun confirmDownload(entry: ModelSpec) {
        if (repository.busy.get()) return
        val connectivity = requireContext().getSystemService(ConnectivityManager::class.java) ?: return
        val metered = connectivity.isActiveNetworkMetered
        val hosts = (setOf(ModelCatalog.sourceUri(entry.sourceUrl).host) + entry.redirectHosts)
            .joinToString(", ")
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.models_download)
            .setMessage(getString(R.string.models_download_consent, entry.file, entry.size, hosts) +
                if (metered) "\n\n" + getString(R.string.models_metered) else "")
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(if (metered) R.string.models_allow_metered else R.string.models_download) { _, _ ->
                if (!repository.busy.get()) command(entry.id, "download", metered)
            }.show()
    }

    private fun confirmResume(entry: ModelSpec) {
        if (repository.busy.get()) return
        val connectivity = requireContext().getSystemService(ConnectivityManager::class.java) ?: return
        if (!connectivity.isActiveNetworkMetered) {
            command(entry.id, "download")
            return
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.models_resume)
            .setMessage(R.string.models_metered)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.models_allow_metered) { _, _ ->
                if (!repository.busy.get()) command(entry.id, "download", allowMetered = true)
            }.show()
    }

    private fun confirmDelete(entry: ModelSpec) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.models_delete)
            .setMessage(R.string.models_delete_consent)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.models_delete) { _, _ -> command(entry.id, "delete") }
            .show()
    }

    private fun startImport(id: String, uri: Uri) {
        if (uri.scheme != "content" || repository.busy.get()) return
        val intent = Intent(requireContext(), ModelImportService::class.java).setData(uri)
            .putExtra("modelId", id).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        intent.clipData = ClipData.newRawUri("Model", uri)
        startService(intent)
    }

    private fun command(id: String, operation: String, allowMetered: Boolean = false) {
        startService(Intent(requireContext(), ModelImportService::class.java)
            .putExtra("modelId", id).putExtra("operation", operation)
            .putExtra("allowMetered", allowMetered))
    }

    private fun startService(intent: Intent) {
        runCatching { requireContext().startForegroundService(intent) }
            .onFailure { showOutcome(R.string.models_unavailable) }
    }

    private fun showOutcome(message: Int) {
        repository.lastOutcome.set(message)
        views().modelsOutcome.visibility = View.VISIBLE
        views().modelsOutcome.setText(message)
    }

    private fun stateLabel(state: Int): Int = when (state) {
        IModelStore.ABSENT -> R.string.models_absent
        IModelStore.DOWNLOADING -> R.string.models_downloading
        IModelStore.VERIFYING -> R.string.models_verifying
        IModelStore.INCOMPATIBLE -> R.string.models_incompatible
        IModelStore.CORRUPT -> R.string.models_corrupt
        IModelStore.READY -> R.string.models_ready
        IModelStore.IMPORTING -> R.string.models_importing
        IModelStore.REMOVING -> R.string.models_removing
        else -> R.string.models_unavailable
    }

    private companion object {
        const val POLL_INTERVAL_MS = 1000L
        const val BUNDLED_DIR = "/product/etc/mosaic/bundled-models"
    }
}
