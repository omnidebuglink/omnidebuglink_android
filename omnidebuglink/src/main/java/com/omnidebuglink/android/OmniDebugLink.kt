package com.omnidebuglink.android

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.net.URLEncoder

/**
 * OmniDebugLink Android 客户端入口。
 *
 * 接入：
 * ```kotlin
 * OmniDebugLink.start(context, "<clientToken>")
 * OmniDebugLink.stop()
 * ```
 *
 * start() 必须传 Application 级 context（内部注册 ActivityLifecycleCallbacks 做页面栈跟踪）。
 */
object OmniDebugLink {

    const val LibVersion = "0.2.0"

    /** 中继地址（写死，调用方只传 token；自建中继改这里重新打包）。 */
    const val RelayUrl = "wss://api.omnidebuglink.dev/ws"

    /** 心跳间隔：55s，让服务端 DO 可休眠。 */
    const val HeartbeatMs = 55_000L

    /** 看门狗：~180s 无服务端流量视为死连接，主动断开重连。 */
    const val WatchdogMs = 180_000L

    private const val TAG = "OmniDebugLink"

    /** 写操作总开关。false = 只读观察模式（随 hello 上报）。读 task 永远可用。 */
    @Volatile
    var actionsEnabled: Boolean = true

    lateinit var appContext: Context
        private set

    val tasks = TaskRegistry { connection?.sendHello() }

    internal val mainHandler = Handler(Looper.getMainLooper())
    internal val activityTracker = ActivityTracker()
    internal var connection: LinkConnection? = null
    internal var startedAtMs: Long = 0L
    private var started = false

    fun start(context: Context, clientToken: String) {
        synchronized(this) {
            if (started) {
                Log.w(TAG, "already started; call stop() first")
                return
            }
            appContext = context.applicationContext
            BuiltinTasks.registerAll(tasks)
            (appContext as? Application)?.registerActivityLifecycleCallbacks(activityTracker)
                ?: Log.w(TAG, "context is not an Application; get_state activity stack unavailable")
            startedAtMs = SystemClock.elapsedRealtime()
            val url = "$RelayUrl?token=${URLEncoder.encode(clientToken, "UTF-8")}"
            connection = LinkConnection(url)
            connection?.start()
            started = true
        }
    }

    fun stop() {
        synchronized(this) {
            if (!started) return
            started = false
            connection?.close()
            connection = null
            (appContext as? Application)?.unregisterActivityLifecycleCallbacks(activityTracker)
            activityTracker.reset()
        }
    }

    internal fun currentActivity(): Activity? = activityTracker.current

    internal fun appVersion(): String = try {
        appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName ?: "?"
    } catch (_: Exception) {
        "?"
    }
}

/** 写操作统一闸门：关掉时抛错（错误信息原样回传给 AI）。 */
internal fun ensureActionsEnabled(taskType: String) {
    if (!OmniDebugLink.actionsEnabled) {
        throw TaskException(
            "ACTIONS_DISABLED",
            "task \"$taskType\" blocked: OmniDebugLink.actionsEnabled = false (read-only observation mode)"
        )
    }
}

/** 带 error code 的 task 异常；其他异常统一按 TASK_FAILED 处理。 */
class TaskException(val code: String, message: String) : RuntimeException(message)

/** Activity 生命周期跟踪：当前前台 Activity + 历史（get_state 数据源，main thread 回调）。 */
internal class ActivityTracker : Application.ActivityLifecycleCallbacks {
    @Volatile
    var current: Activity? = null
        private set

    data class Entry(val className: String, val startedAt: Long)

    private val history = mutableListOf<Entry>()

    override fun onActivityStarted(activity: Activity) {
        current = activity
    }

    override fun onActivityResumed(activity: Activity) {
        current = activity
    }

    override fun onActivityPaused(activity: Activity) {}

    override fun onActivityStopped(activity: Activity) {
        if (current === activity) current = null
        history.add(Entry(activity.javaClass.name, System.currentTimeMillis()))
        if (history.size > 50) history.removeAt(0)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: android.os.Bundle?) {
        history.add(Entry(activity.javaClass.name, System.currentTimeMillis()))
        if (history.size > 50) history.removeAt(0)
    }

    override fun onActivitySaveInstanceState(activity: Activity, outState: android.os.Bundle) {}

    override fun onActivityDestroyed(activity: Activity) {
        if (current === activity) current = null
    }

    fun snapshotHistory(): List<Entry> = synchronized(history) { history.toList() }

    fun reset() {
        current = null
        synchronized(history) { history.clear() }
    }
}
