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
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SecureDirectoryStream
import java.nio.file.attribute.BasicFileAttributeView

internal object RetiredSpeechCleanup {
    fun preferenceKeys(values: Map<String, *>): Set<String> = values.filter { (key, value) ->
        key == "hungarian_speech_migrated" || key == "selected_speech_hu" ||
            key.startsWith("selected_speech_") && value in SpeechModels.retiredIds
    }.keys

    // Older transfer notifications identify the model only through their localized title.
    fun notification(slot: Int, title: String?): Boolean = slot in setOf(2002, 2003) &&
        SpeechModels.retiredIds.any {
            val name = "Whisper " + it.removePrefix("speech-")
            title == "$name — English and Hungarian" || title == "$name – angol és magyar"
        }

    fun removeFiles(noBackup: Path) {
        runCatching {
            Files.newDirectoryStream(noBackup.parent).use { parent ->
                if (parent !is SecureDirectoryStream<Path>) return
                parent.newDirectoryStream(noBackup.fileName, NOFOLLOW_LINKS).use { removeModels(it) }
            }
        }
    }

    fun removeModels(noBackup: SecureDirectoryStream<Path>) {
        runCatching {
            noBackup.newDirectoryStream(Paths.get("offline-models"), NOFOLLOW_LINKS).use { root ->
                SpeechModels.retiredIds.forEach { removeEntry(root, Paths.get(it)) }
            }
        }
    }

    private fun removeEntry(parent: SecureDirectoryStream<Path>, name: Path) {
        runCatching {
            val attributes = parent.getFileAttributeView(name, BasicFileAttributeView::class.java, NOFOLLOW_LINKS)
                .readAttributes()
            if (attributes.isDirectory) {
                parent.newDirectoryStream(name, NOFOLLOW_LINKS).use { directory ->
                    directory.forEach { removeEntry(directory, it.fileName) }
                }
                parent.deleteDirectory(name)
            } else parent.deleteFile(name)
        }
    }
}
