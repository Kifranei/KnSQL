package dev.knsql.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log

object AndroidNetwork {
    var context: Context? = null
}

actual fun prepareDatabaseNetwork() {
    val context = AndroidNetwork.context ?: return
    val manager = context.getSystemService(ConnectivityManager::class.java) ?: return
    val vpn = manager.allNetworks.firstOrNull { network ->
        val capabilities = manager.getNetworkCapabilities(network) ?: return@firstOrNull false
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }
    manager.bindProcessToNetwork(vpn)
}

actual fun logFailure(tag: String, error: Throwable) {
    Log.e("KnSQL", tag, error)
}
