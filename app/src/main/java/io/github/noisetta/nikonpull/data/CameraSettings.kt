package io.github.noisetta.nikonpull.data

import android.content.Context
import io.github.noisetta.nikonpull.ptp.PtpIpClient
import java.util.UUID

data class CameraEndpointSettings(
    val host: String = "",
    val port: Int = PtpIpClient.DEFAULT_PORT,
    val mode: CameraConnectionMode = CameraConnectionMode.AP,
) {
    val usesManualHost: Boolean get() = host.isNotBlank()
}

enum class CameraConnectionMode { AP, STA }

class CameraSettings(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    val clientGuid: UUID
        get() {
            val stored = preferences.getString(KEY_CLIENT_GUID, null)
            val parsed = stored?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            if (parsed != null) return parsed
            return UUID.randomUUID().also { generated ->
                preferences.edit().putString(KEY_CLIENT_GUID, generated.toString()).apply()
            }
        }

    var endpoint: CameraEndpointSettings
        get() = CameraEndpointSettings(
            host = preferences.getString(KEY_MANUAL_HOST, "").orEmpty(),
            port = preferences.getInt(KEY_PORT, PtpIpClient.DEFAULT_PORT),
            mode = runCatching {
                CameraConnectionMode.valueOf(preferences.getString(KEY_MODE, "AP").orEmpty())
            }.getOrDefault(CameraConnectionMode.AP),
        )
        set(value) {
            preferences.edit()
                .putString(KEY_MANUAL_HOST, value.host.trim())
                .putInt(KEY_PORT, value.port)
                .putString(KEY_MODE, value.mode.name)
                .putString(hostKey(value.mode), value.host.trim())
                .putInt(portKey(value.mode), value.port)
                .apply()
        }

    fun endpointFor(mode: CameraConnectionMode): CameraEndpointSettings {
        val active = endpoint
        if (!preferences.contains(hostKey(active.mode))) {
            preferences.edit()
                .putString(hostKey(active.mode), active.host)
                .putInt(portKey(active.mode), active.port)
                .apply()
        }
        if (active.mode == mode) return active
        return CameraEndpointSettings(
            host = preferences.getString(hostKey(mode), "").orEmpty(),
            port = preferences.getInt(portKey(mode), PtpIpClient.DEFAULT_PORT),
            mode = mode,
        )
    }

    private fun hostKey(mode: CameraConnectionMode) = "${KEY_MANUAL_HOST}_${mode.name}"
    private fun portKey(mode: CameraConnectionMode) = "${KEY_PORT}_${mode.name}"

    private companion object {
        const val PREFERENCES_NAME = "camera_settings"
        const val KEY_CLIENT_GUID = "client_guid"
        const val KEY_MANUAL_HOST = "manual_host"
        const val KEY_PORT = "port"
        const val KEY_MODE = "connection_mode"
    }
}
