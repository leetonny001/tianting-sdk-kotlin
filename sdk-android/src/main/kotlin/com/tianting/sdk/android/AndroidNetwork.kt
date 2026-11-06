package com.tianting.sdk.android

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import com.tianting.sdk.AccountClient
import com.tianting.sdk.IMClient
import java.io.Closeable

/** 网络恢复时立即重连（PRD F-CONN-01 规则 3）：相当于浏览器的 online 事件。返回的对象 close 后解除监听。 */
public fun IMClient.bindNetwork(context: Context): Closeable = onNetworkAvailable(context) { networkOnline() }

/**
 * 网络恢复时补发"待退出"（PRD-chat F-ACC-04 规则 1）。退出之后 IMClient 已经不在连接了，所以账号这边单独监听，App 启动时就绑定，登录页也要有。
 * 返回的对象 close 后解除监听。
 */
public fun AccountClient.bindNetwork(context: Context): Closeable = onNetworkAvailable(context) { networkOnline() }

private fun onNetworkAvailable(context: Context, fn: () -> Unit): Closeable {
    val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = fn()
    }
    cm.registerDefaultNetworkCallback(callback)
    return Closeable { cm.unregisterNetworkCallback(callback) }
}
