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

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
import app.mosaicos.models.ModelCatalog
import app.mosaicos.models.ModelSpec
import java.io.IOException
import java.io.InputStream
import java.net.Authenticator
import java.net.CookieHandler
import java.net.ResponseCache
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection

internal class ModelDownload(
    context: Context,
    private val entry: ModelSpec,
    private var offset: Long,
    private val allowMetered: Boolean,
    private val checkpoint: () -> Unit,
    private val pause: () -> Unit,
) : InputStream() {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
        ?: error("Network service unavailable")
    @Volatile private var connection: HttpsURLConnection? = null
    private var input: InputStream? = null
    private val retries = TransferRetries()
    private var bodyValidated = false
    @Volatile private var networkAvailable = false
    @Volatile private var networkMetered = true
    @Volatile private var recoveringNetwork = false
    @Volatile private var networkReported = false
    @Volatile private var activeNetwork: Network? = null
    @Volatile private var closed = false
    private val cancellation = Executors.newSingleThreadScheduledExecutor()
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (activeNetwork != null && activeNetwork != network) recoveringNetwork = true
            networkAvailable = false
            networkMetered = true
            activeNetwork = network
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            if (network != activeNetwork) return
            networkMetered = !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            networkAvailable = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            networkReported = true
            if (!networkAvailable) recoveringNetwork = true
            if (!closed && (!networkAvailable || !allowMetered && networkMetered)) {
                runCatching { connection?.disconnect() }
            }
        }

        override fun onLost(network: Network) {
            if (network != activeNetwork) return
            recoveringNetwork = true
            networkAvailable = false
            activeNetwork = null
            networkReported = true
            if (!closed) runCatching { connection?.disconnect() }
        }
    }

    init {
        check(Application.getProcessName().endsWith(":models"))
        Authenticator.setDefault(null)
        try {
            connectivity.registerDefaultNetworkCallback(networkCallback)
            cancellation.scheduleWithFixedDelay({
                if (!closed && runCatching { checkpoint() }.isFailure) {
                    runCatching { connection?.disconnect() }
                }
            }, 0, 250, TimeUnit.MILLISECONDS)
        } catch (failure: Throwable) {
            cancellation.shutdownNow()
            runCatching { connectivity.unregisterNetworkCallback(networkCallback) }
            throw failure
        }
    }

    private fun guard() {
        checkpoint()
        check(!closed)
        check(connectivity.boundNetworkForProcess == null)
    }

    private fun awaitNetwork() {
        val started = SystemClock.elapsedRealtime()
        val deadline = started + TransferPolicy.NETWORK_WAIT_MS
        var waitingForNetwork = recoveringNetwork
        while (true) {
            guard()
            // The default-network callback is asynchronous, so a missing first report is not a lost network.
            val reported = networkReported ||
                SystemClock.elapsedRealtime() - started >= TransferPolicy.FIRST_REPORT_MS
            if (networkAvailable) {
                if (allowMetered || !networkMetered) {
                    recoveringNetwork = false
                    return
                }
                if (!waitingForNetwork) {
                    pause()
                    checkpoint()
                    throw ModelDownloadUnavailable()
                }
            } else if (reported) {
                waitingForNetwork = true
                recoveringNetwork = true
            }
            if (SystemClock.elapsedRealtime() >= deadline) throw ModelDownloadUnavailable()
            Thread.sleep(if (reported) 250 else 20)
        }
    }

    private fun header(connection: HttpsURLConnection, name: String): String? {
        val values = connection.headerFields.entries.filter { it.key?.equals(name, true) == true }
            .flatMap { it.value }
        if (values.size > 1) throw RejectedDownload()
        return values.singleOrNull()
    }

    private fun connect() {
        bodyValidated = false
        awaitNetwork()
        var uri = ModelCatalog.sourceUri(entry.sourceUrl)
        val hosts = entry.redirectHosts + uri.host
        for (hop in 0..3) {
            awaitNetwork()
            check(CookieHandler.getDefault() == null && ResponseCache.getDefault() == null)
            val current = uri.toURL().openConnection() as HttpsURLConnection
            connection = current
            current.requestMethod = "GET"
            current.instanceFollowRedirects = false
            current.connectTimeout = 15_000
            current.readTimeout = 15_000
            current.useCaches = false
            current.setRequestProperty("User-Agent", "MosaicStore-Models/1")
            current.setRequestProperty("Accept", "application/octet-stream")
            current.setRequestProperty("Accept-Encoding", "identity")
            current.setRequestProperty("Cache-Control", "no-store")
            current.setRequestProperty("Connection", "close")
            if (offset > 0) current.setRequestProperty("Range", "bytes=$offset-")
            awaitNetwork()
            val code = current.responseCode
            guard()
            if (code in setOf(301, 302, 303, 307, 308)) {
                if (hop == 3) throw RejectedDownload()
                val location = header(current, "Location") ?: throw RejectedDownload()
                if (location.length > 8192) throw RejectedDownload()
                val next = ModelCatalog.sourceUri(uri.resolve(location).toString())
                if (next.host !in hosts) throw RejectedDownload()
                disconnect()
                uri = next
                continue
            }
            if (code in setOf(408, 429, 500, 502, 503, 504)) throw IOException()
            if (code != 200 && code != 206 || offset > 0 && code != 206) throw RejectedDownload()
            if (code == 206) {
                val range = header(current, "Content-Range") ?: throw RejectedDownload()
                if (range != "bytes $offset-${entry.size - 1}/${entry.size}") throw RejectedDownload()
            } else if (header(current, "Content-Range") != null) throw RejectedDownload()
            val encoding = header(current, "Content-Encoding")
            if (encoding != null && !encoding.equals("identity", true)) throw RejectedDownload()
            val length = header(current, "Content-Length")
            if (length != null && (length.toLongOrNull() != entry.size - offset ||
                    header(current, "Transfer-Encoding") != null)) throw RejectedDownload()
            bodyValidated = true
            input = current.inputStream
            return
        }
        throw RejectedDownload()
    }

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 255
    }

    override fun read(buffer: ByteArray, start: Int, length: Int): Int {
        require(start >= 0 && length >= 0 && start <= buffer.size - length)
        if (length == 0) return 0
        while (true) {
            guard()
            try {
                if (input == null) {
                    if (offset == entry.size) return -1
                    connect()
                }
                val count = input!!.read(buffer, start, minOf(length.toLong(), entry.size - offset + 1).toInt())
                guard()
                if (!networkAvailable || !allowMetered && networkMetered) throw IOException()
                if (count < 0) {
                    if (offset == entry.size) return -1
                    throw IOException()
                }
                if (count == 0) throw IOException()
                if (count.toLong() > entry.size - offset) throw RejectedDownload()
                offset += count
                retries.progressed()
                return count
            } catch (failure: IOException) {
                disconnect()
                checkpoint()
                if (failure is ModelDownloadUnavailable) throw failure
                if (TransferPolicy.rejected(failure, bodyValidated)) throw RejectedDownload()
                awaitNetwork()
                val delay = retries.nextDelay() ?: throw ModelDownloadUnavailable()
                repeat((delay / 100).toInt()) {
                    guard()
                    Thread.sleep(100)
                }
            }
        }
    }

    private fun disconnect() {
        val previous = connection
        connection = null
        input = null
        runCatching { previous?.disconnect() }
    }

    override fun close() {
        if (closed) return
        closed = true
        cancellation.shutdownNow()
        disconnect()
        runCatching { connectivity.unregisterNetworkCallback(networkCallback) }
    }
}
