package jp.co.xenocode.mado

import android.os.Handler
import android.os.Looper
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.min

/**
 * The frame's one connection: an outbound WebSocket to the hub, redialled forever.
 * Listener callbacks arrive on the main thread.
 */
class HubLink(private val url: String, private val hello: JSONObject, private val listener: Listener) {
    interface Listener {
        fun onHubMessage(msg: JSONObject)
        fun onHubLink(up: Boolean)
    }

    private val main = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .connectTimeout(5, TimeUnit.SECONDS)
        .build()
    private val redial = Runnable { dial() }
    private var socket: WebSocket? = null
    private var backoffMs = FIRST_BACKOFF_MS
    private var stopped = false

    fun start() = dial()

    fun stop() {
        stopped = true
        main.removeCallbacks(redial)
        socket?.close(1000, null)
        socket = null
    }

    fun send(msg: JSONObject) {
        socket?.send(msg.toString())
    }

    /** GET a file from the hub (same host as the socket). `done` gets null on any failure. */
    fun fetch(path: String, done: (ByteArray?) -> Unit) {
        val base = url.replaceFirst("ws", "http").substringBeforeLast("/frame")
        val request = try {
            Request.Builder().url(base + path).build()
        } catch (e: IllegalArgumentException) {
            return done(null)
        }
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                main.post { done(null) }
            }

            override fun onResponse(call: Call, response: Response) {
                val bytes = response.use { if (it.isSuccessful) it.body?.bytes() else null }
                main.post { done(bytes) }
            }
        })
    }

    private fun dial() {
        val request = try {
            Request.Builder().url(url).build()
        } catch (e: IllegalArgumentException) {
            return // A hub address that is not a URL; nothing to redial until it is changed.
        }
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(hello.toString())
                main.post {
                    backoffMs = FIRST_BACKOFF_MS
                    listener.onHubLink(true)
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val msg = try {
                    JSONObject(text)
                } catch (e: Exception) {
                    return
                }
                main.post { listener.onHubMessage(msg) }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                lost(webSocket)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                lost(webSocket)
            }
        })
    }

    private fun lost(which: WebSocket) {
        main.post { onLost(which) }
    }

    private fun onLost(which: WebSocket) {
        if (which !== socket) return
        socket = null
        listener.onHubLink(false)
        if (!stopped) {
            main.postDelayed(redial, backoffMs)
            backoffMs = min(backoffMs * 2, MAX_BACKOFF_MS)
        }
    }

    private companion object {
        const val FIRST_BACKOFF_MS = 1_000L
        const val MAX_BACKOFF_MS = 15_000L
    }
}
