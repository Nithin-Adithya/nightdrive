package com.nithin.nightcast

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.util.concurrent.Executors

/**
 * Play, pause and skip sent back to the phone. AirPlay itself is one-way; these go over DACP,
 * the remote-control protocol iTunes introduced. While it streams, the phone announces a
 * "_dacp._tcp" service named after an id it gave us, and accepts simple HTTP commands on it
 * that carry the matching Active-Remote token.
 */
internal class RemoteControl(context: Context, private val onChanged: () -> Unit) {

    private val nsd = context.getSystemService(NsdManager::class.java)
    private val worker = Executors.newSingleThreadExecutor()

    private var discovery: NsdManager.DiscoveryListener? = null
    @Volatile private var activeRemote: String? = null
    @Volatile private var host: InetAddress? = null
    @Volatile private var port = 0

    /** True once the phone's control service has been found, i.e. commands have somewhere to go. */
    val available: Boolean get() = host != null && activeRemote != null

    @Synchronized
    fun setTarget(dacpId: String, activeRemote: String) {
        clear()
        this.activeRemote = activeRemote
        val wanted = "iTunes_Ctrl_$dacpId"
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(service: NsdServiceInfo) {
                if (service.serviceName.equals(wanted, ignoreCase = true)) resolve(service)
            }

            override fun onServiceLost(service: NsdServiceInfo) = Unit
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "could not look for the phone's remote control: $errorCode")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
        discovery = listener
        try {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: RuntimeException) {
            Log.w(TAG, "service discovery unavailable", e)
            discovery = null
        }
    }

    @Suppress("DEPRECATION")
    private fun resolve(service: NsdServiceInfo) {
        nsd.resolveService(service, object : NsdManager.ResolveListener {
            override fun onServiceResolved(resolved: NsdServiceInfo) {
                host = resolved.host
                port = resolved.port
                Log.i(TAG, "remote control at ${resolved.host}:${resolved.port}")
                onChanged()
            }

            override fun onResolveFailed(service: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "could not resolve the phone's remote control: $errorCode")
            }
        })
    }

    @Synchronized
    fun clear() {
        discovery?.let {
            try {
                nsd.stopServiceDiscovery(it)
            } catch (e: RuntimeException) {
                // Discovery never started.
            }
        }
        discovery = null
        activeRemote = null
        host = null
    }

    fun playPause() = send("playpause")
    fun next() = send("nextitem")
    fun previous() = send("previtem")

    private fun send(command: String) {
        val address = host ?: return
        val token = activeRemote ?: return
        val targetPort = port
        worker.execute {
            try {
                val url = URL("http", address.hostAddress, targetPort, "/ctrl-int/1/$command")
                val connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                connection.setRequestProperty("Active-Remote", token)
                val status = connection.responseCode
                connection.disconnect()
                if (status >= 400) Log.w(TAG, "$command refused: $status")
            } catch (e: Exception) {
                Log.w(TAG, "$command failed", e)
            }
        }
    }

    private companion object {
        const val TAG = "NightcastRemote"
        const val SERVICE_TYPE = "_dacp._tcp"
        const val TIMEOUT_MS = 2000
    }
}
