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
package app.mosaicos.models

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCatalogTest {
    private fun entry(id: String): JSONObject {
        val speech = id == "speech-en"
        return JSONObject().apply {
            put("id", id)
            put("kind", if (speech) "speech" else "chat")
            put("verityVersion", 1)
            put("verityAlgorithm", "sha256")
            put("verityBlockSize", 4096)
            put("veritySalt", "")
            put("format", if (speech) "whisper-ggml" else "gguf")
            put("file", if (speech) "whisper-base.en-q8_0.bin" else "$id.gguf")
            put("size", 100)
            put("sha256", "a".repeat(64))
            put("verity", "b".repeat(64))
            put("engine", if (speech) ModelCatalog.SPEECH_ENGINE else ModelCatalog.CHAT_ENGINE)
            put("sourceUrl", "https://huggingface.co/test/model/resolve/" + "c".repeat(40) + "/model.bin")
            put("redirectHosts", JSONArray())
            put("languages", JSONArray(if (speech) listOf("en") else emptyList<String>()))
            if (speech) put("variant", "base.en-q8_0")
        }
    }

    private fun catalog(vararg extra: JSONObject, schema: Int = 3): ByteArray = JSONObject().apply {
        put("schema", schema)
        put("recommendedChat", "chat-2b")
        put("models", JSONArray(listOf(entry("chat-2b"), entry("chat-4b"), entry("speech-en")) + extra))
    }.toString().toByteArray(Charsets.UTF_8)

    @Test fun retiredEntriesAreDroppedBeforeValidatingTheirFields() {
        for (schema in listOf(2, 3)) {
            val retired = listOf("speech-base", "speech-small", "speech-hu", "speech-hu-small").map {
                JSONObject().put("id", it).put("kind", "invalid").put("sourceUrl", "http://invalid")
            }
            val result = ModelCatalog.read(catalog(*retired.toTypedArray(), schema = schema))
            assertEquals(listOf("chat-2b", "chat-4b", "speech-en"), result.models.map { it.id })
            for (id in listOf("speech-base", "speech-small", "speech-hu", "speech-hu-small")) {
                assertTrue(runCatching { result.get(id) }.isFailure)
                assertFalse(result.models.any { it.id == id })
            }
        }
    }

    @Test fun duplicateRetiredEntriesAreIgnoredButLiveDuplicatesAreRejected() {
        val retired = JSONObject().put("id", "speech-small")
        assertEquals(3, ModelCatalog.read(catalog(retired, retired)).models.size)
        assertTrue(runCatching { ModelCatalog.read(catalog(entry("speech-en"))) }.isFailure)
    }

    @Test fun everyOtherEntryRemainsStrictlyValidated() {
        for (bad in listOf(JSONObject().put("id", "speech-new"), JSONObject().put("id", "../speech-base"),
            entry("speech-en").put("file", "../model"), entry("speech-en").put("languages", JSONArray(listOf("hu"))),
            entry("speech-en").put("sha256", "invalid"), entry("speech-en").put("variant", "base-q8_0"))) {
            val root = JSONObject(catalog().toString(Charsets.UTF_8))
            root.getJSONArray("models").put(2, bad)
            assertTrue(runCatching { ModelCatalog.read(root.toString().toByteArray()) }.isFailure)
        }
        assertTrue(runCatching { ModelCatalog.read(ByteArray(16385)) }.isFailure)
    }
    private fun voice(): JSONObject = entry(ModelCatalog.HUNGARIAN_VOICE).apply {
        put("kind", "voice")
        put("format", "onnx")
        put("file", ModelCatalog.VOICE_FILE)
        put("size", ModelCatalog.VOICE_SIZE)
        put("sha256", ModelCatalog.VOICE_SHA256)
        put("engine", ModelCatalog.VOICE_ENGINE)
        put("sourceUrl", "https://huggingface.co" + ModelCatalog.VOICE_PATH)
        put("languages", JSONArray(listOf("hu")))
        put("variant", "hu_HU-anna-medium")
        put("downloadOnly", true)
    }

    @Test fun annaIsStrictlyPinnedDownloadOnlyAndAbsentFromStaleCatalogs() {
        val spec = ModelCatalog.read(catalog(voice())).get(ModelCatalog.HUNGARIAN_VOICE)
        assertEquals("voice", spec.kind)
        assertEquals(setOf("hu"), spec.languages)
        assertTrue(spec.downloadOnly && ModelCatalog.compatible(spec))
        assertEquals(3, ModelCatalog.read(catalog()).models.size)
        for ((key, bad) in listOf("file" to "anna.onnx", "size" to 1L, "sha256" to "a".repeat(64),
            "languages" to JSONArray(listOf("en")), "variant" to "other", "downloadOnly" to false,
            "sourceUrl" to ("https://huggingface.co" + ModelCatalog.VOICE_PATH.replace("anna/", "other/")))) {
            assertTrue(key, runCatching { ModelCatalog.read(catalog(voice().put(key, bad))) }.isFailure)
        }
        assertFalse(ModelCatalog.compatible(spec.copy(engine = "unknown")))
    }

    @Test fun pinnedNestedUrlsRejectTraversalEncodingEmptySegmentsAndQueries() {
        val root = "https://huggingface.co/test/model/resolve/" + "c".repeat(40) + "/"
        for (path in listOf("model.bin", "hu/hu_HU/anna/medium/model.onnx", "a_b-c.1/.hidden/model.bin")) {
            assertEquals(root + path, ModelCatalog.pinnedSourceUri(root + path).toString())
        }
        for (path in listOf("", "/model.bin", "a//model.bin", "a/./model.bin", "a/../model.bin",
            "./model.bin", "../model.bin", "a/", "a/%2f/b", "a/%2F/b", "a/%5c/b", "a/%2e%2e/b",
            "a\\b", "model.bin?", "model.bin?x=1", "model.bin#", "model.bin#x", "m odel.bin")) {
            assertTrue(path, runCatching { ModelCatalog.pinnedSourceUri(root + path) }.isFailure)
        }
        for (url in listOf(root.replace("https:", "http:") + "model.bin",
            root.replace("huggingface.co", "huggingface.co:443") + "model.bin",
            root.replace("huggingface.co", "huggingface.co.evil") + "model.bin",
            root.replace("test/model", "./model") + "model.bin", "\n" + root + "model.bin")) {
            assertTrue(url, runCatching { ModelCatalog.pinnedSourceUri(url) }.isFailure)
        }
    }
}
