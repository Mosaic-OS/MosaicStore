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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecommendedModelsTest {
    private val catalog = setOf("chat-2b", "chat-4b", "speech-en", "speech-en-small")

    @Test fun languageHintsAreNarrowAndDefaultsFollowLocale() {
        assertEquals("hu", RecommendedModels.language(null, "hu"))
        assertEquals("en", RecommendedModels.language(null, "de"))
        assertEquals("en", RecommendedModels.language("en", "hu"))
        assertNull(RecommendedModels.language("auto", "hu"))
        assertNull(RecommendedModels.language("HU", "en"))
    }

    @Test fun recommendationsUseDeviceTierAndCompatibleVoiceInput() {
        assertEquals(listOf("chat-2b", "speech-en"), RecommendedModels.ids("chat-2b", "en"))
        assertEquals(listOf("chat-4b", "speech-en"), RecommendedModels.ids("chat-4b", "hu"))
        assertTrue(RecommendedModels.ids("chat-2b", "auto").isEmpty())
    }

    @Test fun linksAcceptOnlyCatalogChoicesOrTheFixedBundle() {
        assertEquals("speech-en-small", RecommendedModels.choice("speech-en-small", "en", catalog))
        assertNull(RecommendedModels.choice("speech-hu-small", "hu", emptySet()))
        assertNull(RecommendedModels.choice("speech-hu", "hu", emptySet()))
        assertNull(RecommendedModels.choice("speech-hu-small", "en", emptySet()))
        for (retired in listOf("speech-base", "speech-small", "speech-hu", "speech-hu-small")) {
            assertNull(RecommendedModels.choice(retired, "hu", catalog + retired))
            assertNull(RecommendedModels.choice(retired, "en", catalog + retired))
        }
        assertEquals("recommended", RecommendedModels.choice("recommended", "en", catalog))
        assertNull(RecommendedModels.choice("https://example.invalid/model", "en", catalog))
        assertNull(RecommendedModels.choice("speech-new", "en", catalog))
        assertNull(RecommendedModels.choice("speech-en", "de", catalog))
        assertNull(RecommendedModels.choice("speech-en", null, catalog))
    }

    @Test fun partialSuccessRetriesOnlyTheMissingFile() {
        val recommended = RecommendedModels.ids("chat-2b", "hu")
        assertEquals(listOf("speech-en"), RecommendedModels.pending(recommended, setOf("chat-2b")))
        assertEquals(listOf("chat-2b"), RecommendedModels.pending(recommended, setOf("speech-en")))
        assertTrue(RecommendedModels.pending(recommended, recommended.toSet()).isEmpty())
    }

    @Test fun consentCannotAddReorderOrDuplicateFiles() {
        val ids = RecommendedModels.ids("chat-2b", "hu")
        assertTrue(RecommendedModels.authorized(ids, ids))
        assertTrue(RecommendedModels.authorized(listOf("speech-en"), ids))
        for (bad in listOf(emptyList(), ids.reversed(), ids + "speech-en-small", ids + "chat-2b")) {
            assertFalse(RecommendedModels.authorized(bad, ids))
        }
    }

    @Test fun onlySuccessAdvancesTheSequence() {
        val sequence = DownloadSequence(listOf("chat-2b", "speech-en"))
        assertEquals("chat-2b", sequence.current)
        assertEquals("speech-en", sequence.complete(true))
        assertNull(sequence.complete(true))
        assertNull(sequence.current)
    }

    @Test fun failureStopsWithoutDownloadingTheNextItem() {
        val sequence = DownloadSequence(listOf("chat-2b", "speech-en"))
        assertNull(sequence.complete(false))
        assertNull(sequence.complete(true))
    }

    @Test fun cancelOrPauseCannotStartTheNextFile() {
        val sequence = DownloadSequence(listOf("chat-2b", "speech-en"))
        sequence.cancel()
        assertNull(sequence.complete(true))
        assertNull(sequence.current)
    }
}
