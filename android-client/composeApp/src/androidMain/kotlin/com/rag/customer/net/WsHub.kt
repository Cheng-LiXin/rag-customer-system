package com.rag.customer.net

import com.rag.customer.core.MainThread
import com.rag.customer.core.Server
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/**
 * 人工客服 WebSocket 单例（镜像 mp utils/ws.ts 协议，后端零改动）：
 *  - 连接成功后由调用方/自动重连后发送 register：{"type":"register","role":"user","conversationId":n}
 *  - 上行消息帧：{"type":"message","conversationId":n,"content":"..."}
 *  - 下行事件 JSON：message / assigned / position / closed(reason)
 *  - 非主动关闭时指数退避自动重连（最多 5 次）。
 */
object WsHub {

    interface Listener {
        /** 连接建立且已补发 register 后回调。 */
        fun onOpen()
        /** 收到一帧 JSON 文本。 */
        fun onMessage(text: String)
        /** 非主动关闭（含重连失败放弃）。 */
        fun onClosedUnexpectedly()
    }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var listener: Listener? = null
    private var ws: WebSocket? = null
    private var shouldRun = false
    private var userClosed = false
    private var attempts = 0
    private var registerJson: String? = null
    private var reconnectJob: Job? = null
    private val queue = ArrayDeque<String>()

    private fun currentUrl(): String = Server.wsUrl

    @Synchronized
    fun connect(register: String?, l: Listener) {
        listener = l
        registerJson = register
        queue.clear()
        userClosed = false
        shouldRun = true
        attempts = 0
        openSocket()
    }

    private fun openSocket() {
        val req = Request.Builder().url(currentUrl()).build()
        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                MainThread.post {
                    attempts = 0
                    registerJson?.let { safeSendRaw(it) }
                    flushQueue()
                    listener?.onOpen()
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                MainThread.post { listener?.onMessage(text) }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                MainThread.post { if (!userClosed) handleUnexpectedClose() }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                MainThread.post { if (!userClosed) handleUnexpectedClose() }
            }
        })
    }

    private fun handleUnexpectedClose() {
        listener?.onClosedUnexpectedly()
        if (shouldRun && attempts < 5) {
            attempts++
            val delayMs = (1 shl minOf(attempts - 1, 4)) * 1000 // 1s/2s/4s/8s/16s
            reconnectJob?.cancel()
            reconnectJob = scope.launch {
                delay(delayMs.toLong())
                if (shouldRun && !userClosed) openSocket()
            }
        } else {
            shouldRun = false
        }
    }

    @Synchronized
    fun send(json: String) {
        if (!(ws?.send(json) ?: false)) queue.addLast(json)
    }

    private fun safeSendRaw(json: String) {
        if (!(ws?.send(json) ?: false)) queue.addLast(json)
    }

    private fun flushQueue() {
        while (queue.isNotEmpty()) {
            val s = queue.removeFirst()
            if (!(ws?.send(s) ?: false)) {
                queue.addFirst(s)
                break
            }
        }
    }

    @Synchronized
    fun disconnect() {
        userClosed = true
        shouldRun = false
        reconnectJob?.cancel()
        reconnectJob = null
        queue.clear()
        registerJson = null
        ws?.close(1000, "bye")
        ws = null
        listener = null
    }
}
