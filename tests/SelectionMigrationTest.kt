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

import org.junit.Assert.*
import org.junit.Test

class SelectionMigrationTest {
    @Test fun liveKeysSurviveMigrationWithoutModifyingTheLegacyMap() {
        val legacy = mapOf(SelectionMigration.CHAT to "chat-4b", SelectionMigration.SPEECH to "speech-en",
            SelectionMigration.EN to "speech-en-small")
        val snapshot = legacy.toMap()
        val migrated = SelectionMigration.merged(legacy, emptyMap<String, Any>())
        assertEquals(snapshot, migrated)
        assertEquals(snapshot, legacy)
    }

    @Test fun legacySpeechChoiceFillsOnlyItsOwnLanguage() {
        for ((id, key) in listOf("speech-en" to SelectionMigration.EN)) {
            val migrated = SelectionMigration.merged(mapOf(SelectionMigration.SPEECH to id), emptyMap<String, Any>())
            assertEquals(id, migrated[key])
            assertEquals(id, migrated[SelectionMigration.SPEECH])
            assertEquals(2, migrated.size)
        }
    }

    @Test fun languageSpecificChoiceWinsOverTheOlderCombinedChoice() {
        val migrated = SelectionMigration.merged(mapOf(SelectionMigration.SPEECH to "speech-en",
            SelectionMigration.EN to "speech-en-small"), emptyMap<String, Any>())
        assertEquals("speech-en-small", migrated[SelectionMigration.EN])
        assertEquals("speech-en", migrated[SelectionMigration.SPEECH])
    }

    @Test fun aRetriedMigrationCannotOverwriteANewLocalSelection() {
        val legacy = mapOf(SelectionMigration.CHAT to "chat-2b", SelectionMigration.EN to "speech-en")
        val local = mapOf(SelectionMigration.CHAT to "chat-4b", SelectionMigration.EN to "speech-en-small")
        val first = SelectionMigration.merged(legacy, local)
        assertEquals(local, first)
        assertEquals(first, SelectionMigration.merged(legacy, first))
    }

    @Test fun absentRetiredAndUnknownSelectionsAreNotReinterpretedAsAnotherLanguage() {
        assertTrue(SelectionMigration.merged(emptyMap<String, Any>(), emptyMap<String, Any>()).isEmpty())
        for (old in listOf("speech-base", "speech-small", "speech-hu", "speech-hu-small", "future-model")) {
            val migrated = SelectionMigration.merged(mapOf(SelectionMigration.SPEECH to old), emptyMap<String, Any>())
            assertEquals(mapOf(SelectionMigration.SPEECH to old), migrated)
        }
    }

    @Test fun retiredHungarianKeyNeverEntersNewSelections() {
        val legacy = mapOf("selected_speech_hu" to "speech-hu-small", SelectionMigration.EN to "speech-en")
        assertEquals(mapOf(SelectionMigration.EN to "speech-en"),
            SelectionMigration.merged(legacy, emptyMap<String, Any>()))
        assertTrue(runCatching { SelectionMigration.speechKey("hu") }.isFailure)
    }

    @Test fun unrelatedSettingsNeverEnterTheModelsPreferences() {
        val migrated = SelectionMigration.merged(mapOf("auto_update" to true, "repo_timestamp" to 123L,
            SelectionMigration.CHAT to "chat-2b"), emptyMap<String, Any>())
        assertEquals(mapOf(SelectionMigration.CHAT to "chat-2b"), migrated)
    }

    @Test(expected = IllegalArgumentException::class)
    fun unsupportedLanguageCannotWriteAPreference() {
        SelectionMigration.speechKey("de")
    }
}
