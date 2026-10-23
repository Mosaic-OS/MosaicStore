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

import android.animation.ValueAnimator
import android.content.ClipData
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Uri
import android.graphics.Rect
import android.util.TypedValue
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.StatFs
import android.text.format.Formatter
import android.transition.ChangeBounds
import android.transition.Fade
import android.transition.TransitionManager
import android.transition.TransitionSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import android.view.animation.PathInterpolator
import android.widget.Button
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
import app.mosaicos.models.SpeechModels
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.motion.MotionUtils
import com.google.android.material.snackbar.Snackbar
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class OfflineModelsFragment : ViewBindingFragment<OfflineModelsScreenBinding>() {

    private class Row(val entry: ModelSpec, val views: OfflineModelItemBinding) {
        var partial = false
        val bundled = !entry.downloadOnly && File(BUNDLED_DIR, entry.file).isFile
    }

    private data class Snapshot(val state: Int, val allocated: Long, val bytes: Long, val partial: Boolean)
    private data class Selections(
        val busy: Boolean, val active: String?, val chat: String?, val speech: Map<String, String?>,
    )

    private data class Rendered(
        val rows: List<Snapshot>, val free: String, val selections: Selections, val outcome: Int?,
        val failures: Set<String>, val selecting: Boolean, val waiting: Boolean, val linkHandled: Boolean,
    )

    private var rendered: Rendered? = null
    private val repository by lazy { ModelRepository.get(requireContext()) }
    private val rows = mutableListOf<Row>()
    private var pendingImport: String? = null
    private var selecting = false
    private var linkHandled = false
    private var waitingForResult = false
    private var recommended = emptyList<ModelSpec>()
    private var recommendedAvailable = false
    private var englishExpanded = false
    private var voiceExpanded = false
    private val language: String get() = arguments?.getString(RecommendedModels.EXTRA_LANGUAGE)
        ?.takeIf { it == "en" || it == "hu" }
        ?: if (resources.configuration.locales[0].language == "hu") "hu" else "en"
    private var linkedChoice: String? = null

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val id = pendingImport
        pendingImport = null
        if (uri != null && id != null) startImport(id, uri)
    }

    override fun inflate(inflater: LayoutInflater, container: ViewGroup?, attach: Boolean) =
        OfflineModelsScreenBinding.inflate(inflater, container, attach)

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("englishExpanded", englishExpanded)
        outState.putBoolean("voiceExpanded", voiceExpanded)
        outState.putBoolean("linkHandled", linkHandled)
        outState.putBoolean("waitingForResult", waitingForResult)
    }

    override fun onViewsCreated(views: OfflineModelsScreenBinding, savedInstanceState: Bundle?) {
        linkHandled = savedInstanceState?.getBoolean("linkHandled") ?: linkHandled
        waitingForResult = savedInstanceState?.getBoolean("waitingForResult") ?: waitingForResult
        englishExpanded = savedInstanceState?.getBoolean("englishExpanded") ?: true
        voiceExpanded = savedInstanceState?.getBoolean("voiceExpanded")
            ?: (linkedChoice == ModelCatalog.HUNGARIAN_VOICE)
        setupCategory(views.modelsTtsHuHeader, { voiceExpanded }) { voiceExpanded = it }
        setupCategory(views.modelsEnHeader, { englishExpanded }) { englishExpanded = it }
        bindCategories()
        views.modelsBundle.setOnClickListener { confirmRecommended() }
        rows.clear()
        selecting = false
        rendered = null
        viewLifecycleOwner.lifecycleScope.launch {
            val catalog = withContext(Dispatchers.IO) { repository.catalog }
            if (catalog == null) {
                views.modelsScroll.visibility = View.GONE
                views.modelsPlaceholder.visibility = View.VISIBLE
                return@launch
            }
            linkedChoice = RecommendedModels.choice(
                arguments?.getString(RecommendedModels.EXTRA_CHOICE), language, catalog.models.map { it.id }.toSet())
            if (savedInstanceState == null && linkedChoice == ModelCatalog.HUNGARIAN_VOICE) voiceExpanded = true
            bindCategories()
            val inflater = LayoutInflater.from(views.root.context)
            val recommendedIds = RecommendedModels.ids(catalog.recommendedChat, language)
            recommended = recommendedIds.mapNotNull { id -> catalog.models.firstOrNull { it.id == id } }
            recommendedAvailable = recommended.size == recommendedIds.size
            views.modelsTtsHuUnavailable.visibility =
                if (catalog.models.none { it.id == ModelCatalog.HUNGARIAN_VOICE }) View.VISIBLE else View.GONE
            val others = catalog.models.filter { it.id !in recommendedIds }
            (recommended + others).forEach { entry ->
                val isRecommended = entry.id in recommendedIds
                val container = when {
                    entry.kind == "voice" -> views.modelsTtsHuContainer
                    SpeechModels.language(entry.id) == "en" -> views.modelsEnContainer
                    isRecommended -> views.modelsRecommendedContainer
                    else -> views.modelsOtherContainer
                }
                val item = OfflineModelItemBinding.inflate(inflater, container, true)
                rows += Row(entry, item)
                bindStatic(rows.last(), isRecommended)
            }
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                repository.events.collectLatest {
                    do {
                        refresh()
                        if (!ModelsPolling.active(repository.busy.get(), repository.bundleActive)) break
                        delay(POLL_INTERVAL_MS)
                    } while (true)
                }
            }
        }
    }

    private fun setupCategory(header: View, expanded: () -> Boolean, setExpanded: (Boolean) -> Unit) {
        header.setOnClickListener {
            setExpanded(!expanded())
            bindCategories(animate = true)
        }
        header.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = Button::class.java.name
                info.addAction(if (expanded())
                    AccessibilityAction.ACTION_COLLAPSE else AccessibilityAction.ACTION_EXPAND)
            }

            override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean {
                if (action == AccessibilityNodeInfo.ACTION_EXPAND && !expanded() ||
                    action == AccessibilityNodeInfo.ACTION_COLLAPSE && expanded()) {
                    setExpanded(action == AccessibilityNodeInfo.ACTION_EXPAND)
                    bindCategories(animate = true)
                    return true
                }
                return super.performAccessibilityAction(host, action, args)
            }
        }
    }

    private fun bindCategories(animate: Boolean = false) {
        val views = views()
        val content = views.modelsContainer
        TransitionManager.endTransitions(content)
        val motion = animate && content.isLaidOut && ValueAnimator.areAnimatorsEnabled()
        val duration = MotionUtils.resolveThemeDuration(requireContext(),
            com.google.android.material.R.attr.motionDurationMedium2, 250).toLong()
        val easing = MotionUtils.resolveThemeInterpolator(requireContext(),
            com.google.android.material.R.attr.motionEasingEmphasizedInterpolator,
            PathInterpolator(0.4f, 0f, 0.2f, 1f))
        if (motion) {
            val transition = TransitionSet().apply {
                ordering = TransitionSet.ORDERING_TOGETHER
                addTransition(ChangeBounds())
                addTransition(Fade())
                this.duration = duration
                interpolator = easing
            }
            TransitionManager.beginDelayedTransition(content, transition)
        }
        fun bind(header: View, body: View, chevron: View, expanded: Boolean) {
            header.stateDescription = getString(if (expanded)
                R.string.models_category_expanded else R.string.models_category_collapsed)
            body.visibility = if (expanded) View.VISIBLE else View.GONE
            val angle = if (expanded) 180f else 0f
            chevron.animate().cancel()
            if (motion) {
                chevron.animate().rotation(angle).setDuration(duration).setInterpolator(easing)
                    .withEndAction { chevron.rotation = angle }.start()
            } else chevron.rotation = angle
        }
        bind(views.modelsEnHeader, views.modelsEnContainer, views.modelsEnChevron, englishExpanded)
        bind(views.modelsTtsHuHeader, views.modelsTtsHuContainer, views.modelsTtsHuChevron, voiceExpanded)
    }

    override fun onDestroyView() {
        TransitionManager.endTransitions(views().modelsContainer)
        views().modelsEnChevron.animate().cancel()
        views().modelsTtsHuChevron.animate().cancel()
        super.onDestroyView()
    }

    private fun size(bytes: Long) = Formatter.formatFileSize(requireContext(), bytes)

    private fun bindStatic(row: Row, recommended: Boolean) {
        val entry = row.entry
        val item = row.views
        item.modelImport.visibility = View.GONE
        item.modelImportBundled.visibility = View.GONE
        val name = when (entry.id) {
            ModelCatalog.HUNGARIAN_VOICE -> R.string.models_voice_anna
            "chat-2b" -> R.string.models_chat_2b
            "chat-4b" -> R.string.models_chat_4b
            "speech-en-small" -> R.string.models_speech_en_small
            else -> R.string.models_speech
        }
        item.modelName.text = getString(name) +
            if (recommended) " " + getString(R.string.models_recommended) else ""
        item.modelSize.text = getString(R.string.models_about_size, size(entry.size))
        item.modelDetail.text = getString(R.string.models_expected, entry.file, entry.size)
        val note = when {
            entry.kind == "voice" -> R.string.models_voice_anna_detail
            entry.kind == "speech" && entry.id.endsWith("-small") -> R.string.models_speech_accurate
            entry.kind == "speech" -> R.string.models_speech_faster
            isOversized(entry) -> R.string.models_larger_not_rated
            entry.id == "chat-2b" && repository.catalog?.recommendedChat == "chat-4b" ->
                R.string.models_smaller_capable
            else -> null
        }
        item.modelNote.visibility = if (note == null) View.GONE else View.VISIBLE
        note?.let { item.modelNote.setText(it) }
        item.modelDownload.setOnClickListener {
            if (repository.busy.get() || repository.bundleActive) return@setOnClickListener
            if (row.partial) continueDownload(entry) else confirmModelChoice(entry) { confirmDownload(entry) }
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
        if (entry.kind == "speech") {
            item.modelChoice.setText(R.string.models_speech_choice)
            item.modelSelected.setText(R.string.models_speech_in_use)
        }
        item.modelChoice.contentDescription = getString(if (entry.kind == "speech")
            R.string.models_speech_choice_description else R.string.models_chat_choice_description, getString(name))
        item.modelChoice.setOnClickListener {
            if (selecting || repository.busy.get()) return@setOnClickListener
            repository.lastOutcome.set(null)
            selecting = true
            rendered = null
            rows.forEach { it.views.modelChoice.isEnabled = false }
            viewLifecycleOwner.lifecycleScope.launch {
                val success = withContext(Dispatchers.IO) {
                    runCatching {
                        check(repository.state(entry.id) == IModelStore.READY)
                        if (entry.kind == "speech") repository.selectSpeechModel(entry.id)
                        else repository.selectChatModel(entry.id)
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
            Triple(snapshots, free, Selections(repository.busy.get() || repository.bundleActive, repository.activeId,
                repository.selectedChatModelId(), mapOf("en" to repository.selectedSpeechModelId("en"),
                    "hu" to repository.selectedSpeechModelId("hu"))))
        }
        val outcome = repository.lastOutcome.get()
        val next = Rendered(data.first, size(data.second), data.third, outcome,
            repository.downloadFailures.toSet(), selecting, waitingForResult, linkHandled)
        if (rendered == next) return
        rendered = next
        val views = views()
        views.modelsFree.text = getString(R.string.models_free, next.free)
        val ready = rows.zip(data.first).filter { it.second.state == IModelStore.READY }
            .map { it.first.entry.id }.toSet()
        val pending = recommended.filter { it.id !in ready }
        views.modelsBundle.text = if (!recommendedAvailable) getString(R.string.models_recommended_unavailable)
            else if (pending.isEmpty()) getString(R.string.models_recommended_ready)
            else getString(R.string.models_download_recommended, size(pending.sumOf { it.size }))
        views.modelsBundle.isEnabled = recommendedAvailable && pending.isNotEmpty() && !data.third.busy && !selecting
        if (waitingForResult && !data.third.busy && outcome != null) {
            waitingForResult = false
            if (outcome == R.string.models_import_done || outcome == R.string.models_cancelled) {
                requireActivity().finish()
                return
            }
        }
        if (!linkHandled && !data.third.busy && linkedChoice != null) {
            linkHandled = true
            focusChoice(ready)
        }
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
        val (busy, active, selected, speech) = data.third
        val readyChats = rows.zip(data.first).count { (row, snapshot) ->
            row.entry.kind == "chat" && snapshot.state == IModelStore.READY
        }
        rows.zip(data.first).forEach { (row, snapshot) ->
            bindState(row, snapshot, busy, active)
            val speechLanguage = SpeechModels.language(row.entry.id)
            val chosen = row.entry.id ==
                (if (row.entry.kind == "speech") speech[speechLanguage] else selected) &&
                snapshot.state == IModelStore.READY
            row.views.modelSelected.visibility = if (chosen) View.VISIBLE else View.GONE
            val readyChoices = if (row.entry.kind == "chat") readyChats else rows.zip(data.first).count {
                (other, state) -> speechLanguage != null && SpeechModels.language(other.entry.id) == speechLanguage &&
                    state.state == IModelStore.READY
            }
            row.views.modelChoice.visibility = if (readyChoices > 1 &&
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

        val failed = row.entry.id in repository.downloadFailures && state != IModelStore.READY
        val label = getString(when {
            failed -> R.string.models_item_failed
            paused -> R.string.models_paused
            else -> stateLabel(state)
        })
        val hasProgress = snapshot.bytes > 0 && state != IModelStore.REMOVING ||
            running && (state == IModelStore.DOWNLOADING || state == IModelStore.IMPORTING)
        item.modelStatus.text = if (hasProgress)
            ModelProgressText.format(requireContext(), label, snapshot.bytes, row.entry.size)
        else label
        item.modelProgress.isIndeterminate = running &&
            (state == IModelStore.VERIFYING || state == IModelStore.REMOVING)
        item.modelProgress.visibility =
            if (hasProgress || item.modelProgress.isIndeterminate) View.VISIBLE else View.GONE
        if (!item.modelProgress.isIndeterminate) {
            item.modelProgress.progress = if (state == IModelStore.READY && !snapshot.partial) 1000
                else (snapshot.bytes * 1000 / row.entry.size).toInt()
        }
        val offerDownload = usable && !busy && !running &&
            (state != IModelStore.READY || snapshot.partial)
        item.modelDownload.visibility = if (offerDownload) View.VISIBLE else View.GONE
        item.modelDownload.setText(when {
            failed -> R.string.models_retry
            snapshot.partial -> R.string.models_resume
            else -> R.string.models_download
        })
        item.modelPause.visibility =
            if (running && state == IModelStore.DOWNLOADING) View.VISIBLE else View.GONE
        val offerCancel = (running && state != IModelStore.REMOVING || !busy && snapshot.partial)
        item.modelCancel.visibility = if (offerCancel) View.VISIBLE else View.GONE
        // A running or partial transfer shows only its own Pause/Continue/Retry and Cancel controls.
        val transfer = running || snapshot.partial
        val offerImport = !busy && !transfer &&
            (state == IModelStore.ABSENT || state == IModelStore.CORRUPT)
        item.modelImport.visibility = if (offerImport) View.VISIBLE else View.GONE
        item.modelImportBundled.visibility = if (offerImport && row.bundled) View.VISIBLE else View.GONE
        val offerDelete = !busy && !transfer && state != IModelStore.REMOVING &&
            (snapshot.allocated > 0 || state == IModelStore.CORRUPT)
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
        if (repository.busy.get() || repository.bundleActive) return
        if (!isOversized(entry)) {
            proceed()
            return
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.models_oversized_title)
            .setMessage(R.string.models_oversized_warning)
            .setNegativeButton(android.R.string.cancel) { _, _ -> returnToTabiti() }
            .setOnCancelListener { returnToTabiti() }
            .setPositiveButton(R.string.models_continue) { _, _ ->
                if (!repository.busy.get() && !repository.bundleActive) proceed()
            }.show()
    }

    private fun confirmDownload(entry: ModelSpec) {
        if (repository.busy.get() || repository.bundleActive) return
        val connectivity = requireContext().getSystemService(ConnectivityManager::class.java) ?: return
        val metered = connectivity.isActiveNetworkMetered
        val hosts = (setOf(ModelCatalog.sourceUri(entry.sourceUrl).host) + entry.redirectHosts)
            .joinToString(", ")
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.models_download)
            .setMessage(getString(R.string.models_download_consent, entry.file, entry.size, hosts) +
                "\n\n" + getString(if (metered) R.string.models_metered else R.string.models_network_cost))
            .setNegativeButton(android.R.string.cancel) { _, _ -> returnToTabiti() }
            .setOnCancelListener { returnToTabiti() }
            .setPositiveButton(if (metered) R.string.models_allow_metered else R.string.models_download) { _, _ ->
                if (!repository.busy.get() && !repository.bundleActive) command(entry.id, "download", metered)
            }.show()
    }

    // A partial download reuses the consent given when it started; only mobile data needs a fresh yes.
    private fun continueDownload(entry: ModelSpec) {
        val metered = requireContext().getSystemService(ConnectivityManager::class.java)
            ?.isActiveNetworkMetered ?: true
        if (!metered) {
            command(entry.id, "download")
            return
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.models_resume)
            .setMessage(getString(R.string.models_expected, entry.file, entry.size) + "\n\n" +
                getString(R.string.models_metered))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.models_allow_metered) { _, _ ->
                if (!repository.busy.get() && !repository.bundleActive) command(entry.id, "download", true)
            }.show()
    }

    private fun returnToTabiti() {
        if (linkedChoice != null) requireActivity().finish()
    }

    private fun focusChoice(ready: Set<String>) {
        val views = views()
        val row = rows.firstOrNull { it.entry.id == linkedChoice }
        val speechLanguage = SpeechModels.language(linkedChoice.orEmpty())
        if (speechLanguage == "en") englishExpanded = true
        if (linkedChoice == ModelCatalog.HUNGARIAN_VOICE) voiceExpanded = true
        bindCategories()
        val target = if (linkedChoice == RecommendedModels.BUNDLE) views.modelsBundle
            else if (linkedChoice == ModelCatalog.HUNGARIAN_VOICE)
                row?.views?.root ?: views.modelsTtsHuHeader
            else row?.views?.root ?: when (speechLanguage) {
                "en" -> views.modelsEnHeader
                else -> null
            }
        if (target == null) return
        val color = TypedValue().also {
            requireContext().theme.resolveAttribute(android.R.attr.colorAccent, it, true)
        }.data
        val width = (2 * resources.displayMetrics.density).toInt().coerceAtLeast(1)
        if (row != null) {
            row.views.root.strokeWidth = width
            row.views.root.strokeColor = color
        } else if (linkedChoice == RecommendedModels.BUNDLE) {
            views.modelsBundle.strokeWidth = width
            views.modelsBundle.strokeColor = ColorStateList.valueOf(color)
        }
        target.post {
            if (view == null) return@post
            val rect = Rect()
            target.getDrawingRect(rect)
            views.modelsScroll.offsetDescendantRectToMyCoords(target, rect)
            views.modelsScroll.smoothScrollTo(0, rect.top)
        }
        if (linkedChoice == RecommendedModels.BUNDLE) confirmRecommended()
        else if (row != null && row.entry.id !in ready) {
            confirmModelChoice(row.entry) { confirmDownload(row.entry) }
        }
    }

    private fun confirmRecommended() {
        if (!recommendedAvailable || repository.busy.get() || repository.bundleActive || selecting) return
        viewLifecycleOwner.lifecycleScope.launch {
            val pending = withContext(Dispatchers.IO) {
                recommended.filter { repository.state(it.id) != IModelStore.READY }
            }
            if (pending.isEmpty() || repository.busy.get() || repository.bundleActive || !isResumed) return@launch
            val metered = requireContext().getSystemService(ConnectivityManager::class.java)
                ?.isActiveNetworkMetered ?: true
            val files = pending.joinToString("\n") { getString(R.string.models_bundle_file, it.file, it.size) }
            val hosts = pending.flatMap {
                setOf(ModelCatalog.sourceUri(it.sourceUrl).host) + it.redirectHosts
            }.distinct().joinToString(", ")
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.models_recommended_title)
                .setMessage(getString(R.string.models_download_consent, files, pending.sumOf { it.size }, hosts) +
                    "\n\n" + getString(if (metered) R.string.models_metered else R.string.models_network_cost))
                .setNegativeButton(android.R.string.cancel) { _, _ -> returnToTabiti() }
                .setOnCancelListener { returnToTabiti() }
                .setPositiveButton(if (metered) R.string.models_allow_metered else R.string.models_download) { _, _ ->
                    if (!repository.busy.get() && !repository.bundleActive) {
                        startService(Intent(requireContext(), ModelImportService::class.java)
                            .putExtra("operation", "recommended")
                            .putStringArrayListExtra("modelIds", ArrayList(pending.map { it.id }))
                            .putExtra(RecommendedModels.EXTRA_LANGUAGE, language)
                            .putExtra("allowMetered", metered))
                    }
                }.show()
        }
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
        repository.lastOutcome.set(null)
        waitingForResult = linkedChoice != null && intent.getStringExtra("operation") in
            setOf("download", "recommended", "cancel")
        runCatching { requireContext().startForegroundService(intent) }
            .onFailure { waitingForResult = false; showOutcome(R.string.models_unavailable) }
    }

    private fun showOutcome(message: Int) {
        repository.lastOutcome.set(message)
        views().modelsOutcome.visibility = View.VISIBLE
        views().modelsOutcome.setText(message)
        repository.changed()
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
