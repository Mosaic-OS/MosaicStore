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

import java.io.EOFException
import java.io.IOException
import java.io.ByteArrayInputStream
import java.net.ProtocolException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException
import org.junit.Assert.*
import org.junit.Test

class TransferPolicyTest {
    @Test fun stalledTransfersHaveABoundedBackoff() {
        val retries = TransferRetries()
        assertEquals(listOf(1000L, 2000L, 4000L, null, null), List(5) { retries.nextDelay() })
    }

    @Test fun progressRestoresTheRetryBudgetAcrossManyInterruptions() {
        val retries = TransferRetries()
        repeat(20) {
            assertEquals(1000L, retries.nextDelay())
            assertEquals(2000L, retries.nextDelay())
            retries.progressed()
        }
        assertEquals(1000L, retries.nextDelay())
    }

    @Test fun validatedBodyFailuresKeepTheDownloadResumable() {
        for (failure in listOf(SSLException("body"), ProtocolException("body"), EOFException(),
            SocketTimeoutException(), IOException())) {
            assertFalse(failure.javaClass.name, TransferPolicy.rejected(failure, bodyValidated = true))
        }
    }

    @Test fun tlsAndProtocolFailuresBeforeValidationRemainRejected() {
        assertTrue(TransferPolicy.rejected(SSLException("handshake"), bodyValidated = false))
        assertTrue(TransferPolicy.rejected(ProtocolException("headers"), bodyValidated = false))
        assertFalse(TransferPolicy.rejected(SocketTimeoutException(), bodyValidated = false))
    }

    @Test fun policyRejectionCannotBeConvertedIntoABodyRetry() {
        assertTrue(TransferPolicy.rejected(RejectedDownload(), bodyValidated = false))
        assertTrue(TransferPolicy.rejected(RejectedDownload(), bodyValidated = true))
    }

    @Test fun aPowerLossTailIsNeverReusedBeyondItsDurableOffset() {
        val durable = TransferPolicy.SYNC_INTERVAL
        assertEquals(durable, TransferPolicy.resumeOffset(durable + 900_000L, durable))
        assertEquals(durable, TransferPolicy.resumeOffset(durable, durable))
        assertEquals(0L, TransferPolicy.resumeOffset(durable, durable + 1))
        assertEquals(0L, TransferPolicy.resumeOffset(durable, -1))
        assertEquals(0L, TransferPolicy.resumeOffset(durable, null))
    }

    @Test fun idleAndPausedScreensDoNotPollButBundleGapsDo() {
        val lifecycle = listOf(false to false, true to false, false to true, true to true, false to false)
        assertEquals(listOf(false, true, true, true, false),
            lifecycle.map { (operation, bundle) -> ModelsPolling.active(operation, bundle) })
    }

    @Test fun transientPauseContinuesTheOtherBundleItemAndLeavesTheMissingOneResumable() {
        val ids = listOf("chat-2b", "speech-en")
        val sequence = DownloadSequence(ids)
        assertEquals("speech-en", sequence.complete(success = false, paused = true))
        assertNull(sequence.complete(success = true))
        assertEquals(listOf("chat-2b"), RecommendedModels.pending(ids, setOf("speech-en")))
    }

    @Test fun explicitCancellationStillStopsTheBundle() {
        val sequence = DownloadSequence(listOf("chat-2b", "speech-en"))
        sequence.cancel()
        assertNull(sequence.complete(success = false, paused = true))
    }

    @Test fun verifiedImportsAndDownloadsSurviveConsumerFailureAndCancellation() {
        for (downloading in listOf(true, false)) {
            for (failure in listOf(IllegalStateException("consumer timed out"), IOException(),
                ModelTransferStopped(false))) {
                assertTrue(TransferPolicy.keepPartial(true, downloading, false, failure))
            }
        }
    }

    @Test fun onlyResumableUnverifiedTransfersAreRetained() {
        assertTrue(TransferPolicy.keepPartial(false, true, false, ModelDownloadUnavailable()))
        assertTrue(TransferPolicy.keepPartial(false, true, false, ModelStorageUnavailable()))
        assertTrue(TransferPolicy.keepPartial(false, true, false, InterruptedException()))
        assertTrue(TransferPolicy.keepPartial(false, true, false, ModelTransferStopped(true)))
        assertFalse(TransferPolicy.keepPartial(false, true, false, ModelTransferStopped(false)))
        assertFalse(TransferPolicy.keepPartial(false, true, false, RejectedDownload()))
        assertFalse(TransferPolicy.keepPartial(false, true, false, IllegalStateException("SHA-256 mismatch")))
        assertFalse(TransferPolicy.keepPartial(false, false, false, IOException()))
    }

    @Test fun smallNetworkReadsBecomeFullBlocksWithoutLosingTheFinalTail() {
        val expected = ByteArray(TransferPolicy.BUFFER_BYTES + 7919) { (it % 251).toByte() }
        val input = object : ByteArrayInputStream(expected) {
            override fun read(buffer: ByteArray, start: Int, length: Int): Int =
                super.read(buffer, start, minOf(length, 8192))
        }
        val buffer = ByteArray(TransferPolicy.BUFFER_BYTES)
        val result = java.io.ByteArrayOutputStream()
        var checkpoints = 0
        val counts = mutableListOf<Int>()
        while (true) {
            val count = TransferPolicy.readBlock(input, buffer) { checkpoints++ }
            if (count == 0) break
            counts += count
            result.write(buffer, 0, count)
        }
        assertEquals(listOf(TransferPolicy.BUFFER_BYTES, 7919), counts)
        assertArrayEquals(expected, result.toByteArray())
        assertTrue(checkpoints >= 130)
    }
}
