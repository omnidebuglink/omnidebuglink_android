package com.omnidebuglink.android

import android.os.Build
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

/**
 * WS 连接层：网络 I/O 全在后台协程；task 分发到主线程执行（handler 里安全访问 View/Activity）。
 * 心跳 55s / 看门狗 180s / 指数退避重连（1s→30s）。
 * 关闭码 4000 = 同 token 被新连接顶替 → 打警告并永久停机，不再重连（防止两台设备互踢乒乓）。
 */
internal class LinkConnection(private val url: String) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .pingInterval(0, TimeUnit.SECONDS) // 心跳走应用层 JSON ping
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val stopped = AtomicBoolean(false)
    @Volatile
    var connected = false
        private set
    @Volatile
    private var ws: WebSocket? = null
    @Volatile
    private var lastServerMessageMs = 0L
    @Volatile
    var reconnectAttempts = 0
        private set

    fun start() {
        scope.launch { runLoop() }
    }

    fun close() {
        stopped.set(true)
        connected = false
        try {
            ws?.close(1001, "client stopped")
        } catch (_: Exception) {
        }
        scope.cancel()
    }

    private suspend fun runLoop() {
        var backoffMs = 1000L
        while (!stopped.get()) {
            val closed = CompletableRef<Unit>()
            var socket: WebSocket? = null
            try {
                socket = client.newWebSocket(
                    Request.Builder().url(url).build(),
                    listener(closed)
                )
                ws = socket
            } catch (e: Exception) {
                Log.w(TAG, "websocket construction failed: ${e.message}")
            }
            if (socket != null) {
                closed.await()
                connected = false
                if (stopped.get()) return
                if (closedByReplacement) {
                    Log.w(
                        TAG,
                        "closed with code 4000: this token pair was replaced by a newer connection. " +
                            "Stopping reconnects. One token pair belongs to ONE device; mint a separate " +
                            "token pair for each device in the console."
                    )
                    return
                }
            }
            Log.i(TAG, "disconnected; reconnecting in ${backoffMs}ms")
            delay(backoffMs)
            backoffMs = min(30_000L, backoffMs * 2)
            reconnectAttempts++
        }
    }

    @Volatile
    private var closedByReplacement = false

    private fun listener(closed: CompletableRef<Unit>) = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            connected = true
            reconnectAttempts = 0
            lastServerMessageMs = SystemClock.elapsedRealtime()
            sendHello()
            scope.launch { heartbeatLoop(webSocket) }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            lastServerMessageMs = SystemClock.elapsedRealtime()
            handleMessage(text)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (code == 4000) closedByReplacement = true
            closed.complete()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.i(TAG, "connection failure: ${t.message}")
            closed.complete()
        }
    }

    private suspend fun heartbeatLoop(webSocket: WebSocket) {
        while (connected && !stopped.get() && ws === webSocket) {
            delay(OmniDebugLink.HeartbeatMs)
            if (!connected || stopped.get() || ws !== webSocket) return
            val silent = SystemClock.elapsedRealtime() - lastServerMessageMs
            if (silent > OmniDebugLink.WatchdogMs) {
                Log.w(TAG, "heartbeat watchdog: server went silent for ${silent}ms, dropping connection")
                try {
                    webSocket.cancel()
                } catch (_: Exception) {
                }
                return
            }
            webSocket.send("""{"v":1,"type":"ping"}""")
        }
    }

    private fun handleMessage(text: String) {
        val msg = try {
            JSONObject(text)
        } catch (_: Exception) {
            return
        }
        if (msg.optInt("v") != 1) return
        when (msg.optString("type")) {
            "pong" -> {}
            "task" -> {
                val requestId = msg.optString("requestId")
                val task = msg.optJSONObject("task") ?: return
                val type = task.optString("type")
                if (requestId.isEmpty() || type.isEmpty()) return
                dispatch(requestId, type, task.optJSONObject("payload") ?: JSONObject())
            }
        }
    }

    private fun dispatch(requestId: String, type: String, payload: JSONObject) {
        val spec = OmniDebugLink.tasks.tryGet(type)
        if (spec == null) {
            sendResultError(requestId, "UNKNOWN_TASK", "no handler registered for task type \"$type\"")
            return
        }
        // 主线程执行：handler 可安全访问 View/Activity；suspend 便于 wait_for/screenshot 这类异步
        OmniDebugLink.mainHandler.post {
            scope.launch(Dispatchers.Main) {
                val result = try {
                    Jsons.fromAny(spec.handler(payload))
                } catch (e: TaskException) {
                    sendResultError(requestId, e.code, e.message ?: "task failed")
                    return@launch
                } catch (e: Exception) {
                    Log.w(TAG, "task $type failed", e)
                    sendResultError(requestId, "TASK_FAILED", e.message ?: e.javaClass.name)
                    return@launch
                }
                sendResultOk(requestId, result)
            }
        }
    }

    fun sendHello() {
        val hello = JSONObject()
            .put("v", 1)
            .put("type", "hello")
            .put(
                "client",
                JSONObject()
                    .put("platform", "android")
                    .put("version", OmniDebugLink.appVersion())
                    .put("osVersion", "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
                    .put("model", "${Build.MANUFACTURER} ${Build.MODEL}")
                    .put("libVersion", OmniDebugLink.LibVersion)
                    .put("actionsEnabled", OmniDebugLink.actionsEnabled)
            )
            .put("tasks", OmniDebugLink.tasks.tasksJson())
        sendRaw(hello.toString())
    }

    private fun sendResultOk(requestId: String, result: Any?) {
        val o = JSONObject()
            .put("v", 1)
            .put("type", "result")
            .put("requestId", requestId)
            .put("ok", true)
        o.put("result", result ?: JSONObject.NULL)
        sendRaw(o.toString())
    }

    private fun sendResultError(requestId: String, code: String, message: String) {
        val o = JSONObject()
            .put("v", 1)
            .put("type", "result")
            .put("requestId", requestId)
            .put("error", JSONObject().put("code", code).put("message", message))
        sendRaw(o.toString())
    }

    fun sendRaw(json: String) {
        val socket = ws
        if (socket == null || !connected) return
        try {
            socket.send(json)
        } catch (e: Exception) {
            Log.w(TAG, "send failed: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "OmniDebugLink"
    }
}

/** 单元素一次性完成器（协程 await 桥接 OkHttp 回调）。 */
internal class CompletableRef<T> {
    @Volatile
    private var done = false

    fun complete(@Suppress("UNUSED_PARAMETER") v: T? = null) {
        done = true
    }

    suspend fun await() {
        while (!done) delay(50)
    }
}

/** 返回值 JSON 化：JSONObject/JSONArray 原样，Map/List/标量递归包装。 */
internal object Jsons {
    fun fromAny(v: Any?): Any? = when (v) {
        null -> JSONObject.NULL
        is JSONObject -> v
        is JSONArray -> v
        is Map<*, *> -> {
            val o = JSONObject()
            for ((k, value) in v) o.put(k.toString(), fromAny(value))
            o
        }
        is Collection<*> -> {
            val a = JSONArray()
            for (e in v) a.put(fromAny(e))
            a
        }
        is Number, is Boolean, is String -> v
        is Enum<*> -> v.name
        else -> v.toString()
    }
}
