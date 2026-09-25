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
    private var deleteAfterTransfer = false
    private var discardAfterTransfer = false
    @Volatile private var destroyed = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getStringExtra("modelId")
        if (id == null || runCatching { repository.catalog?.get(id) }.getOrNull() == null) {
            if (operationId == null) finishService()
            return START_NOT_STICKY
        }
        val action = intent.getStringExtra("operation") ?: "import"
        if (operationId != null) {
            if (operationId == id) when (action) {
                "delete" -> { deleteAfterTransfer = true; repository.cancel(id) }
                "cancel" -> { discardAfterTransfer = true; repository.cancel(id) }
                "pause" -> if (downloading) repository.cancel(id, pause = true)
            }
            return START_NOT_STICKY
        }
        if (action !in setOf("download", "import", "delete", "cancel")) {
            finishService()
            return START_NOT_STICKY
        }
        startOperation(id, action, intent)
        return START_NOT_STICKY
    }

    private fun startOperation(id: String, action: String, intent: Intent) {
        if (!repository.begin(id)) { finishService(); return }
        operationId = id
        downloading = action == "download"
        if (runCatching {
            val manager = checkNotNull(notifications)
            manager.cancel(2003)
            manager.createNotificationChannel(NotificationChannel("offline-models",
                getString(R.string.models_title), NotificationManager.IMPORTANCE_LOW))
            val open = PendingIntent.getActivity(this, 0, Intent(this, OfflineModelsActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val name = when (id) {
                "chat-2b" -> R.string.models_chat_2b
                "chat-4b" -> R.string.models_chat_4b
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
                showProgress(phase, 0, repository.catalog!!.get(id).size, foreground = true)
            }
        }.isFailure) {
            repository.abandon(id)
            repository.lastOutcome.set(R.string.models_unavailable)
            operationId = null
            finishService()
            return
        }
        if (runCatching {
            worker.execute {
                try {
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
                    repository.lastOutcome.set(when {
                        action == "delete" -> if (ok) R.string.models_deleted else R.string.models_delete_failed
                        action == "cancel" -> if (ok) R.string.models_cancelled else R.string.models_cleanup_failed
                        ok -> R.string.models_import_done
                        repository.hasPartial(id) -> R.string.models_partial_kept
                        else -> R.string.models_import_failed
                    })
                } catch (_: Throwable) {
                    repository.abandon(id)
                    repository.lastOutcome.set(R.string.models_unavailable)
                } finally {
                    val complete = Runnable {
                        operationId = null
                        clearNotifications()
                        if (deleteAfterTransfer && !destroyed) {
                            deleteAfterTransfer = false
                            discardAfterTransfer = false
                            startOperation(id, "delete", Intent())
                        } else if (discardAfterTransfer && !destroyed) {
                            discardAfterTransfer = false
                            startOperation(id, "cancel", Intent())
                        } else {
                            stopSelf()
                        }
                    }
                    runCatching { mainExecutor.execute(complete) }.onFailure { finishService() }
                }
            }
        }.isFailure) {
            repository.abandon(id)
            repository.lastOutcome.set(R.string.models_unavailable)
            operationId = null
            finishService()
        }
    }

    private fun showProgress(phase: Int, bytes: Long, total: Long, foreground: Boolean = false) =
        synchronized(notificationLock) {
            if (destroyed) return@synchronized
            val builder = notification ?: return@synchronized
            val now = SystemClock.elapsedRealtime()
            val completed = bytes == total && lastNotificationBytes != total
            if (!foreground && phase == lastNotificationPhase && !completed &&
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
            if (foreground) startForeground(2002, updated)
            else runCatching { notifications?.notify(2002, updated) }
            lastNotificationTime = now
            lastNotificationPhase = phase
            lastNotificationBytes = bytes
        }

    private fun clearNotifications() = synchronized(notificationLock) {
        notification = null
        lastNotificationPhase = -1
        lastNotificationBytes = -1L
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        runCatching { notifications?.cancel(2002) }
        runCatching { notifications?.cancel(2003) }
    }

    private fun finishService() {
        clearNotifications()
        stopSelf()
    }

    override fun onDestroy() {
        synchronized(notificationLock) { destroyed = true }
        clearNotifications()
        operationId?.let { repository.cancel(it) }
        worker.shutdown()
        super.onDestroy()
    }
}
