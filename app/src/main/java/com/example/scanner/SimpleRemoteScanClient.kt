package com.example.scanner

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.example.model.ScanParameters
import com.example.printer.network.DeviceIdentityHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

data class DownloadedScanPage(
    val pageIndex: Int,
    val bitmap: Bitmap,
    val sha256: String,
    val sizeBytes: Long,
)

/**
 * Lightweight, short-request HTTP client for the `/api/scan/...` async polling & fail-safe handover API.
 * Every HTTP call is fast and bounded — never holds an open socket while the scanner hardware moves.
 */
object SimpleRemoteScanClient {

    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val SHORT_READ_TIMEOUT_MS = 15_000
    private const val DOWNLOAD_READ_TIMEOUT_MS = 60_000
    private const val START_RETRY_DELAY_MS = 2_500L

    suspend fun probeCapabilities(
        host: String,
        port: Int,
        timeoutMs: Int = 5_000,
    ): SimpleScanCapabilities? = withContext(Dispatchers.IO) {
        runCatching {
            val conn = openConn("http://$host:$port/api/scan/capabilities", "GET", timeoutMs, timeoutMs)
            try {
                if (conn.responseCode !in 200..299) return@runCatching null
                val body = conn.inputStream.readBytes().toString(Charsets.UTF_8)
                SimpleScanCapabilities.fromJson(JSONObject(body))
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
    }

    suspend fun startScan(
        host: String,
        port: Int,
        deviceId: String,
        clientName: String,
        params: ScanParameters,
        onRetryStatus: ((String) -> Unit)? = null,
    ): Result<SimpleScanJobState> = withContext(Dispatchers.IO) {
        val payload = SimpleScanProtocol.buildStartRequestJson(deviceId, clientName, params)
            .toByteArray(Charsets.UTF_8)
        var lastError: Throwable? = null

        for (attempt in 1..3) {
            try {
                val conn = openConn(
                    "http://$host:$port/api/scan/start",
                    "POST",
                    CONNECT_TIMEOUT_MS,
                    SHORT_READ_TIMEOUT_MS,
                ).apply {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setFixedLengthStreamingMode(payload.size)
                }
                val (code, respBody) = try {
                    conn.outputStream.use { it.write(payload) }
                    conn.responseCode to readBodyText(conn)
                } finally {
                    conn.disconnect()
                }

                if (code in 200..299) {
                    return@withContext Result.success(SimpleScanJobState.fromJson(JSONObject(respBody)))
                }

                val errMsg = runCatching { JSONObject(respBody).optString("error") }
                    .getOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?: "Host rejected scan request (HTTP $code)"
                lastError = IllegalStateException(errMsg)

                if ((code == 409 || code == 503) && attempt < 3) {
                    countdownRetryDelay(
                        delayMs = START_RETRY_DELAY_MS,
                        prefix = "Host busy — retrying in",
                        nextAttempt = attempt + 1,
                        maxAttempts = 3,
                        onStatus = onRetryStatus,
                    )
                    continue
                }
                break
            } catch (io: IOException) {
                lastError = io
                if (attempt < 3) {
                    countdownRetryDelay(
                        delayMs = START_RETRY_DELAY_MS,
                        prefix = "Host timed out — retrying in",
                        nextAttempt = attempt + 1,
                        maxAttempts = 3,
                        onStatus = onRetryStatus,
                    )
                    continue
                }
                break
            } catch (t: Throwable) {
                lastError = t
                break
            }
        }

        Result.failure(lastError ?: IllegalStateException("Host rejected scan request"))
    }

    private suspend fun countdownRetryDelay(
        delayMs: Long,
        prefix: String,
        nextAttempt: Int,
        maxAttempts: Int,
        onStatus: ((String) -> Unit)?,
    ) {
        val totalSec = ((delayMs + 999L) / 1000L).toInt().coerceAtLeast(1)
        for (sec in totalSec downTo 1) {
            onStatus?.invoke("$prefix ${sec}s (attempt $nextAttempt/$maxAttempts)...")
            delay(1_000L)
        }
    }

    suspend fun getJobProgress(
        host: String,
        port: Int,
        jobId: String,
        deviceId: String,
    ): Result<SimpleScanJobState> = withContext(Dispatchers.IO) {
        runCatching {
            val encDev = URLEncoder.encode(deviceId, "UTF-8")
            val encJob = URLEncoder.encode(jobId, "UTF-8")
            val conn = openConn(
                "http://$host:$port/api/scan/jobs/$encJob/progress?deviceId=$encDev",
                "GET",
                CONNECT_TIMEOUT_MS,
                SHORT_READ_TIMEOUT_MS,
            )
            try {
                val code = conn.responseCode
                val body = readBodyText(conn)
                if (code !in 200..299) {
                    val errMsg = runCatching { JSONObject(body).optString("error") }
                        .getOrNull()
                        ?.takeIf { it.isNotBlank() }
                        ?: "Progress check failed (HTTP $code)"
                    throw IllegalStateException(errMsg)
                }
                SimpleScanJobState.fromJson(JSONObject(body))
            } finally {
                conn.disconnect()
            }
        }
    }

    suspend fun getPendingJobs(
        host: String,
        port: Int,
        deviceId: String,
    ): Result<List<SimpleScanJobState>> = withContext(Dispatchers.IO) {
        runCatching {
            val encDev = URLEncoder.encode(deviceId, "UTF-8")
            val conn = openConn(
                "http://$host:$port/api/scan/pending?deviceId=$encDev",
                "GET",
                CONNECT_TIMEOUT_MS,
                SHORT_READ_TIMEOUT_MS,
            )
            try {
                val code = conn.responseCode
                val body = readBodyText(conn)
                if (code !in 200..299) {
                    throw IllegalStateException("Pending check failed (HTTP $code)")
                }
                val root = JSONObject(body)
                val arr = root.optJSONArray("jobs")
                buildList {
                    if (arr != null) {
                        for (i in 0 until arr.length()) {
                            val obj = arr.optJSONObject(i) ?: continue
                            add(SimpleScanJobState.fromJson(obj))
                        }
                    }
                }
            } finally {
                conn.disconnect()
            }
        }
    }

    /**
     * Downloads a single scanned page from the host into a temporary file, verifies BOTH exact
     * byte count and SHA-256 checksum, and decodes it to a valid [Bitmap].
     *
     * Does NOT send ACK — caller MUST persist locally first and then call [ackPage] so host
     * deletion only happens after verified fail-safe handover!
     */
    suspend fun downloadAndVerifyPage(
        host: String,
        port: Int,
        jobId: String,
        pageMeta: SimpleScanPageMeta,
        deviceId: String,
        cacheDir: File,
    ): Result<DownloadedScanPage> = withContext(Dispatchers.IO) {
        val tempFile = File(cacheDir, "dl_${jobId}_p${pageMeta.index}_${System.nanoTime()}.jpg")
        try {
            val encDev = URLEncoder.encode(deviceId, "UTF-8")
            val encJob = URLEncoder.encode(jobId, "UTF-8")
            val conn = openConn(
                "http://$host:$port/api/scan/jobs/$encJob/page/${pageMeta.index}?deviceId=$encDev",
                "GET",
                CONNECT_TIMEOUT_MS,
                DOWNLOAD_READ_TIMEOUT_MS,
            )
            try {
                val code = conn.responseCode
                if (code !in 200..299) {
                    val err = readBodyText(conn)
                    throw IllegalStateException("Page download failed (HTTP $code): $err")
                }

                val headerSha = conn.getHeaderField("X-Scan-Sha256")?.trim().orEmpty()
                val expectedSha = pageMeta.sha256.ifBlank { headerSha }
                val expectedBytes = pageMeta.sizeBytes.takeIf { it > 0L } ?: conn.contentLengthLong

                val digest = MessageDigest.getInstance("SHA-256")
                var totalRead = 0L
                conn.inputStream.use { input ->
                    BufferedOutputStream(FileOutputStream(tempFile), 64 * 1024).use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            out.write(buf, 0, n)
                            digest.update(buf, 0, n)
                            totalRead += n
                        }
                        out.flush()
                    }
                }

                if (totalRead <= 0L) {
                    throw IllegalStateException("Received empty scan file for page ${pageMeta.index}")
                }
                if (expectedBytes > 0L && totalRead != expectedBytes) {
                    throw IllegalStateException(
                        "Incomplete transfer for page ${pageMeta.index} ($totalRead / $expectedBytes bytes)",
                    )
                }
                val actualSha = digest.digest().joinToString("") { "%02x".format(it) }
                if (expectedSha.isNotBlank() && !actualSha.equals(expectedSha, ignoreCase = true)) {
                    throw IllegalStateException("Checksum mismatch on page ${pageMeta.index} — keeping host copy")
                }

                val bitmap = decodeFileSafely(tempFile)
                    ?: throw IllegalStateException("Corrupted scan image for page ${pageMeta.index}")

                Result.success(
                    DownloadedScanPage(
                        pageIndex = pageMeta.index,
                        bitmap = bitmap,
                        sha256 = actualSha,
                        sizeBytes = totalRead,
                    ),
                )
            } finally {
                conn.disconnect()
            }
        } catch (t: Throwable) {
            Result.failure(t)
        } finally {
            runCatching { tempFile.delete() }
        }
    }

    /**
     * Confirms verified receipt of [pageIndex] so the host can safely delete its vault copy.
     */
    suspend fun ackPage(
        host: String,
        port: Int,
        jobId: String,
        deviceId: String,
        pageIndex: Int,
        sha256: String,
        receivedBytes: Long,
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        val encJob = URLEncoder.encode(jobId, "UTF-8")
        val payload = JSONObject().apply {
            put("deviceId", deviceId)
            put("pageIndex", pageIndex)
            put("sha256", sha256)
            put("receivedBytes", receivedBytes)
        }.toString().toByteArray(Charsets.UTF_8)

        var lastError: Throwable? = null
        for (attempt in 1..3) {
            try {
                val conn = openConn(
                    "http://$host:$port/api/scan/jobs/$encJob/ack",
                    "POST",
                    CONNECT_TIMEOUT_MS,
                    SHORT_READ_TIMEOUT_MS,
                ).apply {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setFixedLengthStreamingMode(payload.size)
                }
                try {
                    conn.outputStream.use { it.write(payload) }
                    val code = conn.responseCode
                    val body = readBodyText(conn)
                    if (code in 200..299) {
                        return@withContext Result.success(JSONObject(body).optBoolean("deleted", true))
                    }
                    if (code == 404 || code == 410) {
                        // Already cleaned up on host
                        return@withContext Result.success(true)
                    }
                    lastError = IllegalStateException("ACK rejected (HTTP $code): $body")
                    break
                } finally {
                    conn.disconnect()
                }
            } catch (io: IOException) {
                lastError = io
                if (attempt < 3) {
                    delay(800L)
                    continue
                }
            } catch (t: Throwable) {
                lastError = t
                break
            }
        }
        Result.failure(lastError ?: IllegalStateException("ACK failed"))
    }

    private fun decodeFileSafely(file: File): Bitmap? {
        return try {
            BitmapFactory.decodeFile(file.absolutePath)
        } catch (_: OutOfMemoryError) {
            System.gc()
            runCatching {
                val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
                BitmapFactory.decodeFile(file.absolutePath, opts)
            }.getOrNull()
        }
    }

    suspend fun cancelJob(
        host: String,
        port: Int,
        jobId: String,
        deviceId: String,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val encJob = URLEncoder.encode(jobId, "UTF-8")
            val payload = JSONObject().apply { put("deviceId", deviceId) }
                .toString()
                .toByteArray(Charsets.UTF_8)
            val conn = openConn(
                "http://$host:$port/api/scan/jobs/$encJob/cancel",
                "POST",
                CONNECT_TIMEOUT_MS,
                SHORT_READ_TIMEOUT_MS,
            ).apply {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setFixedLengthStreamingMode(payload.size)
            }
            try {
                conn.outputStream.use { it.write(payload) }
                conn.responseCode
                Unit
            } finally {
                conn.disconnect()
            }
        }
    }

    private fun openConn(
        url: String,
        method: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
    ): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = method
        connectTimeout = connectTimeoutMs
        readTimeout = readTimeoutMs
        useCaches = false
        setRequestProperty("Connection", "close")
        DeviceIdentityHelper.attachClientHeaders(this)
    }

    private fun readBodyText(conn: HttpURLConnection): String {
        val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
        return runCatching { stream?.readBytes()?.toString(Charsets.UTF_8).orEmpty() }.getOrDefault("")
    }
}
