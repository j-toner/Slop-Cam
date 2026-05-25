package com.slopIpCam.cam

import android.util.Log
import kotlinx.coroutines.*
import okhttp3.*
import okio.ByteString
import okio.ByteString.Companion.toByteString

class WsClient(
    private val url: String,
    private val onText: (String) -> Unit,
    private val onBinary: (ByteArray) -> Unit = {},
    private val onConnected: () -> Unit = {},
    private val onDisconnected: () -> Unit = {}
) {
    private val client = OkHttpClient.Builder()
        .retryOnConnectionFailure(true)
        .build()
    private var ws: WebSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var reconnectJob: Job? = null

    fun connect() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            var delayMs = 1000L
            while (isActive) {
                try {
                    val request = Request.Builder().url(url).build()
                    val latch = CompletableDeferred<Unit>()
                    ws = client.newWebSocket(request, object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            delayMs = 1000L
                            onConnected()
                        }
                        override fun onMessage(webSocket: WebSocket, text: String) = onText(text)
                        override fun onMessage(webSocket: WebSocket, bytes: ByteString) = onBinary(bytes.toByteArray())
                        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                            onDisconnected()
                            latch.complete(Unit)
                        }
                        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                            Log.w("WsClient", "ws failure: ${t.message}")
                            onDisconnected()
                            latch.complete(Unit)
                        }
                    })
                    latch.await()
                } catch (e: CancellationException) {
                    break
                } catch (e: Exception) {
                    Log.w("WsClient", "connect error: ${e.message}")
                }
                delay(delayMs)
                delayMs = minOf(delayMs * 2, 30_000L)
            }
        }
    }

    fun sendText(msg: String) { ws?.send(msg) }
    fun sendBinary(data: ByteArray) { ws?.send(data.toByteString()) }

    fun disconnect() {
        reconnectJob?.cancel()
        ws?.close(1000, "disconnect")
        ws = null
    }
}
