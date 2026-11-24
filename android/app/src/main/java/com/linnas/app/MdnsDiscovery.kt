package com.linnas.app

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * 基于 Android NsdManager 的 mDNS 发现,查找服务端广播的 _linnas._tcp. 服务。
 * 与 UDP 广播发现互为补充:某些系统/网络下 mDNS 更通,某些下 UDP 更通。
 */
object MdnsDiscovery {
    private const val SERVICE_TYPE = "_linnas._tcp."

    suspend fun discover(
        timeoutMs: Long = 2500,
        onFound: (Discovery.Server) -> Unit = {},
    ): List<Discovery.Server> = withContext(Dispatchers.IO) {
        val ctx: Context = LinNasApp.instance ?: return@withContext emptyList()
        val nsd = ctx.getSystemService(Context.NSD_SERVICE) as? NsdManager
            ?: return@withContext emptyList()
        val found = LinkedHashMap<String, Discovery.Server>()
        val lock = Any()

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                try { nsd.stopServiceDiscovery(this) } catch (_: Exception) {}
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                try {
                    nsd.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(si: NsdServiceInfo, errorCode: Int) {}
                        override fun onServiceResolved(si: NsdServiceInfo) {
                            val host = si.host?.hostAddress ?: return
                            val port = if (si.port > 0) si.port else 52700
                            val s = Discovery.Server(host, si.serviceName ?: "linNas", "", port)
                            val isNew = synchronized(lock) {
                                found.putIfAbsent("$host:$port", s) == null
                            }
                            if (isNew) {
                                try { onFound(s) } catch (_: Exception) {}
                            }
                        }
                    })
                } catch (_: Exception) { }
            }
        }

        try {
            withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine { cont ->
                    try {
                        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
                    } catch (_: Exception) {
                        if (cont.isActive) cont.resume(Unit)
                    }
                    // 到点自行结束,交由 withTimeoutOrNull 收口
                    cont.invokeOnCancellation {
                        try { nsd.stopServiceDiscovery(listener) } catch (_: Exception) { }
                    }
                }
            }
        } catch (_: Exception) {
        } finally {
            try { nsd.stopServiceDiscovery(listener) } catch (_: Exception) { }
        }
        synchronized(lock) { found.values.toList() }
    }
}
