package jp.co.xenocode.mado

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import java.net.Inet4Address

/** A hub seen on the local network. `name` is what its owner called it. */
class FoundHub(val name: String, val url: String)

/** The address a frame dials for a hub at `host`; `path` comes from the hub's announcement. */
fun hubUrl(host: String, port: Int, path: String?): String {
    val p = path.orEmpty().ifEmpty { "/frame" }
    return "ws://$host:$port${if (p.startsWith("/")) p else "/$p"}"
}

/**
 * The address meant by what a person typed: `192.168.1.20`, `hub.local:7878` or a whole
 * `ws://` address. Null when there is nothing to dial.
 */
fun typedHubUrl(typed: String): String? {
    val bare = typed.trim().substringAfter("://").trimEnd('/')
    if (bare.isEmpty() || bare.any { it.isWhitespace() }) return null
    val host = bare.substringBefore("/")
    if (host.substringBefore(":").isEmpty()) return null
    val path = if ("/" in bare) "/" + bare.substringAfter("/") else "/frame"
    return "ws://" + (if (":" in host) host else "$host:$HUB_PORT") + path
}

const val HUB_PORT = 7878

/**
 * Looks for hubs announcing themselves on the local network (`_mado._tcp` over mDNS, the hub's
 * `discover.rs`). `onChange` gets the whole list each time it changes, on the main thread.
 */
class HubFinder(context: Context, private val onChange: (List<FoundHub>) -> Unit) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val main = Handler(Looper.getMainLooper())
    private val found = LinkedHashMap<String, FoundHub>()
    // This Android resolves one service at a time and fails any asked for meanwhile.
    private val toResolve = ArrayList<NsdServiceInfo>()
    private var resolving = false
    private var browsing: NsdManager.DiscoveryListener? = null

    val hubs: List<FoundHub> get() = found.values.toList()
    val running get() = browsing != null

    fun start() {
        if (browsing != null) return
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) {}
            override fun onDiscoveryStopped(type: String) {}
            override fun onStartDiscoveryFailed(type: String, error: Int) {
                main.post { if (browsing === this) browsing = null }
            }
            override fun onStopDiscoveryFailed(type: String, error: Int) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                main.post {
                    if (browsing === this) {
                        toResolve.add(info)
                        resolveNext()
                    }
                }
            }
            override fun onServiceLost(info: NsdServiceInfo) {
                main.post { if (browsing === this && found.remove(info.serviceName) != null) onChange(hubs) }
            }
        }
        browsing = listener
        try {
            nsd.discoverServices(SERVICE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: RuntimeException) {
            browsing = null
        }
    }

    fun stop() {
        val listener = browsing ?: return
        browsing = null
        toResolve.clear()
        found.clear()
        try {
            nsd.stopServiceDiscovery(listener)
        } catch (e: RuntimeException) {
            // Never started, as far as the system is concerned.
        }
    }

    private fun resolveNext() {
        if (resolving || toResolve.isEmpty()) return
        val session = browsing
        resolving = true
        val asked = toResolve.removeAt(0)
        try {
            nsd.resolveService(asked, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, error: Int) {
                    main.post { resolved(session, null) }
                }
                override fun onServiceResolved(info: NsdServiceInfo) {
                    main.post { resolved(session, info) }
                }
            })
        } catch (e: RuntimeException) {
            resolved(session, null)
        }
    }

    private fun resolved(session: NsdManager.DiscoveryListener?, info: NsdServiceInfo?) {
        resolving = false
        val host = info?.host
        if (info != null && session === browsing && host is Inet4Address) {
            val path = info.attributes["path"]?.let { String(it, Charsets.UTF_8) }
            val hub = FoundHub(info.serviceName, hubUrl(host.hostAddress.orEmpty(), info.port, path))
            if (found[hub.name]?.url != hub.url) {
                found[hub.name] = hub
                onChange(hubs)
            }
        }
        resolveNext()
    }

    private companion object {
        const val SERVICE = "_mado._tcp."
    }
}
