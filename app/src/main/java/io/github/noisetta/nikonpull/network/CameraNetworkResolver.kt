package io.github.noisetta.nikonpull.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import java.io.Closeable
import java.net.Inet4Address
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

data class CameraNetworkLease(
    val network: Network,
    val phoneAddress: String,
    val gatewayAddress: String,
) : Closeable {
    override fun close() = Unit
}

object CameraNetworkResolver {
    suspend fun acquire(
        context: Context,
        manualHost: String? = null,
    ): CameraNetworkLease = withTimeout(NETWORK_TIMEOUT_MS) {
        val connectivityManager = context.getSystemService(ConnectivityManager::class.java)

        var cameraNetwork = findExistingCameraNetwork(connectivityManager, manualHost)
        while (cameraNetwork == null) {
            // DHCP can briefly replace the Wi-Fi Network object after association. Poll the
            // existing networks without requesting a new one; requestNetwork() disconnects
            // this camera access point on current Android versions.
            delay(NETWORK_POLL_INTERVAL_MS)
            cameraNetwork = findExistingCameraNetwork(connectivityManager, manualHost)
        }
        cameraNetwork
    }

    fun find(context: Context, manualHost: String? = null): CameraNetworkLease? = findExistingCameraNetwork(
        context.getSystemService(ConnectivityManager::class.java),
        manualHost,
    )

    private fun findExistingCameraNetwork(
        connectivityManager: ConnectivityManager,
        manualHost: String?,
    ): CameraNetworkLease? = connectivityManager.allNetworks
        .asSequence()
        .filter { network ->
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return@filter false
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                (manualHost != null ||
                    !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
        }
        .mapNotNull { network ->
            val route = connectivityManager.getLinkProperties(network)
                ?.let(::routeFrom)
                ?: return@mapNotNull null
            CameraNetworkLease(
                network = network,
                phoneAddress = route.phoneAddress,
                gatewayAddress = manualHost ?: route.gatewayAddress,
            )
        }
        .firstOrNull()

    private fun routeFrom(linkProperties: LinkProperties): Route? {
        val phoneAddress = linkProperties.linkAddresses
            .asSequence()
            .map { it.address }
            .filterIsInstance<Inet4Address>()
            .mapNotNull { it.hostAddress }
            .firstOrNull()
            ?: return null
        val gatewayAddress = linkProperties.routes
            .asSequence()
            .filter { it.isDefaultRoute }
            .mapNotNull { it.gateway }
            .filterIsInstance<Inet4Address>()
            .filterNot { it.isAnyLocalAddress }
            .mapNotNull { it.hostAddress }
            .firstOrNull()
            ?: return null

        return Route(phoneAddress, gatewayAddress)
    }

    private data class Route(
        val phoneAddress: String,
        val gatewayAddress: String,
    )

    private const val NETWORK_TIMEOUT_MS = 15_000L
    private const val NETWORK_POLL_INTERVAL_MS = 100L
}
