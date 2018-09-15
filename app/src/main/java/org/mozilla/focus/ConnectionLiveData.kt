package org.mozilla.focus

import android.net.ConnectivityManager
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.arch.lifecycle.LiveData
import android.content.Context
import android.net.ConnectivityManager.CONNECTIVITY_ACTION
import android.os.Build
import android.support.annotation.RequiresApi
import android.net.NetworkCapabilities
import android.net.ConnectivityManager.NetworkCallback
import android.net.Network
import android.util.Log

@Suppress("DEPRECATION")
class ConnectionLiveData(private val context: Context) : LiveData<ConnectionModel>() {
    companion object {
        private const val TAG = "ConnectionLiveData"
        // Based on requiring ConnectivityManager#registerDefaultNetworkCallback - added in API 24.
        private fun isNetworkCallbackSupported(): Boolean = Build.VERSION.SDK_INT >= 24
    }

    private val connectivityManager: ConnectivityManager by lazy {
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }

    @RequiresApi(24)
    private var mNetworkCallback: NetworkStateCallback? = null
    private var networkReceiver: BroadcastReceiver? = null

    init {
        if (isNetworkCallbackSupported()) {
            mNetworkCallback = NetworkStateCallback()
        } else {
            networkReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.action == null) {
                        return
                    }
                    if (intent.action == CONNECTIVITY_ACTION) {
                        Log.d(TAG, "Network broadcast received")
                        // Active Network Info and isConnected can be null with no default network
                        postValue(ConnectionModel(connectivityManager.activeNetworkInfo?.isConnected))
                    }
                }
            }
        }
        postValue(ConnectionModel(connectivityManager.activeNetworkInfo?.isConnected))
    }

    @RequiresApi(24)
    private inner class NetworkStateCallback : NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            // The Network parameter is unreliable when a VPN app is running - use active network.
            Log.d(TAG, String.format("Network capabilities changed: $capabilities"))
            postValue(ConnectionModel(connectivityManager.activeNetworkInfo?.isConnected))
        }

        override fun onLost(network: Network) {
            Log.d(TAG, "Network connection lost")
            postValue(ConnectionModel(false))
        }
    }

    override fun onActive() {
        super.onActive()
        if (isNetworkCallbackSupported()) {
            Log.d(TAG, "Registering network callback")
            connectivityManager.registerDefaultNetworkCallback(mNetworkCallback)
        } else {
            Log.d(TAG, "Registering broadcast receiver")
            val filter = IntentFilter(CONNECTIVITY_ACTION)
            context.registerReceiver(networkReceiver, filter)
        }
    }

    override fun onInactive() {
        super.onInactive()
        if (isNetworkCallbackSupported()) {
            Log.d(TAG, "Unregistering network callback")
            connectivityManager.unregisterNetworkCallback(mNetworkCallback)
        } else {
            Log.d(TAG, "Unregistering broadcast receiver")
            context.unregisterReceiver(networkReceiver)
        }
    }
}
