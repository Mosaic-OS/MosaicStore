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

import java.io.IOException
import java.lang.reflect.Proxy
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SecureDirectoryStream
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.BasicFileAttributes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RetiredSpeechCleanupTest {
    private class Entry(
        val directory: Boolean = false,
        val symlink: Boolean = false,
        val children: MutableMap<String, Entry> = linkedMapOf(),
        var failDelete: Boolean = false,
        var replaceWithLinkOnOpen: Boolean = false,
    )

    private fun directory(vararg entries: Pair<String, Entry>) = Entry(true, children = linkedMapOf(*entries))

    private fun attributeView(entry: Entry): BasicFileAttributeView {
        val attributes = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(BasicFileAttributes::class.java)) {
                _, method, _ ->
            when (method.name) {
                "isDirectory" -> entry.directory && !entry.symlink
                else -> error("Unexpected attribute")
            }
        }
        return Proxy.newProxyInstance(javaClass.classLoader, arrayOf(BasicFileAttributeView::class.java)) {
                _, method, _ ->
            when (method.name) {
                "readAttributes" -> attributes
                else -> error("Unexpected attribute operation")
            }
        } as BasicFileAttributeView
    }

    @Suppress("UNCHECKED_CAST")
    private fun stream(entry: Entry): SecureDirectoryStream<Path> =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SecureDirectoryStream::class.java)) {
                _, method, args ->
            when (method.name) {
                "close" -> null
                "iterator" -> entry.children.keys.toList().map { Paths.get("ignored-prefix").resolve(it) }.iterator()
                "newDirectoryStream", "getFileAttributeView", "deleteFile", "deleteDirectory" -> {
                    val path = args!![0] as Path
                    assertFalse(path.isAbsolute)
                    assertEquals(1, path.nameCount)
                    assertTrue(path.toString() !in setOf(".", ".."))
                    val child = entry.children[path.toString()] ?: throw IOException("Absent")
                    when (method.name) {
                        "getFileAttributeView" -> {
                            assertTrue(NOFOLLOW_LINKS in (args[2] as Array<*>))
                            attributeView(child)
                        }
                        "newDirectoryStream" -> {
                            assertTrue(NOFOLLOW_LINKS in (args[1] as Array<*>))
                            if (child.symlink || !child.directory || child.replaceWithLinkOnOpen) {
                                throw IOException("Not a directory")
                            }
                            stream(child)
                        }
                        else -> {
                            if (child.failDelete || method.name == "deleteDirectory" && child.children.isNotEmpty()) {
                                throw IOException("Unavailable")
                            }
                            entry.children.remove(path.toString())
                            null
                        }
                    }
                }
                else -> error("Unexpected filesystem operation")
            }
        } as SecureDirectoryStream<Path>

    @Test fun onlyRetiredDirectoriesAndAllTheirTransferArtifactsAreRemoved() {
        val payload = directory("hash.model" to Entry(), "import.part" to Entry(), "previous.model" to Entry(),
            "hash.use" to Entry(), "hash.bad.bak" to Entry(), "hash.use.new" to Entry(),
            "temporary" to directory("resume" to Entry(), "pause" to Entry(), "consent" to Entry()))
        val keep = directory("model" to Entry())
        val root = directory("speech-base" to payload, "speech-small" to directory("partial" to Entry()),
            "speech-en-small" to keep, "chat-2b" to keep, "speech-base-extra" to keep)
        val base = directory("offline-models" to root, "unrelated" to keep)
        RetiredSpeechCleanup.removeModels(stream(base))
        assertEquals(setOf("speech-en-small", "chat-2b", "speech-base-extra"), root.children.keys)
        assertEquals(setOf("model"), keep.children.keys)
        assertTrue(base.children.containsKey("unrelated"))
        RetiredSpeechCleanup.removeModels(stream(base))
        assertEquals(3, root.children.size)
    }

    @Test fun hungarianSpeechFilesAndSelectionsAreRetiredWhileAnnaAndEnglishRemain() {
        val keep = directory("model" to Entry())
        val root = directory("speech-hu" to directory("model" to Entry()),
            "speech-hu-small" to directory("partial" to Entry()), "voice-hu-anna" to keep, "speech-en" to keep)
        RetiredSpeechCleanup.removeModels(stream(directory("offline-models" to root)))
        assertEquals(setOf("voice-hu-anna", "speech-en"), root.children.keys)
        for (id in listOf("speech-hu", "speech-hu-small")) {
            val values = mapOf("selected_speech_model" to id, "selected_speech_hu" to id,
                "selected_speech_en" to "speech-en-small", "selected_chat_model" to "chat-4b")
            assertEquals(setOf("selected_speech_model", "selected_speech_hu"),
                RetiredSpeechCleanup.preferenceKeys(values))
        }
    }

    @Test fun symbolicLinksAreUnlinkedWithoutTraversingTargets() {
        val target = directory("another-model" to Entry())
        val link = Entry(directory = true, symlink = true, children = target.children)
        val root = directory("speech-base" to link, "speech-small" to directory("link" to link))
        RetiredSpeechCleanup.removeModels(stream(directory("offline-models" to root)))
        assertTrue(root.children.isEmpty())
        assertEquals(setOf("another-model"), target.children.keys)
        val linkedRoot = Entry(directory = true, symlink = true, children = root.children)
        root.children["speech-base"] = Entry()
        RetiredSpeechCleanup.removeModels(stream(directory("offline-models" to linkedRoot)))
        assertTrue(root.children.containsKey("speech-base"))
    }

    @Test fun failuresRetryAndDirectoryReplacementCannotFollowALink() {
        val file = Entry(failDelete = true)
        val replaced = directory("target" to Entry()).apply { replaceWithLinkOnOpen = true }
        val root = directory("speech-base" to directory("partial" to file), "speech-small" to replaced)
        val base = directory("offline-models" to root)
        RetiredSpeechCleanup.removeModels(stream(base))
        assertEquals(2, root.children.size)
        assertEquals(setOf("target"), replaced.children.keys)
        file.failDelete = false
        replaced.replaceWithLinkOnOpen = false
        RetiredSpeechCleanup.removeModels(stream(base))
        assertTrue(root.children.isEmpty())
    }

    @Test fun stalePreferencesAreRemovedWithoutClearingLiveChoices() {
        val values = mapOf("hungarian_speech_migrated" to true, "selected_speech_model" to "speech-base",
            "selected_speech_hu" to "speech-small", "selected_speech_en" to "speech-en-small",
            "selected_speech_future" to "speech-base", "selected_chat_model" to "chat-2b",
            "unrelated" to "speech-base")
        assertEquals(setOf("hungarian_speech_migrated", "selected_speech_model", "selected_speech_hu",
            "selected_speech_future"), RetiredSpeechCleanup.preferenceKeys(values))
        assertEquals(setOf("selected_speech_hu"),
            RetiredSpeechCleanup.preferenceKeys(mapOf("selected_speech_hu" to "speech-hu-small")))
    }

    @Test fun onlyExactRetiredNotificationsInModelSlotsAreMatched() {
        for (size in listOf("base", "small")) {
            assertTrue(RetiredSpeechCleanup.notification(2002, "Whisper $size — English and Hungarian"))
            assertTrue(RetiredSpeechCleanup.notification(2003, "Whisper $size – angol és magyar"))
            assertFalse(RetiredSpeechCleanup.notification(99, "Whisper $size – angol és magyar"))
        }
        assertFalse(RetiredSpeechCleanup.notification(2002, "Whisper small – magyar"))
        assertFalse(RetiredSpeechCleanup.notification(2002, "Whisper base.en — English"))
        assertFalse(RetiredSpeechCleanup.notification(2002, null))
    }
}
