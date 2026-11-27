package com.example.printer.network

import android.content.Context
import android.os.Build
import android.provider.Settings
import java.net.HttpURLConnection
import java.util.Locale

/**
 * Resolves a human-friendly identity (owner/device name if configured on Android, falling back
 * to clean Manufacturer + Model) with zero dangerous permissions, and attaches identifying HTTP
 * headers so the host server can display connected devices clearly.
 */
object DeviceIdentityHelper {

    const val HEADER_CLIENT_ID = "X-Client-Id"
    const val HEADER_CLIENT_NAME = "X-Client-Name"
    const val HEADER_CLIENT_MODEL = "X-Client-Model"

    @Volatile
    var cachedClientId: String = ""
        private set

    @Volatile
    var cachedDisplayName: String = ""
        private set

    @Volatile
    var cachedModelName: String = ""
        private set

    fun initialize(context: Context, clientId: String) {
        cachedClientId = clientId.trim()
        cachedModelName = resolveModelName()
        cachedDisplayName = resolveDisplayName(context)
    }

    fun resolveModelName(): String {
        val manufacturer = Build.MANUFACTURER?.trim().orEmpty()
        val model = Build.MODEL?.trim().orEmpty()
        if (model.isBlank() && manufacturer.isBlank()) return "Android Device"
        if (manufacturer.isBlank()) return model
        if (model.isBlank()) return manufacturer.replaceFirstChar { it.titlecase(Locale.US) }
        return if (model.startsWith(manufacturer, ignoreCase = true)) {
            model
        } else {
            val cleanMfg = manufacturer.replaceFirstChar { it.titlecase(Locale.US) }
            "$cleanMfg $model"
        }
    }

    fun resolveDisplayName(context: Context): String {
        val resolver = context.applicationContext.contentResolver
        val candidates = listOf(
            runCatching { Settings.Global.getString(resolver, Settings.Global.DEVICE_NAME) }.getOrNull(),
            runCatching { Settings.Secure.getString(resolver, "bluetooth_name") }.getOrNull(),
            runCatching { Settings.System.getString(resolver, "device_name") }.getOrNull(),
            runCatching { Settings.Global.getString(resolver, "device_name") }.getOrNull(),
        )
        val userDefined = candidates
            .mapNotNull { it?.trim() }
            .firstOrNull { name ->
                name.isNotBlank() &&
                    !name.equals("unknown", ignoreCase = true) &&
                    !name.equals("android", ignoreCase = true)
            }
        return sanitizeHeaderValue(userDefined ?: resolveModelName())
    }

    fun currentDisplayName(context: Context? = null): String {
        if (cachedDisplayName.isNotBlank()) return cachedDisplayName
        val resolved = if (context != null) resolveDisplayName(context) else resolveModelName()
        cachedDisplayName = resolved
        return resolved
    }

    fun currentModelName(): String {
        if (cachedModelName.isBlank()) {
            cachedModelName = resolveModelName()
        }
        return cachedModelName
    }

    fun attachClientHeaders(conn: HttpURLConnection) {
        val id = cachedClientId
        val name = cachedDisplayName.ifBlank { currentModelName() }
        val model = currentModelName()
        if (id.isNotBlank()) {
            conn.setRequestProperty(HEADER_CLIENT_ID, sanitizeHeaderValue(id))
        }
        if (name.isNotBlank()) {
            conn.setRequestProperty(HEADER_CLIENT_NAME, sanitizeHeaderValue(name))
        }
        if (model.isNotBlank()) {
            conn.setRequestProperty(HEADER_CLIENT_MODEL, sanitizeHeaderValue(model))
        }
    }

    fun sanitizeHeaderValue(raw: String): String {
        // Keep ASCII-safe header text (strip CR/LF/control chars)
        val cleaned = raw.replace(Regex("[\\r\\n\\t]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(64)
        return cleaned.ifBlank { "Android Device" }
    }
}
