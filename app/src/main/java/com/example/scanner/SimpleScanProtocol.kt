package com.example.scanner

import com.example.model.ScanColorOption
import com.example.model.ScanPageSizeOption
import com.example.model.ScanParameters
import com.example.model.ScanSourceOption
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

enum class SimpleScanJobStatus {
    QUEUED,
    RUNNING,
    READY,
    FAILED,
    CANCELED;

    companion object {
        fun fromString(raw: String?): SimpleScanJobStatus =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: FAILED
    }
}

data class SimpleScanPageMeta(
    val index: Int,
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String,
    val width: Int,
    val height: Int,
    val acked: Boolean = false,
    val movedToLostAndFound: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("index", index)
        put("fileName", fileName)
        put("sizeBytes", sizeBytes)
        put("sha256", sha256)
        put("width", width)
        put("height", height)
        put("acked", acked)
        put("movedToLostAndFound", movedToLostAndFound)
    }

    companion object {
        fun fromJson(obj: JSONObject): SimpleScanPageMeta = SimpleScanPageMeta(
            index = obj.optInt("index", 1),
            fileName = obj.optString("fileName", "page_001.jpg"),
            sizeBytes = obj.optLong("sizeBytes", 0L),
            sha256 = obj.optString("sha256", ""),
            width = obj.optInt("width", 0),
            height = obj.optInt("height", 0),
            acked = obj.optBoolean("acked", false),
            movedToLostAndFound = obj.optBoolean("movedToLostAndFound", false),
        )
    }
}

data class SimpleScanJobState(
    val jobId: String,
    val deviceId: String,
    val clientName: String,
    val status: SimpleScanJobStatus,
    val progress: Int,
    val statusMessage: String,
    val errorMessage: String? = null,
    val queuePosition: Int = 0,
    val attempt: Int = 1,
    val maxAttempts: Int = 3,
    val dpi: Int = 300,
    val colorMode: ScanColorOption = ScanColorOption.COLOR,
    val pageSize: ScanPageSizeOption = ScanPageSizeOption.A4,
    val source: ScanSourceOption = ScanSourceOption.FLATBED,
    val brightness: Int = 50,
    val contrast: Int = 50,
    val createdAtMs: Long = System.currentTimeMillis(),
    val updatedAtMs: Long = System.currentTimeMillis(),
    val heartbeatAtMs: Long = updatedAtMs,
    val completedAtMs: Long? = null,
    val pages: List<SimpleScanPageMeta> = emptyList(),
) {
    val unackedPages: List<SimpleScanPageMeta>
        get() = pages.filter { !it.acked }

    fun toJson(): JSONObject = JSONObject().apply {
        put("jobId", jobId)
        put("deviceId", deviceId)
        put("clientName", clientName)
        put("status", status.name)
        put("progress", progress.coerceIn(0, 100))
        put("statusMessage", statusMessage)
        put("queuePosition", queuePosition.coerceAtLeast(0))
        put("attempt", attempt.coerceAtLeast(1))
        put("maxAttempts", maxAttempts.coerceAtLeast(1))
        if (!errorMessage.isNullOrBlank()) put("errorMessage", errorMessage) else put("errorMessage", JSONObject.NULL)
        put("dpi", dpi)
        put("colorMode", colorMode.name)
        put("pageSize", pageSize.name)
        put("source", source.name)
        put("brightness", brightness)
        put("contrast", contrast)
        put("createdAtMs", createdAtMs)
        put("updatedAtMs", updatedAtMs)
        put("heartbeatAtMs", heartbeatAtMs)
        if (completedAtMs != null) put("completedAtMs", completedAtMs)
        val arr = JSONArray()
        pages.forEach { arr.put(it.toJson()) }
        put("pages", arr)
    }

    companion object {
        fun fromJson(obj: JSONObject): SimpleScanJobState {
            val pagesArr = obj.optJSONArray("pages")
            val parsedPages = buildList {
                if (pagesArr != null) {
                    for (i in 0 until pagesArr.length()) {
                        val pObj = pagesArr.optJSONObject(i) ?: continue
                        add(SimpleScanPageMeta.fromJson(pObj))
                    }
                }
            }
            val errRaw = obj.opt("errorMessage")
            val errStr = if (errRaw == null || errRaw == JSONObject.NULL) null else errRaw.toString().ifBlank { null }
            val updatedAt = obj.optLong("updatedAtMs", System.currentTimeMillis())
            return SimpleScanJobState(
                jobId = obj.optString("jobId", ""),
                deviceId = obj.optString("deviceId", ""),
                clientName = obj.optString("clientName", "Client"),
                status = SimpleScanJobStatus.fromString(obj.optString("status")),
                progress = obj.optInt("progress", 0).coerceIn(0, 100),
                statusMessage = obj.optString("statusMessage", ""),
                errorMessage = errStr,
                queuePosition = obj.optInt("queuePosition", 0).coerceAtLeast(0),
                attempt = obj.optInt("attempt", 1).coerceAtLeast(1),
                maxAttempts = obj.optInt("maxAttempts", 3).coerceAtLeast(1),
                dpi = obj.optInt("dpi", 300),
                colorMode = runCatching { ScanColorOption.valueOf(obj.optString("colorMode")) }
                    .getOrDefault(ScanColorOption.COLOR),
                pageSize = runCatching { ScanPageSizeOption.valueOf(obj.optString("pageSize")) }
                    .getOrDefault(ScanPageSizeOption.A4),
                source = runCatching { ScanSourceOption.valueOf(obj.optString("source")) }
                    .getOrDefault(ScanSourceOption.FLATBED),
                brightness = obj.optInt("brightness", 50).coerceIn(0, 100),
                contrast = obj.optInt("contrast", 50).coerceIn(0, 100),
                createdAtMs = obj.optLong("createdAtMs", System.currentTimeMillis()),
                updatedAtMs = updatedAt,
                heartbeatAtMs = obj.optLong("heartbeatAtMs", updatedAt),
                completedAtMs = if (obj.has("completedAtMs") && !obj.isNull("completedAtMs")) {
                    obj.optLong("completedAtMs")
                } else {
                    null
                },
                pages = parsedPages,
            )
        }
    }
}

data class SimpleScanCapabilities(
    val available: Boolean,
    val busy: Boolean,
    val busyReason: String? = null,
    val supportsFlatbed: Boolean = true,
    val supportsAdf: Boolean = false,
    val supportsAdfDuplex: Boolean = false,
    val model: String = "Brother Scanner",
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("available", available)
        put("busy", busy)
        if (!busyReason.isNullOrBlank()) put("busyReason", busyReason)
        put("supportsFlatbed", supportsFlatbed)
        put("supportsAdf", supportsAdf)
        put("supportsAdfDuplex", supportsAdfDuplex)
        put("model", model)
    }

    companion object {
        fun fromJson(obj: JSONObject): SimpleScanCapabilities = SimpleScanCapabilities(
            available = obj.optBoolean("available", false),
            busy = obj.optBoolean("busy", false),
            busyReason = obj.optString("busyReason", "").ifBlank { null },
            supportsFlatbed = obj.optBoolean("supportsFlatbed", true),
            supportsAdf = obj.optBoolean("supportsAdf", false),
            supportsAdfDuplex = obj.optBoolean("supportsAdfDuplex", false),
            model = obj.optString("model", "Brother Scanner"),
        )
    }
}

sealed interface SimpleScanHttpResponse {
    val statusCode: Int
    val reason: String
    val contentType: String
    val extraHeaders: Map<String, String>

    data class Bytes(
        override val statusCode: Int,
        override val reason: String,
        override val contentType: String = "application/json; charset=utf-8",
        val body: ByteArray,
        override val extraHeaders: Map<String, String> = emptyMap(),
    ) : SimpleScanHttpResponse

    data class FileStream(
        override val statusCode: Int = 200,
        override val reason: String = "OK",
        override val contentType: String = "image/jpeg",
        val file: File,
        override val extraHeaders: Map<String, String> = emptyMap(),
    ) : SimpleScanHttpResponse
}

object SimpleScanProtocol {
    const val BASE_PATH = "/api/scan"

    private val SAFE_ID_REGEX = Regex("^[a-zA-Z0-9_-]{3,64}$")

    fun sanitizeId(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val trimmed = raw.trim()
        if (trimmed.contains("..") || trimmed.contains('/') || trimmed.contains('\\')) return null
        val cleaned = trimmed.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(64)
        return cleaned.takeIf { SAFE_ID_REGEX.matches(it) }
    }

    fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { fis ->
            val buf = ByteArray(32 * 1024)
            while (true) {
                val n = fis.read(buf)
                if (n <= 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    fun buildStartRequestJson(
        deviceId: String,
        clientName: String,
        params: ScanParameters,
    ): String = JSONObject().apply {
        put("deviceId", deviceId)
        put("clientName", clientName)
        put("source", params.source.name)
        put("colorMode", params.colorMode.name)
        put("dpi", params.dpi)
        put("pageSize", params.pageSize.name)
        put("brightness", params.brightness.coerceIn(0, 100))
        put("contrast", params.contrast.coerceIn(0, 100))
    }.toString()

    fun parseQueryParams(rawPath: String): Map<String, String> {
        val query = rawPath.substringAfter('?', "")
        if (query.isBlank()) return emptyMap()
        val result = LinkedHashMap<String, String>()
        query.split('&').forEach { pair ->
            if (pair.isBlank()) return@forEach
            val idx = pair.indexOf('=')
            if (idx > 0) {
                val key = runCatching { java.net.URLDecoder.decode(pair.substring(0, idx), "UTF-8") }
                    .getOrDefault(pair.substring(0, idx))
                val value = runCatching { java.net.URLDecoder.decode(pair.substring(idx + 1), "UTF-8") }
                    .getOrDefault(pair.substring(idx + 1))
                result[key] = value
            }
        }
        return result
    }

    fun jsonResponse(
        statusCode: Int,
        reason: String,
        json: JSONObject,
    ): SimpleScanHttpResponse.Bytes = SimpleScanHttpResponse.Bytes(
        statusCode = statusCode,
        reason = reason,
        contentType = "application/json; charset=utf-8",
        body = json.toString().toByteArray(Charsets.UTF_8),
    )

    fun errorResponse(
        statusCode: Int,
        reason: String,
        message: String,
    ): SimpleScanHttpResponse.Bytes = jsonResponse(
        statusCode = statusCode,
        reason = reason,
        json = JSONObject().apply { put("error", message) },
    )
}
