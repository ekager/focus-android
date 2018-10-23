/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.focus.connectivity

import android.arch.lifecycle.LiveData
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.support.annotation.RequiresApi
import android.util.Log

@Suppress("DEPRECATION")
class ConnectionLiveData(private val context: Context) : LiveData<ConnectionModel>() {
    companion object {
        private const val TAG = "ConnectionLiveData"
        // Based on requiring ConnectivityManager#registerDefaultNetworkCallback - added in API 24.
        private fun isNetworkCallbackSupported(): Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
    }

    private val connectivityManager: ConnectivityManager by lazy {
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }

    @RequiresApi(Build.VERSION_CODES.N)
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
                    if (intent.action == ConnectivityManager.CONNECTIVITY_ACTION) {
                        Log.d(TAG, "Network broadcast received")
                        // Active Network Info and isConnected can be null with no default network
                        postValue(ConnectionModel(connectivityManager.activeNetworkInfo?.isConnected))
                    }
                }
            }
        }
        postValue(ConnectionModel(connectivityManager.activeNetworkInfo?.isConnected))
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private inner class NetworkStateCallback : ConnectivityManager.NetworkCallback() {
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
            val filter = IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION)
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
