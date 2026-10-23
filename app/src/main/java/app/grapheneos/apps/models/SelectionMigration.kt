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

import app.mosaicos.models.SpeechModels

internal object SelectionMigration {
    const val CHAT = "selected_chat_model"
    const val SPEECH = "selected_speech_model"
    const val EN = "selected_speech_en"
    const val COMPLETE = "selection_migration_v1"
    val keys = listOf(CHAT, SPEECH, EN)

    fun merged(legacy: Map<String, *>, local: Map<String, *>): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for (key in keys) {
            val value = if (local.containsKey(key)) local[key] else legacy[key]
            if (value is String) result[key] = value
        }
        val old = result[SPEECH]
        val language = old?.let { SpeechModels.language(it) }
        val key = if (language == "en") EN else null
        if (key != null && key !in result && old != null) result[key] = old
        return result
    }

    fun speechKey(language: String): String = when (language) {
        "en" -> EN
        else -> throw IllegalArgumentException("Unsupported speech language")
    }
}
