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

import android.app.Service
import android.content.Intent
import android.content.IntentFilter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Binder
import android.os.IBinder
import android.os.ParcelFileDescriptor
import app.mosaicos.models.IChatModelListener
import app.mosaicos.models.IModelLease
import app.mosaicos.models.IModelStore

class ModelStoreService : Service() {
    private val repository by lazy { ModelRepository.get(this) }

    private val callerLock = Any()
    private var verifiedUid: Int? = null
    private val packageChanges = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.data?.schemeSpecificPart == "apps.mosaicos.tabiti") {
                synchronized(callerLock) { verifiedUid = null }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        registerReceiver(packageChanges, filter, Context.RECEIVER_NOT_EXPORTED)
    }

    override fun onDestroy() {
        unregisterReceiver(packageChanges)
        synchronized(callerLock) { verifiedUid = null }
        super.onDestroy()
    }

    private fun checkCaller() = synchronized(callerLock) {
        val uid = Binder.getCallingUid()
        if (verifiedUid == uid) return@synchronized
        val app = packageManager.getApplicationInfo("apps.mosaicos.tabiti", 0)
        check(uid == app.uid && app.flags and ApplicationInfo.FLAG_SYSTEM != 0 &&
            packageManager.checkSignatures("apps.mosaicos.tabiti", "android") == 0)
        verifiedUid = uid
    }

    override fun onBind(intent: Intent?): IBinder = object : IModelStore.Stub() {
        override fun getSelectedChatModelId(): String? {
            checkCaller()
            return repository.selectedChatModelId()
        }
        override fun setSelectedChatModelId(modelId: String) {
            checkCaller()
            repository.selectChatModel(modelId)
        }
        override fun registerChatModelListener(listener: IChatModelListener) {
            checkCaller()
            repository.registerChatModelListener(listener)
        }
        override fun unregisterChatModelListener(listener: IChatModelListener) {
            checkCaller()
            repository.unregisterChatModelListener(listener)
        }
        override fun getState(modelId: String): Int {
            checkCaller()
            return repository.state(modelId)
        }
        override fun openModel(modelId: String, lease: IModelLease): ParcelFileDescriptor? {
            checkCaller()
            return repository.open(modelId, lease)
        }
        override fun releaseModel(modelId: String, lease: IModelLease) {
            checkCaller()
            repository.release(modelId, lease)
        }
        override fun reportCorrupt(modelId: String, lease: IModelLease) {
            checkCaller()
            repository.corrupt(modelId, lease)
        }
        override fun getSelectedSpeechModelId(): String? {
            checkCaller()
            return repository.selectedSpeechModelId()
        }
        override fun getSelectedSpeechModelIdForLanguage(language: String): String? {
            checkCaller()
            return repository.selectedSpeechModelId(language)
        }
        override fun setSelectedSpeechModelId(modelId: String) {
            checkCaller()
            repository.selectSpeechModel(modelId)
        }
    }
}
