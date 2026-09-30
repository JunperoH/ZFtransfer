package io.github.noisetta.nikonpull.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import io.github.noisetta.nikonpull.diagnostics.DiagnosticLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address

data class DiscoveredCameraEndpoint(
    val host: String,
    val port: Int,
    val name: String,
    val apps: String?,
)

/** DNS-SD discovery for PTP/IP responders on the current local Wi-Fi network. */
object StaCameraDiscovery {
    suspend fun discover(context: Context, timeoutMs: Long = 8_000L): DiscoveredCameraEndpoint? {
        val manager = context.getSystemService(NsdManager::class.java)
        val result = CompletableDeferred<DiscoveredCameraEndpoint?>()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                DiagnosticLog.info("STA", "DNS-SD discovery started type=$serviceType")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                DiagnosticLog.info("STA", "service found name=${serviceInfo.serviceName} type=${serviceInfo.serviceType}")
                manager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                        DiagnosticLog.warn("STA", "resolve failed name=${info.serviceName} code=$errorCode")
                    }

                    override fun onServiceResolved(info: NsdServiceInfo) {
                        val address = info.host as? Inet4Address ?: return
                        if (info.port !in 1..65535) return
                        val apps = info.attributes["apps"]?.toString(Charsets.US_ASCII)
                        DiagnosticLog.info("STA", "resolved name=${info.serviceName} host=${address.hostAddress} port=${info.port} apps=${apps ?: "absent"}")
                        result.complete(DiscoveredCameraEndpoint(address.hostAddress.orEmpty(), info.port, info.serviceName, apps))
                    }
                })
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                DiagnosticLog.warn("STA", "discovery start failed code=$errorCode")
                result.complete(null)
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                DiagnosticLog.warn("STA", "discovery stop failed code=$errorCode")
            }
        }
        return try {
            manager.discoverServices("_ptp._tcp.", NsdManager.PROTOCOL_DNS_SD, listener)
            withTimeoutOrNull(timeoutMs) { result.await() }
        } finally {
            runCatching { manager.stopServiceDiscovery(listener) }
        }
    }
}
