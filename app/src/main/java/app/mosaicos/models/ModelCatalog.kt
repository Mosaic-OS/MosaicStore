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

import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.security.MessageDigest

data class ModelSpec(
    val id: String,
    val file: String,
    val kind: String,
    val size: Long,
    val sha256: String,
    val verity: String,
    val engine: String,
    val sourceUrl: String,
    val redirectHosts: Set<String>,
    val languages: Set<String> = emptySet(),
    val variant: String = "",
    val downloadOnly: Boolean = false,
)

class ModelCatalog private constructor(val recommendedChat: String, val models: List<ModelSpec>) {
    fun get(id: String): ModelSpec = models.single { it.id == id }

    companion object {
        const val CHAT_ENGINE = "llama-b10737-mosaic-1"
        const val SPEECH_ENGINE = "whisper-v1.9.3-mosaic-1"
        const val VOICE_ENGINE = "piper-onnx-1"
        const val HUNGARIAN_VOICE = "voice-hu-anna"
        const val VOICE_FILE = "piper-hu_HU-anna-medium.onnx"
        const val VOICE_SIZE = 63_201_294L
        const val VOICE_SHA256 = "968c0c3a66cb667811242cc88653bff9247395fc7a0517fbeef7d8c08cdae62a"
        const val VOICE_COMMIT = "c10ece1aade47bb51c153c893d14e5bf8e5b7117"
        const val VOICE_PATH = "/rhasspy/piper-voices/resolve/" + VOICE_COMMIT +
            "/hu/hu_HU/anna/medium/hu_HU-anna-medium.onnx"
        private val hex = Regex("[0-9a-f]{64}")
        private val speechVariants = mapOf(
            "speech-en" to "base.en-q8_0", "speech-en-small" to "small.en-q8_0",
        )

        fun read(): ModelCatalog {
            val bytes = File("/product/etc/mosaic/model-catalog.json").inputStream().use {
                it.readNBytes(16385)
            }
            return read(bytes)
        }

        internal fun read(bytes: ByteArray): ModelCatalog {
            require(bytes.size <= 16384)
            val root = JSONObject(bytes.toString(Charsets.UTF_8))
            val version = root.getInt("schema")
            require(version in 2..3)
            val array = root.getJSONArray("models")
            require(array.length() in 3..32)
            val models = (0 until array.length()).mapNotNull { index ->
                val entry = array.getJSONObject(index)
                val id = entry.getString("id")
                if (id in SpeechModels.retiredIds) return@mapNotNull null
                require(id in speechVariants || id in setOf("chat-2b", "chat-4b", HUNGARIAN_VOICE))
                val kind = when {
                    id == HUNGARIAN_VOICE -> "voice"
                    id.startsWith("speech-") -> "speech"
                    else -> "chat"
                }
                require(entry.getString("kind") == kind)
                require(entry.getInt("verityVersion") == 1)
                require(entry.getString("verityAlgorithm") == "sha256")
                require(entry.getInt("verityBlockSize") == 4096)
                require(entry.getString("veritySalt").isEmpty())
                require(entry.getString("format") == when (kind) {
                    "chat" -> "gguf"
                    "voice" -> "onnx"
                    else -> "whisper-ggml"
                })
                val name = entry.getString("file")
                require(Regex("[A-Za-z0-9._-]{1,100}").matches(name) && !name.startsWith('.'))
                val size = entry.getLong("size")
                require(size in 1..4_000_000_000L)
                val sha = entry.getString("sha256")
                val verity = entry.getString("verity")
                require(hex.matches(sha) && hex.matches(verity))
                val source = entry.getString("sourceUrl")
                val url = pinnedSourceUri(source)
                val redirects = entry.getJSONArray("redirectHosts")
                require(redirects.length() <= 8)
                val hosts = (0 until redirects.length()).map { redirects.getString(it) }.toSet()
                require(hosts.all { validHost(it) })
                val languageArray = entry.optJSONArray("languages")
                val languages = if (languageArray != null) (0 until languageArray.length()).map {
                    languageArray.getString(it)
                }.toSet() else if (id == "speech-en" && version == 2) setOf("en") else emptySet()
                val variant = entry.optString("variant", if (id == "speech-en") "base.en-q8_0" else "")
                if (kind == "speech") {
                    val expectedLanguages = setOf(checkNotNull(SpeechModels.language(id)))
                    require(languages == expectedLanguages && languageArray?.length() != 0)
                    require(languageArray == null || languageArray.length() == languages.size)
                    require(variant == speechVariants[id] && name == "whisper-$variant.bin")
                    require(entry.optBoolean("downloadOnly", false) || id == "speech-en")
                } else if (kind == "voice") {
                    require(languages == setOf("hu") && languageArray?.length() == 1)
                    require(variant == "hu_HU-anna-medium" && name == VOICE_FILE)
                    require(size == VOICE_SIZE && sha == VOICE_SHA256 && url.rawPath == VOICE_PATH)
                    require(entry.getBoolean("downloadOnly"))
                } else require(languages.isEmpty())
                ModelSpec(id, name, kind, size, sha, verity, entry.getString("engine"), source, hosts,
                    languages, variant, entry.optBoolean("downloadOnly", false))
            }
            require(models.map { it.id }.toSet().size == models.size)
            require(models.map { it.id }.containsAll(listOf("chat-2b", "chat-4b", "speech-en")))
            val recommended = root.getString("recommendedChat")
            require(recommended in setOf("chat-2b", "chat-4b"))
            return ModelCatalog(recommended, models)
        }

        private fun validHost(host: String): Boolean = host.length <= 253 &&
            host.contains('.') && host.split('.').all {
                Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?").matches(it)
            } && host.any { it in 'a'..'z' }

        fun sourceUri(value: String): URI {
            require(value.length in 1..8192 && value.all { it.code in 33..126 })
            val uri = URI(value)
            require(uri.scheme == "https" && uri.rawUserInfo == null && uri.rawFragment == null)
            require(uri.port == -1 || uri.port == 443)
            require(uri.host != null && validHost(uri.host))
            return uri
        }

        fun pinnedSourceUri(value: String): URI {
            val uri = sourceUri(value)
            require(uri.rawAuthority == "huggingface.co" && uri.rawQuery == null)
            require(Regex("/[A-Za-z0-9._-]+/[A-Za-z0-9._-]+/resolve/[0-9a-f]{40}/" +
                "[A-Za-z0-9._-]+(?:/[A-Za-z0-9._-]+)*").matches(uri.rawPath))
            require(uri.rawPath.split('/').drop(1).none { it == "." || it == ".." })
            return uri
        }

        fun compatible(model: ModelSpec): Boolean = model.engine == when (model.kind) {
            "chat" -> CHAT_ENGINE
            "speech" -> SPEECH_ENGINE
            "voice" -> VOICE_ENGINE
            else -> null
        }

        fun verify(fd: ParcelFileDescriptor, model: ModelSpec) {
            require(compatible(model))
            val info = Os.fstat(fd.fileDescriptor)
            require(OsConstants.S_ISREG(info.st_mode) && info.st_size == model.size)
            require(Os.fcntlInt(fd.fileDescriptor, OsConstants.F_GETFL, 0) and
                OsConstants.O_ACCMODE == OsConstants.O_RDONLY)
            val measured = ModelVerity.measure(fd.fd) ?: error("Model has no usable fs-verity digest")
            require(MessageDigest.isEqual(measured, decodeHex(model.verity)))
        }

        fun decodeHex(value: String): ByteArray = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        fun sha256(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(value).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
