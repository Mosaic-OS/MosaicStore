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
)

class ModelCatalog private constructor(val recommendedChat: String, val models: List<ModelSpec>) {
    fun get(id: String): ModelSpec = models.single { it.id == id }

    companion object {
        const val CHAT_ENGINE = "llama-b10737-mosaic-1"
        const val SPEECH_ENGINE = "whisper-v1.9.3-mosaic-1"
        private val hex = Regex("[0-9a-f]{64}")

        fun read(): ModelCatalog {
            val bytes = File("/product/etc/mosaic/model-catalog.json").inputStream().use {
                it.readNBytes(16385)
            }
            require(bytes.size <= 16384)
            val root = JSONObject(bytes.toString(Charsets.UTF_8))
            require(root.getInt("schema") == 2)
            val array = root.getJSONArray("models")
            require(array.length() == 3)
            val models = (0 until array.length()).map { index ->
                val entry = array.getJSONObject(index)
                val id = entry.getString("id")
                require(id in setOf("chat-2b", "chat-4b", "speech-en"))
                val kind = if (id == "speech-en") "speech" else "chat"
                require(entry.getString("kind") == kind)
                require(entry.getInt("verityVersion") == 1)
                require(entry.getString("verityAlgorithm") == "sha256")
                require(entry.getInt("verityBlockSize") == 4096)
                require(entry.getString("veritySalt").isEmpty())
                require(entry.getString("format") == if (kind == "chat") "gguf" else "whisper-ggml")
                val name = entry.getString("file")
                require(Regex("[A-Za-z0-9._-]{1,100}").matches(name) && !name.startsWith('.'))
                val size = entry.getLong("size")
                require(size in 1..4_000_000_000L)
                val sha = entry.getString("sha256")
                val verity = entry.getString("verity")
                require(hex.matches(sha) && hex.matches(verity))
                val source = entry.getString("sourceUrl")
                val url = sourceUri(source)
                require(url.rawQuery == null)
                val redirects = entry.getJSONArray("redirectHosts")
                require(redirects.length() <= 8)
                val hosts = (0 until redirects.length()).map { redirects.getString(it) }.toSet()
                require(hosts.all { validHost(it) })
                ModelSpec(id, name, kind, size, sha, verity, entry.getString("engine"), source, hosts)
            }
            require(models.map { it.id }.toSet().size == 3)
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

        fun compatible(model: ModelSpec): Boolean = model.engine ==
            if (model.kind == "chat") CHAT_ENGINE else SPEECH_ENGINE

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
