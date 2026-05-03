package com.example.negotiation.service

import kotlinx.coroutines.*
import okhttp3.*
import okio.ByteString
import org.json.JSONObject

class WebSocketManager(
    private val onTranscript: (String) -> Unit,
    private val onAdvice: (String) -> Unit,
    private val onStatusChange: (String) -> Unit
) {
    private var webSocket: WebSocket? = null
    private val client = OkHttpClient.Builder()
        .pingInterval(20, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    private var currentUrl: String? = null
    private val reconnectScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun connect(url: String) {
        disconnect()
        currentUrl = url
        onStatusChange("连接中...")

        val request = Request.Builder().url(url).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                onStatusChange("已连接")
            }

            override fun onMessage(ws: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    when (json.optString("type")) {
                        "transcript" -> onTranscript(json.getString("text"))
                        "advice" -> onAdvice(json.getString("content"))
                        "error" -> onStatusChange("错误: ${json.optString("message")}")
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                onStatusChange("已断开")
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                onStatusChange("连接失败: ${t.message}")
                reconnectScope.launch {
                    delay(3000)
                    currentUrl?.let { connect(it) }
                }
            }
        })
    }

    fun sendAudio(data: ByteArray) {
        val buffer = okio.Buffer()
        buffer.write(data)
        webSocket?.send(buffer.readByteString())
    }

    fun sendText(text: String) {
        val json = org.json.JSONObject().apply {
            put("text", text)
        }
        webSocket?.send(json.toString())
    }

    fun disconnect() {
        webSocket?.close(1000, "用户断开")
        webSocket = null
    }
}
