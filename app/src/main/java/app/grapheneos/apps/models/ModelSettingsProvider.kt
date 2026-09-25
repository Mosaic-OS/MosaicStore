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

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import app.grapheneos.apps.R
import app.grapheneos.apps.util.getSharedPreferences

// SharedPreferences writes must stay in the main process to preserve other Store settings
class ModelSettingsProvider : ContentProvider() {
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        check(Binder.getCallingUid() == Process.myUid())
        val preferences = getSharedPreferences(R.string.pref_file_settings)
        when (method) {
            "getChatModel" -> Unit
            "setChatModel" -> {
                require(arg in setOf("chat-2b", "chat-4b"))
                check(preferences.edit().putString(KEY_SELECTED_CHAT_MODEL, arg).commit())
            }
            else -> throw IllegalArgumentException("Unknown model setting")
        }
        return Bundle().apply {
            putString(KEY_SELECTED_CHAT_MODEL, preferences.getString(KEY_SELECTED_CHAT_MODEL, null))
        }
    }

    override fun query(uri: Uri, projection: Array<String>?, selection: String?,
        selectionArgs: Array<String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?,
        selectionArgs: Array<String>?): Int = 0

    internal companion object {
        const val KEY_SELECTED_CHAT_MODEL = "selected_chat_model"
    }
}
