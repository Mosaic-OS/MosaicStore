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

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.SystemClock
import app.grapheneos.apps.R
import app.mosaicos.models.IModelStore
import app.mosaicos.models.ModelCatalog
import java.io.File
import java.util.concurrent.Executors

class ModelImportService : Service() {
    private val worker = Executors.newSingleThreadExecutor()
    private val repository by lazy { ModelRepository.get(this) }
    private val notifications by lazy { getSystemService(NotificationManager::class.java) }
    private val notificationLock = Any()
    private var notification: Notification.Builder? = null
    private var lastNotificationTime = 0L
    private var lastNotificationPhase = -1
    private var lastNotificationBytes = -1L
    private var operationId: String? = null
    private var downloading = false
    private var sequence: DownloadSequence? = null
    private var sequenceMetered = false
    private var sequencePause: Int? = null
    private var preparing = false
    private var foregroundStarted = false
    private var deleteAfterTransfer = false
    private var discardAfterTransfer = false
    @Volatile private var pausedByUser = false
    @Volatile private var pendingStop: String? = null
    private var queuedStop: Pair<String, String>? = null
    private var lastStartId = 0
    @Volatile private var destroyed = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (runCatching { ensureForeground() }.isFailure) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        lastStartId = startId
        if (preparing) {
            // A stop tapped while its request is still being validated applies once that operation starts.
            val id = intent?.getStringExtra("modelId")
            val action = intent?.getStringExtra("operation")
            if (id != null && action in setOf("delete", "cancel", "pause")) queuedStop = id to checkNotNull(action)
            return START_NOT_STICKY
        }
        if (operationId != null) {
            val id = intent?.getStringExtra("modelId")
            val action = intent?.getStringExtra("operation")
            if (operationId == id && id != null) {
                if (action in setOf("delete", "cancel", "pause")) {
                    pendingStop = action
                    stopSequence()
                }
                when (action) {
                    "delete" -> { deleteAfterTransfer = true; repository.cancel(id) }
                    "cancel" -> { discardAfterTransfer = true; repository.cancel(id) }
                    "pause" -> if (downloading) {
                        pausedByUser = true
                        repository.cancel(id, pause = true)
                    }
                }
            }
            return START_NOT_STICKY
        }
        preparing = true
        worker.execute {
            val valid = runCatching {
                val catalog = repository.catalog
                val id = intent?.getStringExtra("modelId")
                intent?.getStringExtra("operation") == "recommended" ||
                    id != null && catalog?.models?.any { it.id == id } == true
            }.getOrDefault(false)
            mainExecutor.execute {
                preparing = false
                if (destroyed) return@execute
                if (!valid || intent == null) {
                    finishService()
                } else if (intent.getStringExtra("operation") == "recommended") {
                    startRecommended(intent)
                } else {
                    val action = intent.getStringExtra("operation") ?: "import"
                    if (action in setOf("download", "import", "delete", "cancel")) {
                        startOperation(checkNotNull(intent.getStringExtra("modelId")), action, intent)
                    } else finishService()
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun ensureForeground() = synchronized(notificationLock) {
        if (foregroundStarted) return@synchronized
        val manager = checkNotNull(notifications)
        manager.createNotificationChannel(NotificationChannel("offline-models",
            getString(R.string.models_title), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, OfflineModelsActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val initial = Notification.Builder(this, "offline-models")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(getString(R.string.models_title))
            .setContentText(getString(R.string.models_preparing))
            .setContentIntent(open).setVisibility(Notification.VISIBILITY_PRIVATE)
            .setOngoing(true).setOnlyAlertOnce(true).build()
        startForeground(2002, initial)
        foregroundStarted = true
    }

    private fun startRecommended(intent: Intent) {
        if (operationId != null || repository.busy.get() || repository.bundleActive) {
            if (operationId == null) finishService()
            return
        }
        val catalog = repository.catalog
        val language = intent.getStringExtra(RecommendedModels.EXTRA_LANGUAGE)
        val requested = intent.getStringArrayListExtra("modelIds")?.toList().orEmpty()
        val recommended = if (catalog == null || language == null) emptyList()
            else RecommendedModels.ids(catalog.recommendedChat, language)
        if (!RecommendedModels.authorized(requested, recommended) ||
            requested.any { id -> catalog?.models?.none { it.id == id } != false }) {
            finishService()
            return
        }
        preparing = true
        worker.execute {
            val pending = runCatching {
                RecommendedModels.pending(requested, requested.filter {
                    repository.state(it) == IModelStore.READY
                }.toSet())
            }.getOrNull()
            mainExecutor.execute {
                preparing = false
                if (destroyed) return@execute
                if (pending.isNullOrEmpty()) {
                    repository.lastOutcome.set(if (pending == null) R.string.models_unavailable
                        else R.string.models_import_done)
                    finishService()
                } else {
                    sequence = DownloadSequence(pending)
                    sequencePause = null
                    sequenceMetered = intent.getBooleanExtra("allowMetered", false)
                    repository.bundleActive = true
                    startOperation(pending.first(), "download", Intent().putExtra("allowMetered", sequenceMetered))
                }
            }
        }
    }

    private fun stopSequence() {
        sequence?.cancel()
        sequence = null
        sequenceMetered = false
        sequencePause = null
        repository.bundleActive = false
    }

    private fun startOperation(id: String, action: String, intent: Intent) {
        operationId = id
        downloading = action == "download"
        pausedByUser = false
        pendingStop = null
        queuedStop?.let { (stopId, stopAction) ->
            queuedStop = null
            if (stopId == id && (stopAction != "pause" || downloading)) {
                pendingStop = stopAction
                stopSequence()
                when (stopAction) {
                    "delete" -> deleteAfterTransfer = true
                    "cancel" -> discardAfterTransfer = true
                    "pause" -> pausedByUser = true
                }
            }
        }
        repository.downloadFailures.remove(id)
        if (runCatching {
            val manager = checkNotNull(notifications)
            manager.cancel(2003)
            manager.createNotificationChannel(NotificationChannel("offline-models",
                getString(R.string.models_title), NotificationManager.IMPORTANCE_LOW))
            val open = PendingIntent.getActivity(this, 0, Intent(this, OfflineModelsActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val name = when (id) {
                ModelCatalog.HUNGARIAN_VOICE -> R.string.models_voice_anna
                "chat-2b" -> R.string.models_chat_2b
                "chat-4b" -> R.string.models_chat_4b
                "speech-en-small" -> R.string.models_speech_en_small
                else -> R.string.models_speech
            }
            synchronized(notificationLock) {
                check(!destroyed)
                notification = Notification.Builder(this, "offline-models")
                    .setSmallIcon(android.R.drawable.stat_sys_download)
                    .setContentTitle(getString(name))
                    .setContentIntent(open).setVisibility(Notification.VISIBILITY_PRIVATE)
                    .setOngoing(true).setOnlyAlertOnce(true)
                val phase = when (action) {
                    "download" -> IModelStore.DOWNLOADING
                    "delete", "cancel" -> IModelStore.REMOVING
                    else -> IModelStore.IMPORTING
                }
                showProgress(phase, 0, repository.catalog!!.get(id).size, force = true)
            }
        }.isFailure) {
            repository.lastOutcome.set(R.string.models_unavailable)
            operationId = null
            finishService()
            return
        }
        if (runCatching {
            worker.execute {
                var succeeded = false
                var transientPause = false
                var began = false
                try {
                    check(repository.begin(id))
                    began = true
                    pendingStop?.let { repository.cancel(id, pause = it == "pause") }
                    val ok = when (action) {
                        "delete" -> repository.delete(id)
                        "cancel" -> repository.discardPartial(id)
                        else -> repository.importModel(id, downloading = action == "download",
                            allowMetered = intent.getBooleanExtra("allowMetered", false),
                            progress = { phase, bytes, total -> showProgress(phase, bytes, total) }) {
                            val entry = repository.catalog!!.get(id)
                            if (intent.getBooleanExtra("bundled", false)) {
                                File("/product/etc/mosaic/bundled-models", entry.file).inputStream()
                            } else {
                                val uri = intent.data
                                check(uri?.scheme == "content")
                                contentResolver.openInputStream(uri!!) ?: error("Document unavailable")
                            }
                        }
                    }
                    succeeded = ok
                    transientPause = !ok && repository.pauseReason.get() != null && !pausedByUser
                    if (!ok && action == "download" && !pausedByUser && !transientPause) {
                        repository.downloadFailures.add(id)
                    }
                    repository.lastOutcome.set(when {
                        action == "delete" -> if (ok) R.string.models_deleted else R.string.models_delete_failed
                        action == "cancel" -> if (ok) R.string.models_cancelled else R.string.models_cleanup_failed
                        ok -> R.string.models_import_done
                        repository.pauseReason.get() != null -> checkNotNull(repository.pauseReason.get())
                        repository.hasPartial(id) -> R.string.models_partial_kept
                        else -> R.string.models_import_failed
                    })
                } catch (_: Throwable) {
                    if (action == "download") repository.downloadFailures.add(id)
                    if (began) repository.abandon(id)
                    repository.lastOutcome.set(R.string.models_unavailable)
                } finally {
                    val complete = Runnable {
                        operationId = null
                        repository.changed()
                        if (deleteAfterTransfer && !destroyed) {
                            deleteAfterTransfer = false
                            discardAfterTransfer = false
                            startOperation(id, "delete", Intent())
                        } else if (discardAfterTransfer && !destroyed) {
                            discardAfterTransfer = false
                            startOperation(id, "cancel", Intent())
                        } else {
                            if (transientPause) sequencePause = repository.pauseReason.get()
                            val next = sequence?.complete(succeeded, paused = transientPause)
                            if (next != null && !destroyed) {
                                startOperation(next, "download", Intent().putExtra("allowMetered", sequenceMetered))
                            } else {
                                sequencePause?.let { repository.lastOutcome.set(it) }
                                finishService()
                            }
                        }
                    }
                    runCatching { mainExecutor.execute(complete) }.onFailure { finishService() }
                }
            }
        }.isFailure) {
            repository.lastOutcome.set(R.string.models_unavailable)
            operationId = null
            finishService()
        }
    }

    private fun showProgress(phase: Int, bytes: Long, total: Long, force: Boolean = false) =
        synchronized(notificationLock) {
            if (destroyed) return@synchronized
            val builder = notification ?: return@synchronized
            val now = SystemClock.elapsedRealtime()
            val completed = bytes == total && lastNotificationBytes != total
            if (!force && phase == lastNotificationPhase && !completed &&
                (bytes == lastNotificationBytes || now - lastNotificationTime < 1000L)) return@synchronized
            val label = getString(when (phase) {
                IModelStore.DOWNLOADING -> R.string.models_downloading
                IModelStore.VERIFYING -> R.string.models_verifying
                IModelStore.IMPORTING -> R.string.models_importing
                else -> R.string.models_removing
            })
            val text = if (phase == IModelStore.REMOVING) label else
                ModelProgressText.format(this, label, bytes, total)
            val determinate = phase == IModelStore.DOWNLOADING || phase == IModelStore.IMPORTING
            val progress = if (total > 0) (bytes.coerceIn(0, total) * 1000 / total).toInt() else 0
            val updated = builder.setContentText(text).setStyle(Notification.BigTextStyle().bigText(text))
                .setProgress(if (determinate) 1000 else 0, if (determinate) progress else 0, !determinate)
                .build()
            runCatching { notifications?.notify(2002, updated) }
            lastNotificationTime = now
            lastNotificationPhase = phase
            lastNotificationBytes = bytes
        }

    private fun clearNotifications() = synchronized(notificationLock) {
        notification = null
        lastNotificationPhase = -1
        lastNotificationBytes = -1L
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        foregroundStarted = false
        runCatching { notifications?.cancel(2002) }
        runCatching { notifications?.cancel(2003) }
    }

    private fun finishService() {
        queuedStop = null
        stopSequence()
        clearNotifications()
        repository.changed()
        // A start request delivered after the last one seen here keeps the service alive for that request.
        stopSelfResult(lastStartId)
    }

    override fun onDestroy() {
        synchronized(notificationLock) { destroyed = true }
        stopSequence()
        clearNotifications()
        operationId?.let {
            pendingStop = "cancel"
            repository.cancel(it)
        }
        worker.shutdown()
        super.onDestroy()
    }
}
