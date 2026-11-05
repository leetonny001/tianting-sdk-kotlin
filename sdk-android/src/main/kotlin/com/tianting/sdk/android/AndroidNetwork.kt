package com.tianting.sdk.android

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import com.tianting.sdk.IMClient
import java.io.Closeable

/** 网络恢复时立即重连（PRD F-CONN-01 规则 3）：相当于浏览器的 online 事件。返回的对象 close 后解除监听。 */
public fun IMClient.bindNetwork(context: Context): Closeable {
    val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = networkOnline()
    }
    cm.registerDefaultNetworkCallback(callback)
    return Closeable { cm.unregisterNetworkCallback(callback) }
}
