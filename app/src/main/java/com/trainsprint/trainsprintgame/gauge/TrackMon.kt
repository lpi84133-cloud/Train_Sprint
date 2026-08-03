package com.trainsprint.trainsprintgame.gauge

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

class TrackMon(ctx: Context) {

    private val cm = ctx.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE)
            as ConnectivityManager

    /** True if there is an active network that reports internet capability. */
    fun isConnected(): Boolean {
        val net = cm.activeNetwork ?: return false
        val cap = cm.getNetworkCapabilities(net) ?: return false
        return cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** True if a TCP socket can actually reach the network. */
    suspend fun hasRealInternet(): Boolean = withContext(Dispatchers.IO) {
        try {
            Socket().use { s ->
                s.connect(InetSocketAddress("1.1.1.1", 53), 3_000)
                true
            }
        } catch (_: Exception) { false }
    }

    /**
     * Emits the live online/offline state of the DEFAULT network. Using the default
     * network callback (rather than a capability-filtered request) means we get an
     * immediate onLost the moment Wi-Fi / cellular is turned off, so the WebView host
     * can react instantly even when no page request is in flight.
     */
    val connectivityFlow: Flow<Boolean> = callbackFlow {
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { trySend(true) }
            override fun onLost(network: Network) { trySend(false) }
            override fun onUnavailable() { trySend(false) }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                trySend(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
            }
        }
        cm.registerDefaultNetworkCallback(cb)
        trySend(isConnected())
        awaitClose { cm.unregisterNetworkCallback(cb) }
    }.distinctUntilChanged()
}
