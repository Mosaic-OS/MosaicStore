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

internal object RecommendedModels {
    const val BUNDLE = "recommended"
    const val EXTRA_CHOICE = "app.mosaicos.models.extra.CHOICE"
    const val EXTRA_LANGUAGE = "app.mosaicos.models.extra.LANGUAGE"

    fun language(hint: String?, fallback: String): String? =
        if (hint == null) { if (fallback == "hu") "hu" else "en" } else hint.takeIf { it == "en" || it == "hu" }

    fun ids(chat: String, language: String): List<String> = when (language) {
        "en", "hu" -> listOf(chat, "speech-en")
        else -> emptyList()
    }

    fun choice(id: String?, language: String?, catalog: Set<String>): String? =
        id?.takeIf { language in setOf("en", "hu") &&
            (it == BUNDLE || it == app.mosaicos.models.ModelCatalog.HUNGARIAN_VOICE || it in catalog &&
                (it in setOf("chat-2b", "chat-4b") || app.mosaicos.models.SpeechModels.language(it) != null) ||
                app.mosaicos.models.SpeechModels.language(it) == language) }

    fun pending(ids: List<String>, ready: Set<String>): List<String> = ids.filterNot { it in ready }

    fun authorized(requested: List<String>, recommended: List<String>): Boolean =
        requested.isNotEmpty() && requested == recommended.filter { it in requested }
}

internal class DownloadSequence(ids: List<String>) {
    private val remaining = ArrayDeque(ids)
    val current: String? get() = remaining.firstOrNull()

    init { require(ids.isNotEmpty() && ids.size <= 2 && ids.distinct() == ids) }

    fun complete(success: Boolean, paused: Boolean = false): String? {
        if (success || paused) remaining.removeFirstOrNull() else remaining.clear()
        return current
    }

    fun cancel() { remaining.clear() }
}
