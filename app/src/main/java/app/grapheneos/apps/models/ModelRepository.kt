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

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Binder
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
import app.mosaicos.models.IChatModelListener
import app.mosaicos.models.IModelConsumer
import app.mosaicos.models.IModelLease
import app.mosaicos.models.IModelStopped
import app.mosaicos.models.IModelStore
import app.mosaicos.models.ModelCatalog
import app.mosaicos.models.ModelSpec
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal class ModelRepository private constructor(private val context: Context) {
    val catalog = runCatching { ModelCatalog.read() }.getOrNull()
    private val root = File(context.noBackupFilesDir, "offline-models")
    private val lock = Any()
    private val phases = mutableMapOf<String, Int>()
    private val chatListeners = RemoteCallbackList<IChatModelListener>()
    private var lastSelectedChatModelId: String? = null
    private val leases = mutableMapOf<String, MutableMap<IBinder, IBinder.DeathRecipient>>()
    private val denied = mutableSetOf<String>()
    private val initialized = mutableSetOf<String>()
    private val environmentAvailable by lazy {
        runCatching {
            check(context.checkSelfPermission("android.permission.SETUP_FSVERITY") ==
                android.content.pm.PackageManager.PERMISSION_GRANTED)
            check(context.getSystemService(FileIntegrityManager::class.java)?.isApkVeritySupported == true)
            FileIntegrityManager::class.java.getMethod("setupFsVerity", File::class.java)
            true
        }.getOrDefault(false)
    }
    val busy = AtomicBoolean()
    private val cancelled = AtomicBoolean()
    private val pauseRequested = AtomicBoolean()
    private var operationToken: Any? = null
    @Volatile var activeId: String? = null
        private set
    val lastOutcome = AtomicReference<Int?>()

    private fun chatPreference(method: String, id: String? = null): String? {
        val identity = Binder.clearCallingIdentity()
        return try {
            val uri = Uri.parse("content://${context.packageName}.model-settings")
            checkNotNull(context.contentResolver.call(uri, method, id, null))
                .getString(ModelSettingsProvider.KEY_SELECTED_CHAT_MODEL)
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
    }

    fun selectedChatModelId(): String? = synchronized(lock) {
        val current = catalog ?: return@synchronized null
        val preferred = runCatching { chatPreference("getChatModel") }.getOrNull()
        val candidates = (listOfNotNull(preferred, current.recommendedChat) +
            current.models.filter { it.kind == "chat" }.map { it.id }).distinct()
        val selected = candidates.firstOrNull { id ->
            current.models.any { it.id == id && it.kind == "chat" } && state(id) == IModelStore.READY
        }
        if (selected != lastSelectedChatModelId) {
            lastSelectedChatModelId = selected
            val count = chatListeners.beginBroadcast()
            try {
                repeat(count) { index ->
                    runCatching { chatListeners.getBroadcastItem(index).onChatModelChanged(selected) }
                }
            } finally {
                chatListeners.finishBroadcast()
            }
        }
        selected
    }

    fun selectChatModel(id: String) = synchronized(lock) {
        require(catalog?.models?.any { it.id == id && it.kind == "chat" } == true)
        chatPreference("setChatModel", id)
        selectedChatModelId()
        Unit
    }

    fun registerChatModelListener(listener: IChatModelListener) = synchronized(lock) {
        selectedChatModelId()
        chatListeners.register(listener)
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
        operationToken = Any()
        lastOutcome.set(null)
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
        if (initialized.add(id) && leases[id].isNullOrEmpty() && hasMarker(id, ".use")) {
            // Leases cannot survive the Store process, so an old use marker is not proof of corruption.
            runCatching {
                clearMarker(id, ".use")
                syncDirectory(id)
                Log.i(TAG, "Cleared interrupted model use: $id")
            }.onFailure { denied.add(id) }
        }
        if (id in denied || hasMarker(id, ".bad") ||
            (hasMarker(id, ".use") && leases[id].isNullOrEmpty())) {
            return@synchronized IModelStore.CORRUPT
        }
        if (!model(id).exists()) return@synchronized if (hasPartial(id))
            IModelStore.DOWNLOADING else IModelStore.ABSENT
        if (runCatching { openVerified(entry).close() }.isSuccess) IModelStore.READY
        else {
            quarantine(id)
            IModelStore.CORRUPT
        }
    }

    private fun openVerified(entry: ModelSpec): ParcelFileDescriptor {
        val raw = Os.open(model(entry.id).absolutePath,
            OsConstants.O_RDONLY or OsConstants.O_CLOEXEC or OsConstants.O_NOFOLLOW, 0)
        val fd = try { ParcelFileDescriptor.dup(raw) } finally { Os.close(raw) }
        try {
            ModelCatalog.verify(fd, entry)
            return fd
        } catch (failure: Throwable) {
            fd.close()
            throw failure
        }
    }

    fun open(id: String, lease: IModelLease): ParcelFileDescriptor? = synchronized(lock) {
        if (state(id) != IModelStore.READY) return@synchronized null
        val token = lease.asBinder()
        val active = leases.getOrPut(id) { mutableMapOf() }
        if (token in active || active.size >= 4) return@synchronized null
        var opened: ParcelFileDescriptor? = null
        try {
            opened = openVerified(spec(id))
            persist(marker(id, ".use"))
            syncDirectory(id)
            val death = IBinder.DeathRecipient {
                synchronized(lock) {
                    // Binder death also occurs during normal shutdown and does not prove corruption.
                    if (active.remove(token) != null) quarantine(id, persistent = false)
                }
            }
            token.linkToDeath(death, 0)
            active[token] = death
            opened
        } catch (_: RemoteException) {
            runCatching { opened?.close() }
            quarantine(id, persistent = false)
            null
        } catch (_: Throwable) {
            runCatching { opened?.close() }
            quarantine(id)
            null
        }
    }

    fun release(id: String, lease: IModelLease) = synchronized(lock) {
        spec(id)
        val active = leases[id] ?: return@synchronized
        val token = lease.asBinder()
        val death = active.remove(token) ?: return@synchronized
        token.unlinkToDeath(death, 0)
        if (active.isEmpty()) {
            runCatching { clearMarker(id, ".use"); syncDirectory(id) }
                .onFailure { denied.add(id) }
        }
    }

    fun corrupt(id: String, lease: IModelLease) = synchronized(lock) {
        if (leases[id]?.containsKey(lease.asBinder()) == true) quarantine(id)
    }

    private fun quarantine(id: String, persistent: Boolean = true) {
        val firstFailure = denied.add(id)
        if (persistent) runCatching { persist(marker(id, ".bad")); syncDirectory(id) }
        if (firstFailure) Log.w(TAG, if (persistent) "Model quarantined: $id" else "Model use interrupted: $id")
        if (firstFailure) leases[id]?.keys?.toList()?.forEach { token ->
            runCatching { IModelLease.Stub.asInterface(token).onRevoke() }
        }
    }

    fun usedBytes(id: String): Long = synchronized(lock) {
        (listOf(directory(id)) + directory(id).listFiles().orEmpty()).mapNotNull { file ->
            runCatching { Os.lstat(file.absolutePath) }.getOrNull()
        }.distinctBy { it.st_dev to it.st_ino }.sumOf { it.st_blocks * 512L }
    }

    private fun stopConsumer(id: String): Boolean {
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
        val bound = context.bindService(Intent().setComponent(ComponentName(ASSISTANT,
            "apps.mosaicos.timty.engine.ModelControlService")), connection, Context.BIND_AUTO_CREATE)
        if (!bound) return false
        return try { stopped.await(60, TimeUnit.SECONDS) && success.get() }
        finally { context.unbindService(connection) }
    }

    fun importModel(id: String, downloading: Boolean = false, allowMetered: Boolean = false,
        progress: (Int, Long, Long) -> Unit = { _, _, _ -> }, source: () -> InputStream): Boolean {
        check(activeId == id && busy.get())
        val token = synchronized(lock) { checkNotNull(operationToken) }
        var published = false
        var previous: File? = null
        var previousReady = false
        var keepPartial = false
        var resumable = true
        try {
            val entry = spec(id)
            check(ModelCatalog.compatible(entry) && environmentAvailable)
            val transferPhase = if (downloading) IModelStore.DOWNLOADING else IModelStore.IMPORTING
            synchronized(lock) {
                previousReady = state(id) == IModelStore.READY
                phases[id] = transferPhase
            }
            val dir = directory(id)
            check(dir.isDirectory || dir.mkdirs())
            if (!downloading) clear(staged(id))
            val sha = MessageDigest.getInstance("SHA-256")
            val createFlags = if (staged(id).exists()) 0 else OsConstants.O_CREAT or OsConstants.O_EXCL
            val raw = Os.open(staged(id).absolutePath, OsConstants.O_RDWR or createFlags or
                OsConstants.O_CLOEXEC or OsConstants.O_NOFOLLOW, 384)
            val owned = try { ParcelFileDescriptor.dup(raw) } finally { Os.close(raw) }
            ParcelFileDescriptor.AutoCloseOutputStream(owned).use { output ->
                val info = Os.fstat(output.fd)
                check(OsConstants.S_ISREG(info.st_mode) && info.st_nlink == 1L &&
                    info.st_size in 0..entry.size)
                var total = info.st_size
                progress(transferPhase, total, entry.size)
                fun storageCheck() {
                    check(StatFs(dir.absolutePath).availableBytes >=
                        entry.size - total + entry.size / 64 + 134_217_728L)
                }
                storageCheck()
                val buffer = ByteArray(1024 * 1024)
                if (total > 0) {
                    synchronized(lock) { phases[id] = IModelStore.VERIFYING }
                    progress(IModelStore.VERIFYING, total, entry.size)
                    var remaining = total
                    ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.dup(output.fd)).use { prefix ->
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
                        storageCheck()
                        val count = it.read(buffer)
                        if (count < 0) break
                        checkpoint()
                        check(count > 0 && count.toLong() <= entry.size - total)
                        output.write(buffer, 0, count)
                        sha.update(buffer, 0, count)
                        total += count
                        progress(transferPhase, total, entry.size)
                    }
                }
                check(total == entry.size)
                synchronized(lock) { phases[id] = IModelStore.VERIFYING }
                progress(IModelStore.VERIFYING, total, entry.size)
                output.fd.sync()
            }
            check(MessageDigest.isEqual(sha.digest(), ModelCatalog.decodeHex(entry.sha256)))
            checkpoint()
            resumable = false
            val integrity = context.getSystemService(FileIntegrityManager::class.java)
                ?: error("File integrity service unavailable")
            check(integrity.isApkVeritySupported)
            FileIntegrityManager::class.java.getMethod("setupFsVerity", File::class.java)
                .invoke(integrity, staged(id))
            ParcelFileDescriptor.open(staged(id), ParcelFileDescriptor.MODE_READ_ONLY).use {
                ModelCatalog.verify(it, entry)
            }
            checkpoint()
            check(stopConsumer(id))
            synchronized(lock) {
                check(leases[id].isNullOrEmpty())
                checkpoint()
                if (model(id).exists()) {
                    val backup = File(directory(id), "previous.model")
                    clear(backup)
                    Os.link(model(id).absolutePath, backup.absolutePath)
                    previous = backup
                    syncDirectory(id)
                }
                Os.rename(staged(id).absolutePath, model(id).absolutePath)
                published = true
                syncDirectory(id)
                clearMarker(id, ".bad")
                clearMarker(id, ".use")
                syncDirectory(id)
                denied.remove(id)
            }
            synchronized(lock) {
                directory(id).listFiles()?.filter { it != model(id) }?.forEach {
                    runCatching { clear(it) }
                }
                runCatching { syncDirectory(id) }
            }
            return true
        } catch (failure: Throwable) {
            keepPartial = downloading && resumable && !published &&
                (failure is ModelTransferStopped && failure.keepPartial || failure is ModelDownloadUnavailable)
            if (published) synchronized(lock) {
                val backup = previous
                val restored = backup != null && runCatching {
                    Os.rename(backup.absolutePath, model(id).absolutePath)
                    syncDirectory(id)
                }.isSuccess
                if (!restored || !previousReady) quarantine(id)
            }
            return false
        } finally {
            if (!published) previous?.let { runCatching { clear(it) } }
            if (!keepPartial) runCatching {
                clear(staged(id))
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
        private const val ASSISTANT = "apps.mosaicos.timty"
        @Volatile private var instance: ModelRepository? = null
        fun get(context: Context): ModelRepository = instance ?: synchronized(this) {
            instance ?: ModelRepository(context.applicationContext).also { instance = it }
        }
    }
}
