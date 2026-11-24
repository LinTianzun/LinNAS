package com.linnas.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLDecoder
import java.util.Collections
import java.util.concurrent.TimeUnit

/**
 * 局域网自动发现。三条路并行,任一条命中即可连上,彻底免手输 IP:
 *  1) UDP 广播/单播应答(最可靠;服务端在 UDP 52701 上应答 LINNAS_DISCOVER 并每 3s 广播一次)
 *  2) mDNS(NsdManager,服务类型 _linnas._tcp.)
 *  3) /24 网段端口扫描(兜底)
 */
object Discovery {
    const val DISCOVERY_PORT = 52701
    const val MAGIC = "LINNAS_DISCOVER"

    data class Server(
        val ip: String,
        val name: String,
        val version: String,
        val port: Int = 52700,
    ) {
        val baseUrl: String get() = "http://$ip:$port"
    }

    fun localIpv4(): String? {
        val ifs = Collections.list(NetworkInterface.getNetworkInterfaces())
        for (i in ifs) {
            try {
                if (!i.isUp || i.isLoopback) continue
            } catch (_: Exception) { continue }
            for (a in Collections.list(i.inetAddresses)) {
                val ip = a as? Inet4Address ?: continue
                val h = ip.hostAddress ?: continue
                if (h.startsWith("192.168.") || h.startsWith("10.") || is172Private(h)) return h
            }
        }
        return null
    }

    private fun is172Private(h: String): Boolean {
        if (!h.startsWith("172.")) return false
        val s = h.split(".").getOrNull(1)?.toIntOrNull() ?: return false
        return s in 16..31
    }

    /** 本机所在子网的定向广播地址,如 192.168.10.255。 */
    private fun subnetBroadcast(): InetAddress? {
        val ifs = Collections.list(NetworkInterface.getNetworkInterfaces())
        for (i in ifs) {
            try {
                if (!i.isUp || i.isLoopback) continue
            } catch (_: Exception) { continue }
            for (addr in i.interfaceAddresses) {
                val bc = addr.broadcast ?: continue
                if (bc is Inet4Address) return bc
            }
        }
        return null
    }

    /** 解析服务端回包(JSON),兼容缺字段。 */
    private fun parsePayload(text: String): Server? {
        return try {
            val o = JSONObject(text)
            val ips = o.optJSONArray("ips")
            val port = o.optInt("port", 52700)
            val name = o.optString("name", "linNas")
            val ver = o.optString("version", "")
            val ip = when {
                ips != null && ips.length() > 0 -> ips.optString(0, "")
                else -> o.optString("ip", "")
            }
            if (ip.isBlank()) null else Server(ip, name, ver, port)
        } catch (_: Exception) {
            null
        }
    }

    /** 监听 UDP 广播,收集服务端 beacon(每 3s 一次)。 */
    suspend fun listenBeacon(
        timeoutMs: Long = 2600,
        onFound: (Server) -> Unit = {},
    ): List<Server> = withContext(Dispatchers.IO) {
        val out = LinkedHashMap<String, Server>()
        var sock: DatagramSocket? = null
        try {
            sock = DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                soTimeout = 700
                bind(java.net.InetSocketAddress(DISCOVERY_PORT))
            }
            val buf = ByteArray(2048)
            val deadline = System.currentTimeMillis() + timeoutMs
            // 同时主动发一次探测,唤起可能没在广播的服务端
            try {
                val probe = MAGIC.toByteArray()
                listOfNotNull(
                    InetAddress.getByName("255.255.255.255"),
                    subnetBroadcast(),
                ).forEach { a ->
                    try {
                        sock.send(DatagramPacket(probe, probe.size, a, DISCOVERY_PORT))
                    } catch (_: Exception) { }
                }
            } catch (_: Exception) { }
            while (System.currentTimeMillis() < deadline) {
                val pkt = DatagramPacket(buf, buf.size)
                try {
                    sock.receive(pkt)
                } catch (_: SocketTimeoutException) {
                    continue
                } catch (_: Exception) {
                    break
                }
                val s = parsePayload(String(pkt.data, 0, pkt.length, Charsets.UTF_8)) ?: continue
                if (out.putIfAbsent("${s.ip}:${s.port}", s) == null) onFound(s)
            }
        } catch (_: Exception) {
            // 端口可能被占用/无权限,交给其它发现方式
        } finally {
            try { sock?.close() } catch (_: Exception) { }
        }
        out.values.toList()
    }

    /** mDNS 发现:通过 NsdManager 查找 _linnas._tcp. 服务。 */
    suspend fun mdns(
        timeoutMs: Long = 2500,
        onFound: (Server) -> Unit = {},
    ): List<Server> = withContext(Dispatchers.IO) {
        MdnsDiscovery.discover(timeoutMs, onFound)
    }

    /** 兜底:/24 网段端口扫描。 */
    suspend fun scan(
        port: Int = 52700,
        onFound: (Server) -> Unit = {},
    ): List<Server> = withContext(Dispatchers.IO) {
        val local = localIpv4() ?: return@withContext emptyList()
        val prefix = local.substringBeforeLast('.')
        val client = OkHttpClient.Builder()
            .connectTimeout(250, TimeUnit.MILLISECONDS)
            .readTimeout(500, TimeUnit.MILLISECONDS)
            .build()
        val sem = Semaphore(64)
        val found = Collections.synchronizedList(mutableListOf<Server>())
        coroutineScope {
            (1..254).map { n ->
                async(Dispatchers.IO) {
                    sem.withPermit {
                        val ip = "$prefix.$n"
                        try {
                            val req = Request.Builder().url("http://$ip:$port/api/health").build()
                            client.newCall(req).execute().use { r ->
                                if (r.isSuccessful) {
                                    val body = r.body?.string() ?: ""
                                    val o = JSONObject(body)
                                    if (o.optBoolean("ok")) {
                                        val s = Server(ip, o.optString("name", "linNas"), o.optString("version", ""), port)
                                        found.add(s)
                                        onFound(s)
                                    }
                                }
                            }
                        } catch (_: Exception) { }
                    }
                }
            }.awaitAll()
        }
        found.toList()
    }

    /**
     * 综合发现:UDP 广播 + mDNS + 端口扫描并行进行,结果去重后回调。
     * 快速路径通常 1~2 秒就能拿到结果。
     */
    suspend fun discover(
        port: Int = 52700,
        onFound: (Server) -> Unit = {},
    ): List<Server> = withContext(Dispatchers.IO) {
        val seen = LinkedHashMap<String, Server>()
        val lock = Any()
        fun add(s: Server) {
            val key = "${s.ip}:${s.port}"
            val isNew = synchronized(lock) { seen.putIfAbsent(key, s) == null }
            if (isNew) {
                try { onFound(s) } catch (_: Exception) { }
            }
        }
        coroutineScope {
            val a = launch { listenBeacon(2600) { add(it) } }
            val b = launch { mdns(2500) { add(it) } }
            val c = launch { scan(port) { add(it) } }
            // 给快速通道 3 秒窗口;扫描仍在后台继续,不阻塞 UI
            delay(3000)
            a.join(); b.join()
            c.cancel()
        }
        synchronized(lock) { seen.values.toList() }
    }

    /**
     * 解析二维码内容,支持:
     *  - linnas://pair?ip=192.168.1.5&port=52700&name=linNas&token=xxx
     *  - http://192.168.1.5:52700[...]
     */
    fun parsePair(text: String): Server? {
        val t = text.trim()
        try {
            if (t.startsWith("linnas://", ignoreCase = true)) {
                val u = URI(t)
                val q = u.rawQuery ?: ""
                val params = q.split("&").mapNotNull { kv ->
                    val i = kv.indexOf('='); if (i <= 0) null else
                        URLDecoder.decode(kv.substring(0, i), "UTF-8") to
                            URLDecoder.decode(kv.substring(i + 1), "UTF-8")
                }.toMap()
                val ip = params["ip"] ?: return null
                val port = params["port"]?.toIntOrNull() ?: 52700
                val name = params["name"] ?: "linNas"
                val token = params["token"]
                if (!token.isNullOrBlank()) ServerConfig.token = token
                return Server(ip, name, "", port)
            }
            if (t.startsWith("http://", true) || t.startsWith("https://", true)) {
                val u = URI(t)
                val host = u.host ?: return null
                val p = if (u.port > 0) u.port else 52700
                return Server(host, "linNas", "", p)
            }
        } catch (_: Exception) { }
        // 退化:纯 ip 或 ip:port
        val m = Regex("^(\\d{1,3}(?:\\.\\d{1,3}){3})(?::(\\d{1,5}))?$").find(t)
        if (m != null) {
            val ip = m.groupValues[1]
            val p = m.groupValues.getOrNull(2)?.toIntOrNull() ?: 52700
            return Server(ip, "linNas", "", p)
        }
        return null
    }
}
