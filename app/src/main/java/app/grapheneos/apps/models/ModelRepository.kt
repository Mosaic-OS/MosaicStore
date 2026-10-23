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
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.RemoteCallbackList
import android.os.RemoteException
import android.os.StatFs
import android.security.FileIntegrityManager
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.AtomicFile
import android.util.Log
import app.grapheneos.apps.R
import app.mosaicos.models.IChatModelListener
import app.mosaicos.models.IModelConsumer
import app.mosaicos.models.IModelLease
import app.mosaicos.models.IModelStopped
import app.mosaicos.models.IModelStore
import app.mosaicos.models.ModelCatalog
import app.mosaicos.models.ModelSpec
import app.mosaicos.models.SpeechModels
import java.io.File
import java.io.InputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow

internal class ModelRepository private constructor(private val context: Context) {
    val catalog = runCatching { ModelCatalog.read() }.getOrNull()
    private val root = File(context.noBackupFilesDir, "offline-models")
    private val lock = Any()
    private val selections by lazy { ModelSelections(context) }
    private val clearedUseMarkers = mutableSetOf<String>()
    private val pausedPublication = mutableSetOf<String>()
    private val publishFailed = mutableSetOf<String>()
    val events = MutableSharedFlow<Unit>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val pauseReason = AtomicReference<Int?>()

    fun changed() { events.tryEmit(Unit) }
    private val phases = mutableMapOf<String, Int>()
    private val chatListeners = RemoteCallbackList<IChatModelListener>()
    private val callbacks = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var lastSelectedChatModelId: String? = null
    private val leases = mutableMapOf<String, MutableMap<IBinder, IBinder.DeathRecipient>>()
    private val denied = mutableSetOf<String>()
    private val environmentAvailable = runCatching {
        check(context.checkSelfPermission("android.permission.SETUP_FSVERITY") ==
            android.content.pm.PackageManager.PERMISSION_GRANTED)
        check(context.getSystemService(FileIntegrityManager::class.java)?.isApkVeritySupported == true)
        FileIntegrityManager::class.java.getMethod("setupFsVerity", File::class.java)
        true
    }.getOrDefault(false)
    val busy = AtomicBoolean()
    private val cancelled = AtomicBoolean()
    private val pauseRequested = AtomicBoolean()
    private var operationToken: Any? = null
    @Volatile var activeId: String? = null
        private set
    val lastOutcome = AtomicReference<Int?>()
    @Volatile var bundleActive = false
        set(value) { field = value; changed() }
    val downloadFailures: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    init {
        changed()
        runCatching { Thread({ runCatching { cleanupRetiredSpeech() } }, "retired-speech-cleanup").start() }
    }

    private fun cleanupRetiredSpeech() {
        val completed = File(context.noBackupFilesDir, "retired-speech-cleanup-v2")
        if (completed.isFile) return
        removeRetiredSelections()
        RetiredSpeechCleanup.removeFiles(context.noBackupFilesDir.toPath())
        val manager = context.getSystemService(NotificationManager::class.java)
        manager?.activeNotifications?.filter {
            it.notification.channelId == "offline-models" &&
                RetiredSpeechCleanup.notification(it.id,
                    it.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
        }?.forEach { runCatching { manager.cancel(it.tag, it.id) } }
        persist(completed)
        val fd = Os.open(context.noBackupFilesDir.absolutePath, OsConstants.O_RDONLY, 0)
        try { Os.fsync(fd) } finally { Os.close(fd) }
    }

    fun chatPreference(): String? = selections.get(SelectionMigration.CHAT)

    fun speechPreference(key: String): String? = selections.get(key)

    fun removeRetiredSelections() { selections.removeRetired() }

    fun selectedChatModelId(): String? = synchronized(lock) {
        val current = catalog ?: return@synchronized null
        val preferred = runCatching { chatPreference() }.getOrElse { return@synchronized null }
        val candidates = (listOfNotNull(preferred, current.recommendedChat) +
            current.models.filter { it.kind == "chat" }.map { it.id }).distinct()
        val selected = candidates.firstOrNull { id ->
            current.models.any { it.id == id && it.kind == "chat" } && state(id) == IModelStore.READY
        }
        if (selected != lastSelectedChatModelId) {
            lastSelectedChatModelId = selected
            callbacks.execute {
                val count = chatListeners.beginBroadcast()
                try {
                    repeat(count) { index ->
                        runCatching { chatListeners.getBroadcastItem(index).onChatModelChanged(selected) }
                    }
                } finally {
                    chatListeners.finishBroadcast()
                }
            }
        }
        selected
    }

    fun selectChatModel(id: String) = synchronized(lock) {
        require(catalog?.models?.any { it.id == id && it.kind == "chat" } == true)
        selections.set(SelectionMigration.CHAT, id)
        changed()
        selectedChatModelId()
        Unit
    }

    fun registerChatModelListener(listener: IChatModelListener) = synchronized(lock) {
        selectedChatModelId()
        chatListeners.register(listener)
        Unit
    }

    fun selectedSpeechModelId(language: String = "en"): String? = synchronized(lock) {
        if (language != "en") return@synchronized null
        val current = catalog ?: return@synchronized null
        val preferred = runCatching { speechPreference(SelectionMigration.speechKey(language)) }
            .getOrElse { return@synchronized null }
        val speech = current.models.filter { SpeechModels.language(it.id) == language }
        val ready = speech.filter { state(it.id) == IModelStore.READY }.map { it.id }.toSet()
        SpeechModels.choose(language, preferred, ready)
    }

    fun selectSpeechModel(id: String) = synchronized(lock) {
        require(catalog?.models?.any { it.id == id && SpeechModels.language(id) != null } == true)
        require(state(id) == IModelStore.READY)
        selections.set(SelectionMigration.speechKey(checkNotNull(SpeechModels.language(id))), id)
        changed()
        Unit
    }

    fun unregisterChatModelListener(listener: IChatModelListener) = synchronized(lock) {
        chatListeners.unregister(listener)
        Unit
    }

    fun begin(id: String): Boolean = synchronized(lock) {
        spec(id)
        if (!busy.compareAndSet(false, true)) return@synchronized false
        cancelled.set(false)
        pauseRequested.set(false)
        activeId = id
        pausedPublication.remove(id)
        publishFailed.remove(id)
        operationToken = Any()
        lastOutcome.set(null)
        pauseReason.set(null)
        changed()
        true
    }

    fun cancel(id: String, pause: Boolean = false, token: Any? = null) = synchronized(lock) {
        if (activeId == id && (token == null || token === operationToken)) {
            if (!cancelled.get()) pauseRequested.set(pause)
            if (!pause) pauseRequested.set(false)
            cancelled.set(true)
        }
    }

    private fun checkpoint() {
        if (cancelled.get()) throw ModelTransferStopped(pauseRequested.get())
    }

    fun abandon(id: String) { finish(id) }

    private fun finish(id: String) = synchronized(lock) {
        phases.remove(id)
        activeId = null
        operationToken = null
        cancelled.set(false)
        pauseRequested.set(false)
        busy.set(false)
        changed()
        if (spec(id).kind == "chat") selectedChatModelId()
        Unit
    }

    fun partialBytes(id: String): Long = synchronized(lock) {
        runCatching { Os.lstat(staged(id).absolutePath) }.getOrNull()?.let {
            if (OsConstants.S_ISREG(it.st_mode)) it.st_size.coerceIn(0, spec(id).size) else 0L
        } ?: 0L
    }

    fun hasPartial(id: String): Boolean = synchronized(lock) { staged(id).exists() }

    private fun spec(id: String) = catalog?.get(id) ?: error("Catalog unavailable")
    private fun directory(id: String) = File(root, spec(id).id)
    private fun model(id: String) = File(directory(id), spec(id).verity + ".model")
    private fun marker(id: String, suffix: String) = File(directory(id), spec(id).verity + suffix)
    private fun hasMarker(id: String, suffix: String) =
        listOf("", ".bak", ".new").any { marker(id, suffix + it).exists() }
    private fun clearMarker(id: String, suffix: String) {
        listOf("", ".bak", ".new").forEach { clear(marker(id, suffix + it)) }
    }
    private fun staged(id: String) = File(directory(id), "import.part")

    private fun persist(file: File) {
        check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try {
            output.write(byteArrayOf(1))
            output.fd.sync()
            atomic.finishWrite(output)
            check(file.isFile && file.length() == 1L)
        } catch (failure: Throwable) {
            atomic.failWrite(output)
            throw failure
        }
    }

    private fun syncDirectory(id: String) {
        val fd = Os.open(directory(id).absolutePath, OsConstants.O_RDONLY, 0)
        try { Os.fsync(fd) } finally { Os.close(fd) }
    }

    private fun clear(file: File) {
        try { Os.lstat(file.absolutePath) } catch (failure: ErrnoException) {
            if (failure.errno == OsConstants.ENOENT) return else throw failure
        }
        Os.remove(file.absolutePath)
    }

    fun state(id: String): Int = synchronized(lock) {
        val entry = runCatching { spec(id) }.getOrNull() ?: return@synchronized IModelStore.INCOMPATIBLE
        if (!ModelCatalog.compatible(entry)) return@synchronized IModelStore.INCOMPATIBLE
        if (!environmentAvailable) return@synchronized IModelStore.UNAVAILABLE
        phases[id]?.let { return@synchronized it }
        if (id !in clearedUseMarkers && leases[id].isNullOrEmpty()) {
            // Leases cannot survive the Store process, so an old use marker is not proof of corruption.
            if (runCatching {
                if (hasMarker(id, ".use")) {
                    clearMarker(id, ".use")
                    syncDirectory(id)
                }
                clearedUseMarkers.add(id)
            }.isFailure) return@synchronized IModelStore.UNAVAILABLE
        }
        val quarantined = id in denied || hasMarker(id, ".bad")
        if (!quarantined && activeId != id && id !in pausedPublication && id !in publishFailed &&
            leases[id].isNullOrEmpty() && verifiedStage(id)) {
            // A failed publication waits for the next explicit operation instead of retrying on every status read.
            if (runCatching { publishStaged(id) }.isFailure) publishFailed.add(id)
        }
        if (quarantined || id in denied) {
            return@synchronized IModelStore.CORRUPT
        }
        if (!model(id).exists()) return@synchronized if (hasPartial(id))
            IModelStore.DOWNLOADING else IModelStore.ABSENT
        try {
            openVerified(entry).close()
            IModelStore.READY
        } catch (_: ModelVerificationException) {
            quarantine(id)
            IModelStore.CORRUPT
        } catch (_: Exception) {
            IModelStore.UNAVAILABLE
        }
    }

    private fun openVerified(entry: ModelSpec, file: File = model(entry.id)): ParcelFileDescriptor {
        val raw = Os.open(file.absolutePath,
            OsConstants.O_RDONLY or OsConstants.O_CLOEXEC or OsConstants.O_NOFOLLOW, 0)
        val fd = try { ParcelFileDescriptor.dup(raw) } finally { Os.close(raw) }
        try {
            ModelCatalog.verify(fd, entry)
            return fd
        } catch (failure: Throwable) {
            fd.close()
            if (failure is IllegalArgumentException || failure is IllegalStateException) {
                throw ModelVerificationException(failure)
            }
            throw failure
        }
    }

    private class ModelVerificationException(cause: Throwable) : Exception(cause)

    private fun clearUnusedLease(id: String) {
        if (!leases[id].isNullOrEmpty()) return
        leases.remove(id)
        changed()
    }

    fun open(id: String, lease: IModelLease): ParcelFileDescriptor? = synchronized(lock) {
        if (state(id) != IModelStore.READY) return@synchronized null
        val token = lease.asBinder()
        val active = leases.getOrPut(id) { mutableMapOf() }
        if (token in active || active.size >= 4) return@synchronized null
        var opened: ParcelFileDescriptor? = null
        try {
            opened = openVerified(spec(id))
            val death = IBinder.DeathRecipient {
                synchronized(lock) {
                    // Binder death also occurs during normal shutdown and does not prove corruption.
                    if (active.remove(token) != null) clearUnusedLease(id)
                }
            }
            token.linkToDeath(death, 0)
            active[token] = death
            opened
        } catch (_: RemoteException) {
            runCatching { opened?.close() }
            clearUnusedLease(id)
            null
        } catch (_: ModelVerificationException) {
            runCatching { opened?.close() }
            quarantine(id)
            clearUnusedLease(id)
            null
        } catch (_: Throwable) {
            runCatching { opened?.close() }
            clearUnusedLease(id)
            null
        }
    }

    fun release(id: String, lease: IModelLease) = synchronized(lock) {
        spec(id)
        val active = leases[id] ?: return@synchronized
        val token = lease.asBinder()
        val death = active.remove(token) ?: return@synchronized
        runCatching { token.unlinkToDeath(death, 0) }
        clearUnusedLease(id)
    }

    fun corrupt(id: String, lease: IModelLease) = synchronized(lock) {
        if (leases[id]?.containsKey(lease.asBinder()) == true) quarantine(id)
    }

    private fun quarantine(id: String) {
        val firstFailure = denied.add(id)
        runCatching { persist(marker(id, ".bad")); syncDirectory(id) }
        if (firstFailure) {
            Log.w(TAG, "Model quarantined: $id")
            changed()
        }
        if (firstFailure) leases[id]?.keys?.toList()?.forEach { token ->
            callbacks.execute { runCatching { IModelLease.Stub.asInterface(token).onRevoke() } }
        }
    }

    fun usedBytes(id: String): Long = synchronized(lock) {
        (listOf(directory(id)) + directory(id).listFiles().orEmpty()).mapNotNull { file ->
            runCatching { Os.lstat(file.absolutePath) }.getOrNull()
        }.distinctBy { it.st_dev to it.st_ino }.sumOf { it.st_blocks * 512L }
    }

    private fun stopConsumer(id: String): Boolean {
        if (synchronized(lock) { leases[id].isNullOrEmpty() }) return true
        val app = context.packageManager.getApplicationInfo(ASSISTANT, 0)
        if (app.flags and ApplicationInfo.FLAG_SYSTEM == 0 ||
            context.packageManager.checkSignatures(ASSISTANT, "android") != 0) return false
        val stopped = CountDownLatch(1)
        val success = AtomicBoolean()
        val callback = object : IModelStopped.Stub() {
            override fun onStopped(value: Boolean) { success.set(value); stopped.countDown() }
        }
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                runCatching { IModelConsumer.Stub.asInterface(binder).stopModel(id, callback) }
                    .onFailure { stopped.countDown() }
            }
            override fun onServiceDisconnected(name: ComponentName) { stopped.countDown() }
            override fun onNullBinding(name: ComponentName) { stopped.countDown() }
            override fun onBindingDied(name: ComponentName) { stopped.countDown() }
        }
        val consumer = if (id == ModelCatalog.HUNGARIAN_VOICE) "apps.mosaicos.tabiti.tts.TtsModelControlService"
            else "apps.mosaicos.tabiti.engine.ModelControlService"
        val bound = context.bindService(Intent().setComponent(ComponentName(ASSISTANT, consumer)),
            connection, Context.BIND_AUTO_CREATE)
        if (!bound) return false
        return try { stopped.await(60, TimeUnit.SECONDS) && success.get() }
        finally { context.unbindService(connection) }
    }

    private fun verifiedStage(id: String): Boolean {
        if (staged(id).length() != spec(id).size) return false
        return runCatching { openVerified(spec(id), staged(id)).use { } }.isSuccess
    }

    private fun publishStaged(id: String) {
        check(leases[id].isNullOrEmpty())
        val backup = File(directory(id), "previous.model")
        var replaced = false
        var backedUp = false
        var previousReady = false
        try {
            clear(backup)
            // SELinux forbids app domains from hard-linking files, so every step here is a rename.
            if (model(id).exists()) {
                previousReady = id !in denied && !hasMarker(id, ".bad") &&
                    runCatching { openVerified(spec(id)).close() }.isSuccess
                Os.rename(model(id).absolutePath, backup.absolutePath)
                backedUp = true
            }
            Os.rename(staged(id).absolutePath, model(id).absolutePath)
            replaced = true
            syncDirectory(id)
            clearMarker(id, ".bad")
            clearMarker(id, ".use")
            syncDirectory(id)
            denied.remove(id)
        } catch (failure: Throwable) {
            val restored = runCatching {
                if (replaced) Os.rename(model(id).absolutePath, staged(id).absolutePath)
                if (backedUp) Os.rename(backup.absolutePath, model(id).absolutePath)
                syncDirectory(id)
            }.isSuccess
            if (!restored || backedUp && !previousReady) quarantine(id)
            throw failure
        }
        runCatching {
            clearMarker(id, ".resume")
            clearMarker(id, ".verified")
            clear(backup)
            syncDirectory(id)
        }
        changed()
    }

    private fun syncedOffset(id: String, inode: Long, size: Long): Long {
        val record = runCatching {
            AtomicFile(marker(id, ".resume")).openRead().use { input ->
                val bytes = ByteArray(96)
                val count = input.read(bytes)
                check(count in 1..95 && input.read() == -1)
                String(bytes, 0, count, Charsets.US_ASCII).split(':')
            }
        }.getOrNull()
        val offset = if (record?.size == 2 && record[0].toLongOrNull() == inode) record[1].toLongOrNull()
            else null
        return TransferPolicy.resumeOffset(size, offset)
    }

    private fun recordSyncedOffset(id: String, inode: Long, offset: Long) {
        val atomic = AtomicFile(marker(id, ".resume"))
        val output = atomic.startWrite()
        try {
            output.write("$inode:$offset".toByteArray(Charsets.US_ASCII))
            output.fd.sync()
            atomic.finishWrite(output)
            syncDirectory(id)
        } catch (failure: Throwable) {
            atomic.failWrite(output)
            throw failure
        }
    }

    fun importModel(id: String, downloading: Boolean = false, allowMetered: Boolean = false,
        progress: (Int, Long, Long) -> Unit = { _, _, _ -> }, source: () -> InputStream): Boolean {
        check(activeId == id && busy.get())
        val token = synchronized(lock) { checkNotNull(operationToken) }
        var published = false
        var keepPartial = false
        var verified = staged(id).exists() && hasMarker(id, ".verified")
        var hashVerified = false
        try {
            val entry = spec(id)
            check(ModelCatalog.compatible(entry) && environmentAvailable)
            val transferPhase = if (downloading) IModelStore.DOWNLOADING else IModelStore.IMPORTING
            synchronized(lock) { phases[id] = transferPhase }
            changed()
            val dir = directory(id)
            check(dir.isDirectory || dir.mkdirs())
            verified = verifiedStage(id)
            if (!verified) {
                clearMarker(id, ".verified")
                if (!downloading) {
                    clear(staged(id))
                    clearMarker(id, ".resume")
                }
                val sha = MessageDigest.getInstance("SHA-256")
                val createFlags = if (staged(id).exists()) 0 else OsConstants.O_CREAT or OsConstants.O_EXCL
                val raw = Os.open(staged(id).absolutePath, OsConstants.O_RDWR or createFlags or
                    OsConstants.O_CLOEXEC or OsConstants.O_NOFOLLOW, 384)
                val owned = try { ParcelFileDescriptor.dup(raw) } finally { Os.close(raw) }
                ParcelFileDescriptor.AutoCloseOutputStream(owned).use { output ->
                    val info = Os.fstat(output.fd)
                    check(OsConstants.S_ISREG(info.st_mode) && info.st_nlink == 1L &&
                        info.st_size in 0..entry.size)
                    var total = if (downloading) syncedOffset(id, info.st_ino, info.st_size) else 0L
                    Os.ftruncate(output.fd, total)
                    var lastSync = total
                    var nextSpaceCheck = total
                    var syncFailed = false
                    fun syncProgress() {
                        // A failed fsync may already have dropped dirty pages, so no later offset can be trusted.
                        check(!syncFailed)
                        try { output.fd.sync() } catch (failure: Throwable) { syncFailed = true; throw failure }
                        if (downloading) recordSyncedOffset(id, info.st_ino, total)
                        lastSync = total
                    }
                    fun storageCheck() {
                        if (StatFs(dir.absolutePath).availableBytes <
                            entry.size - total + entry.size / 64 + 134_217_728L) throw ModelStorageUnavailable()
                        nextSpaceCheck = total + TransferPolicy.SPACE_INTERVAL
                    }
                    try {
                        if (downloading) syncProgress()
                        progress(transferPhase, total, entry.size)
                        storageCheck()
                        val buffer = ByteArray(TransferPolicy.BUFFER_BYTES)
                        if (total > 0) {
                            synchronized(lock) { phases[id] = IModelStore.VERIFYING }
                            progress(IModelStore.VERIFYING, total, entry.size)
                            var remaining = total
                            val prefixFd = ParcelFileDescriptor.dup(output.fd)
                            ParcelFileDescriptor.AutoCloseInputStream(prefixFd).use { prefix ->
                                while (remaining > 0) {
                                    checkpoint()
                                    val count = prefix.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                                    check(count > 0)
                                    sha.update(buffer, 0, count)
                                    remaining -= count
                                }
                            }
                        }
                        checkpoint()
                        synchronized(lock) { phases[id] = transferPhase }
                        progress(transferPhase, total, entry.size)
                        val input = if (downloading && total == entry.size) java.io.ByteArrayInputStream(byteArrayOf())
                            else if (downloading) ModelDownload(context, entry, total, allowMetered,
                                { checkpoint() }, { cancel(id, pause = true, token = token) }) else source()
                        input.use {
                            while (true) {
                                checkpoint()
                                if (total >= nextSpaceCheck) storageCheck()
                                val count = TransferPolicy.readBlock(it, buffer) { checkpoint() }
                                if (count == 0) break
                                checkpoint()
                                check(count.toLong() <= entry.size - total)
                                try {
                                    output.write(buffer, 0, count)
                                } catch (failure: IOException) {
                                    if ((failure.cause as? ErrnoException)?.errno == OsConstants.ENOSPC) {
                                        throw ModelStorageUnavailable(failure)
                                    }
                                    throw failure
                                }
                                sha.update(buffer, 0, count)
                                total += count
                                if (total - lastSync >= TransferPolicy.SYNC_INTERVAL) syncProgress()
                                progress(transferPhase, total, entry.size)
                            }
                        }
                        check(total == entry.size)
                        synchronized(lock) { phases[id] = IModelStore.VERIFYING }
                        progress(IModelStore.VERIFYING, total, entry.size)
                        syncProgress()
                    } catch (failure: Throwable) {
                        runCatching { syncProgress() }
                        throw failure
                    }
                }
                check(MessageDigest.isEqual(sha.digest(), ModelCatalog.decodeHex(entry.sha256)))
                hashVerified = true
                checkpoint()
                val integrity = context.getSystemService(FileIntegrityManager::class.java)
                    ?: error("File integrity service unavailable")
                check(integrity.isApkVeritySupported)
                FileIntegrityManager::class.java.getMethod("setupFsVerity", File::class.java)
                    .invoke(integrity, staged(id))
                openVerified(entry, staged(id)).close()
                verified = true
                persist(marker(id, ".verified"))
                syncDirectory(id)
            }
            checkpoint()
            check(stopConsumer(id))
            synchronized(lock) {
                checkpoint()
                publishStaged(id)
                published = true
            }
            return true
        } catch (failure: Throwable) {
            // A hash-checked download survives a failed fs-verity setup, so Resume retries it without the network.
            keepPartial = TransferPolicy.keepPartial(verified || downloading && hashVerified, downloading, published,
                failure)
            if (verified && (failure is ModelTransferStopped || cancelled.get())) synchronized(lock) {
                pausedPublication.add(id)
            }
            if (keepPartial) pauseReason.set(when {
                verified -> R.string.models_publish_pending
                failure is ModelStorageUnavailable -> R.string.models_space_paused
                failure is ModelTransferStopped || hashVerified -> R.string.models_partial_kept
                else -> R.string.models_network_paused
            })
            return false
        } finally {
            if (!keepPartial && !published) runCatching {
                clear(staged(id))
                clearMarker(id, ".resume")
                clearMarker(id, ".verified")
                if (directory(id).exists()) syncDirectory(id)
            }
            runCatching { pruneEmptyDirectory(id) }
            finish(id)
        }
    }

    private fun pruneEmptyDirectory(id: String) {
        val dir = directory(id)
        if (!dir.exists()) return
        val children = dir.list() ?: error("Model directory unavailable")
        if (children.isEmpty()) {
            clear(dir)
            val fd = Os.open(root.absolutePath, OsConstants.O_RDONLY, 0)
            try { Os.fsync(fd) } finally { Os.close(fd) }
        }
    }

    fun discardPartial(id: String): Boolean {
        check(activeId == id && busy.get())
        return try {
            clear(staged(id))
            clearMarker(id, ".resume")
            clearMarker(id, ".verified")
            if (directory(id).exists()) syncDirectory(id)
            pruneEmptyDirectory(id)
            true
        } catch (_: Throwable) {
            false
        } finally {
            finish(id)
        }
    }

    fun delete(id: String): Boolean {
        check(activeId == id && busy.get())
        try {
            spec(id)
            synchronized(lock) { phases[id] = IModelStore.REMOVING }
            context.getSystemService(NotificationManager::class.java)?.cancel(2003)
            check(stopConsumer(id))
            synchronized(lock) {
                check(leases[id].isNullOrEmpty())
                val dir = directory(id)
                if (dir.exists()) {
                    val files = dir.listFiles() ?: error("Model directory unavailable")
                    val markerSuffixes = setOf("bad", "bad.bak", "bad.new", "use", "use.bak", "use.new")
                    val (markers, payloads) = files.partition { it.name.substringAfter('.') in markerSuffixes }
                    payloads.forEach { clear(it) }
                    syncDirectory(id)
                    markers.forEach { clear(it) }
                    syncDirectory(id)
                }
                pruneEmptyDirectory(id)
                check(!dir.exists())
                leases.remove(id)
                denied.remove(id)
                lastOutcome.set(null)
            }
            return true
        } catch (_: Throwable) {
            return false
        } finally {
            finish(id)
        }
    }

    companion object {
        private const val TAG = "ModelRepository"
        private const val ASSISTANT = "apps.mosaicos.tabiti"
        @Volatile private var instance: ModelRepository? = null
        fun get(context: Context): ModelRepository = instance ?: synchronized(this) {
            instance ?: ModelRepository(context.applicationContext).also { instance = it }
        }
    }
}
