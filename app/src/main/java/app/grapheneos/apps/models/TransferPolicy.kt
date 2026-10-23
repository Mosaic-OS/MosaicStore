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
import java.io.InputStream
import java.net.ProtocolException
import javax.net.ssl.SSLException

internal class ModelTransferStopped(val keepPartial: Boolean) : IOException()
internal class ModelDownloadUnavailable : IOException()
internal class ModelStorageUnavailable(cause: Throwable? = null) : IOException(cause)
internal class RejectedDownload : IOException()

internal class TransferRetries {
    private var failures = 0

    fun progressed() { failures = 0 }

    fun nextDelay(): Long? {
        if (failures == 3) return null
        return 1_000L shl failures++
    }
}

internal object TransferPolicy {
    const val BUFFER_BYTES = 1024 * 1024
    const val SPACE_INTERVAL = 8L * BUFFER_BYTES
    const val SYNC_INTERVAL = 256L * BUFFER_BYTES
    const val NETWORK_WAIT_MS = 10 * 60 * 1000L
    const val FIRST_REPORT_MS = 2_000L

    fun rejected(failure: IOException, bodyValidated: Boolean): Boolean =
        failure is RejectedDownload ||
            !bodyValidated && (failure is SSLException || failure is ProtocolException)

    fun resumeOffset(size: Long, recorded: Long?): Long =
        if (recorded != null && recorded in 0..size) recorded else 0L

    fun keepPartial(verified: Boolean, downloading: Boolean, published: Boolean, failure: Throwable): Boolean =
        !published && (verified || downloading &&
            (failure is ModelTransferStopped && failure.keepPartial ||
                failure is InterruptedException ||
                failure is IOException && failure !is RejectedDownload && failure !is ModelTransferStopped))

    fun readBlock(input: InputStream, buffer: ByteArray, checkpoint: () -> Unit): Int {
        var count = 0
        while (count < buffer.size) {
            checkpoint()
            val read = input.read(buffer, count, buffer.size - count)
            if (read < 0) break
            check(read > 0)
            count += read
        }
        return count
    }
}

internal object ModelsPolling {
    fun active(operation: Boolean, bundle: Boolean): Boolean = operation || bundle
}
