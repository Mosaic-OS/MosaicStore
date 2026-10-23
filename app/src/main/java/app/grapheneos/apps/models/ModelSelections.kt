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

import android.app.Application
import android.content.Context
import app.grapheneos.apps.R

internal class ModelSelections(context: Context) {
    private val preferences = context.getSharedPreferences("offline-model-selections", Context.MODE_PRIVATE)

    init {
        check(Application.getProcessName().endsWith(":models"))
        if (!preferences.getBoolean(SelectionMigration.COMPLETE, false)) {
            val legacy = context.getSharedPreferences(context.getString(R.string.pref_file_settings),
                Context.MODE_PRIVATE)
            val values = SelectionMigration.merged(legacy.all, preferences.all)
            val edit = preferences.edit()
            values.forEach { (key, value) -> edit.putString(key, value) }
            check(edit.putBoolean(SelectionMigration.COMPLETE, true).commit())
        }
    }

    fun get(key: String): String? = preferences.getString(key, null)

    fun set(key: String, value: String) {
        check(preferences.edit().putString(key, value).commit())
    }

    fun removeRetired() {
        val keys = RetiredSpeechCleanup.preferenceKeys(preferences.all)
        if (keys.isEmpty()) return
        val edit = preferences.edit()
        keys.forEach { edit.remove(it) }
        check(edit.commit())
    }
}
