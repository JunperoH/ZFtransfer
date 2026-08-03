package io.github.noisetta.nikonpull.data

import android.content.Context
import io.github.noisetta.nikonpull.ptp.PtpIpClient
import java.util.UUID

data class CameraEndpointSettings(
    val host: String = "",
    val port: Int = PtpIpClient.DEFAULT_PORT,
) {
    val usesManualHost: Boolean get() = host.isNotBlank()
}

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
        )
        set(value) {
            preferences.edit()
                .putString(KEY_MANUAL_HOST, value.host.trim())
                .putInt(KEY_PORT, value.port)
                .apply()
        }

    private companion object {
        const val PREFERENCES_NAME = "camera_settings"
        const val KEY_CLIENT_GUID = "client_guid"
        const val KEY_MANUAL_HOST = "manual_host"
        const val KEY_PORT = "port"
    }
}
