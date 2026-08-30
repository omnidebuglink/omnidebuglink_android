package com.omnidebuglink.android

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.SystemClock
import android.util.Base64
import android.util.DisplayMetrics
import android.view.Choreographer
import android.view.View
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 设备/应用状态类：截图（__odl_file 信封）、logcat、性能、页面栈/Intent/权限、SharedPreferences、启动 Intent。
 */
internal object TasksDevice {

    fun register(registry: TaskRegistry) {
        registerScreenshot(registry)
        registerLogs(registry)
        registerPerf(registry)
        registerState(registry)
        registerPrefs(registry)
        registerLaunchIntent(registry)
    }

    // ---------------------------------------------------------------- screenshot

    private fun registerScreenshot(registry: TaskRegistry) {
        registry.register(
            "screenshot",
            "Captures the current Activity's window as JPEG (rendered from the view tree) and returns it " +
                "as an image envelope plus dimensions. scale caps the longest edge in pixels (default 1080); " +
                "quality is JPEG quality 1-100 (default 70). Screenshots of other apps are not possible.",
            "{\"type\":\"object\",\"properties\":{\"scale\":{\"type\":\"integer\",\"default\":1080}," +
                "\"quality\":{\"type\":\"integer\",\"default\":70}}}"
        ) { p ->
            val v = OmniDebugLink.currentActivity()?.window?.decorView
                ?: throw TaskException("NO_WINDOW", "no foreground activity; nothing to capture")
            var scale = p.optInt("scale", 1080)
            var quality = p.optInt("quality", 70).coerceIn(10, 100)
            var out: ByteArray
            var w = 0
            var h = 0
            while (true) {
                val bmp = render(v, scale)
                w = bmp.width
                h = bmp.height
                out = compress(bmp, quality)
                bmp.recycle()
                // 单消息 ~900KB 上限，base64 再膨胀 4/3：超限就递减质量/尺寸
                if (out.size < 650_000 || quality <= 20) break
                if (quality > 40) quality -= 20 else scale = (scale * 0.7f).toInt()
            }
            JSONObject()
                .put("__odl_file", JSONObject().put("mime", "image/jpeg").put("data", Base64.encodeToString(out, Base64.NO_WRAP)))
                .put("width", w)
                .put("height", h)
                .put("bytes", out.size)
        }
    }

    private fun render(v: View, maxEdge: Int): Bitmap {
        if (v.width <= 0 || v.height <= 0) throw TaskException("NO_WINDOW", "window has no valid size")
        var w = v.width
        var h = v.height
        val longEdge = maxOf(w, h)
        if (longEdge > maxEdge) {
            val r = maxEdge.toFloat() / longEdge
            w = (w * r).toInt().coerceAtLeast(1)
            h = (h * r).toInt().coerceAtLeast(1)
        }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
        val canvas = Canvas(bmp)
        canvas.scale(w.toFloat() / v.width, h.toFloat() / v.height)
        v.draw(canvas)
        return bmp
    }

    private fun compress(bmp: Bitmap, quality: Int): ByteArray {
        val bos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, quality, bos)
        return bos.toByteArray()
    }

    // ---------------------------------------------------------------- read_logs

    private fun registerLogs(registry: TaskRegistry) {
        registry.register(
            "read_logs",
            "Reads the app's own logcat buffer (all historical lines the buffer still holds, unlike a " +
                "subscription model). Filters: level (V|D|I|W|E, minimum severity), contains (substring), " +
                "limit (default 200, most recent), sinceMs (epoch millis cutoff).",
            "{\"type\":\"object\",\"properties\":{\"level\":{\"type\":\"string\",\"enum\":[\"V\",\"D\",\"I\",\"W\",\"E\"]}," +
                "\"contains\":{\"type\":\"string\"},\"limit\":{\"type\":\"integer\",\"default\":200}," +
                "\"sinceMs\":{\"type\":\"integer\"}}}"
        ) { p ->
            withContext(Dispatchers.IO) {
                val pid = android.os.Process.myPid()
                val proc = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-v", "time", "--pid=$pid"))
                val lines = proc.inputStream.bufferedReader().readLines()
                proc.waitFor()
                val minLevel = LEVELS.indexOf(p.optString("level").takeIf { it.isNotEmpty() } ?: "V")
                val contains = p.optString("contains").takeIf { it.isNotEmpty() }
                val since = if (p.has("sinceMs")) p.optLong("sinceMs") else 0L
                val limit = p.optInt("limit", 200)

                data class Line(val ts: Long, val level: String, val tag: String, val msg: String)

                val parsed = mutableListOf<Line>()
                val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
                val year = SimpleDateFormat("yyyy", Locale.US).format(Date())
                for (raw in lines) {
                    // 格式：MM-DD HH:MM:SS.mmm L/TAG: message
                    if (raw.length < 20) continue
                    val ts = try {
                        fmt.parse(raw.substring(0, 18).let { "$year-$it" })?.time ?: 0L
                    } catch (_: Exception) {
                        0L
                    }
                    val rest = raw.substring(19).trim()
                    if (rest.length < 3) continue
                    val level = rest.substring(0, 1)
                    val li = LEVELS.indexOf(level)
                    if (li < 0 || li < minLevel) continue
                    val tag = rest.substringAfter("/", rest).substringBefore(":").trim()
                    val msg = rest.substringAfter(":", rest).trim()
                    if (ts != 0L && since > 0 && ts < since) continue
                    if (contains != null && !(raw.contains(contains) || msg.contains(contains))) continue
                    parsed.add(Line(ts, level, tag, msg))
                }
                val arr = JSONArray()
                for (l in parsed.takeLast(limit)) {
                    arr.put(
                        JSONObject()
                            .put("ts", l.ts)
                            .put("level", l.level)
                            .put("tag", l.tag)
                            .put("message", l.msg)
                    )
                }
                JSONObject().put("lines", arr).put("count", arr.length()).put("totalInBuffer", parsed.size)
            }
        }
    }

    private val LEVELS = listOf("V", "D", "I", "W", "E")

    // ---------------------------------------------------------------- get_perf

    private fun registerPerf(registry: TaskRegistry) {
        registry.register(
            "get_perf",
            "Performance snapshot: Java/native heap, PSS, battery, thread count, and optionally an fps " +
                "sample (frames: number of Choreographer frames to sample, default 60, skipped when 0).",
            "{\"type\":\"object\",\"properties\":{\"frames\":{\"type\":\"integer\",\"default\":60}}}"
        ) { p ->
            val ctx = OmniDebugLink.appContext
            val rt = Runtime.getRuntime()
            val mi = ActivityManager.MemoryInfo()
            (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
            val o = JSONObject()
                .put(
                    "javaHeap",
                    JSONObject()
                        .put("usedMb", (rt.totalMemory() - rt.freeMemory()) / 1048576.0)
                        .put("maxMb", rt.maxMemory() / 1048576.0)
                )
                .put(
                    "deviceMemory",
                    JSONObject()
                        .put("availMb", mi.availMem / 1048576.0)
                        .put("totalMb", mi.totalMem / 1048576.0)
                        .put("lowMemory", mi.lowMemory)
                )
                .put("pssKb", Debug.getPss())
                .put("nativeHeapKb", Debug.getNativeHeapAllocatedSize() / 1024)
                .put("battery", batteryJson(ctx))
                .put("threads", readThreadCount())

            val frames = p.optInt("frames", 60)
            if (frames > 0) o.put("fps", sampleFps(frames))
            o
        }
    }

    private fun batteryJson(ctx: Context): JSONObject {
        return try {
            val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                ?: return JSONObject().put("unavailable", true)
            val level = i.getIntExtra("level", -1)
            val scale = i.getIntExtra("scale", 100)
            JSONObject()
                .put("percent", if (level >= 0) level * 100 / scale else -1)
                .put(
                    "status",
                    when (i.getIntExtra("status", -1)) {
                        2 -> "charging"
                        3 -> "discharging"
                        4 -> "not_charging"
                        5 -> "full"
                        else -> "unknown"
                    }
                )
                .put("temperatureC", i.getIntExtra("temperature", 0) / 10.0)
        } catch (_: Exception) {
            JSONObject().put("unavailable", true)
        }
    }

    private fun readThreadCount(): Int = try {
        File("/proc/self/status").useLines { lines ->
            lines.firstOrNull { it.startsWith("Threads:") }?.substringAfter(":")?.trim()?.toInt() ?: -1
        }
    } catch (_: Exception) {
        -1
    }

    private fun sampleFps(n: Int): JSONObject {
        val times = mutableListOf<Long>()
        var done = false
        val cb = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                times.add(frameTimeNanos / 1_000_000)
                if (times.size >= n) done = true
                else Choreographer.getInstance().postFrameCallback(this)
            }
        }
        Choreographer.getInstance().postFrameCallback(cb)
        val start = SystemClock.elapsedRealtime()
        while (!done && SystemClock.elapsedRealtime() - start < 5_000) {
            Thread.sleep(16)
        }
        Choreographer.getInstance().removeFrameCallback(cb)
        if (times.size < 2) return JSONObject().put("unavailable", true)
        val deltas = (1 until times.size).map { (times[it] - times[it - 1]).toDouble() }.sorted()
        fun pct(q: Double) = deltas[(deltas.size * q).toInt().coerceAtMost(deltas.size - 1)]
        return JSONObject()
            .put("sampledFrames", times.size)
            .put("fps", (times.size - 1) * 1000.0 / (times.last() - times.first()))
            .put("frameMsP50", pct(0.50))
            .put("frameMsP95", pct(0.95))
            .put("frameMsP99", pct(0.99))
    }

    // ---------------------------------------------------------------- get_state

    private fun registerState(registry: TaskRegistry) {
        registry.register(
            "get_state",
            "App state snapshot: foreground activity + recent activity history (stack), current intent, " +
                "fragment tree of the foreground activity (FragmentActivity only), screen metrics, granted " +
                "dangerous permissions, network status, locale.",
            "{\"type\":\"object\",\"properties\":{}}"
        ) { _ ->
            val ctx = OmniDebugLink.appContext
            val activity = OmniDebugLink.currentActivity()
            val o = JSONObject()
                .put("foregroundActivity", activity?.javaClass?.name ?: "(none)")
                .put("activityHistory", JSONArray().apply {
                    OmniDebugLink.activityTracker.snapshotHistory().forEach {
                        put(JSONObject().put("class", it.className).put("at", it.startedAt))
                    }
                })
                .put("screen", screenJson(ctx))
                .put("permissions", permissionsJson(ctx))
                .put("network", networkJson(ctx))
                .put("locale", Locale.getDefault().toString())
            if (activity != null) {
                o.put("intent", intentJson(activity.intent))
                o.put("fragments", fragmentsJson(activity))
            }
            o
        }
    }

    private fun screenJson(ctx: Context): JSONObject {
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        (ctx.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay.getRealMetrics(dm)
        return JSONObject()
            .put("widthPx", dm.widthPixels)
            .put("heightPx", dm.heightPixels)
            .put("density", dm.densityDpi / 160.0)
    }

    private fun permissionsJson(ctx: Context): JSONArray {
        val arr = JSONArray()
        try {
            val info = ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.GET_PERMISSIONS)
            val requested = info.requestedPermissions ?: return arr
            for (perm in requested) {
                if (ctx.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED &&
                    perm.startsWith("android.permission.")
                ) {
                    // 只列危险权限组内的常见项太复杂；全列 granted 项，前缀信息足够 AI 判断
                    arr.put(perm)
                }
            }
        } catch (_: Exception) {
        }
        return arr
    }

    private fun networkJson(ctx: Context): JSONObject {
        return try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val nw = cm.activeNetwork ?: return JSONObject().put("connected", false)
            val caps = cm.getNetworkCapabilities(nw) ?: return JSONObject().put("connected", false)
            val type = when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                else -> "other"
            }
            JSONObject().put("connected", true).put("type", type)
        } catch (_: Exception) {
            JSONObject().put("unavailable", true)
        }
    }

    private fun intentJson(intent: Intent?): JSONObject? {
        intent ?: return null
        val o = JSONObject()
            .put("action", intent.action ?: "")
            .put("data", intent.dataString ?: "")
            .put("flags", intent.flags)
        val extras = intent.extras
        if (extras != null && !extras.isEmpty) {
            val e = JSONObject()
            for (key in extras.keySet()) {
                val v: Any = try {
                    extras.get(key) ?: continue
                } catch (_: Exception) {
                    continue
                }
                e.put(key, Jsons.fromAny(if (v is Bundle) v.toString() else v))
            }
            o.put("extras", e)
        }
        return o
    }

    private fun fragmentsJson(activity: android.app.Activity): JSONArray {
        val arr = JSONArray()
        val fm = try {
            (activity as? androidx.fragment.app.FragmentActivity)?.supportFragmentManager
        } catch (_: Throwable) {
            // androidx 不在 classpath（宿主不用 fragments）时上面 as? 也不会执行到这里——反射兜底
            null
        }
        if (fm != null) {
            fun walk(parent: androidx.fragment.app.FragmentManager, depth: Int) {
                for (f in parent.fragments) {
                    arr.put(
                        JSONObject()
                            .put("class", f.javaClass.name)
                            .put("depth", depth)
                            .put("isVisible", f.isVisible)
                    )
                    walk(f.childFragmentManager, depth + 1)
                }
            }
            walk(fm, 0)
        }
        return arr
    }

    // ---------------------------------------------------------------- prefs

    private fun registerPrefs(registry: TaskRegistry) {
        registry.register(
            "prefs",
            "SharedPreferences access: action get|set|delete|list. get/list return values; set takes key+value " +
                "(type string|int|long|float|bool, default string); delete takes key. file defaults to the " +
                "app's main preferences.",
            "{\"type\":\"object\",\"required\":[\"action\"],\"properties\":{\"action\":{\"type\":\"string\"," +
                "\"enum\":[\"get\",\"set\",\"delete\",\"list\"]},\"file\":{\"type\":\"string\"}," +
                "\"key\":{\"type\":\"string\"},\"value\":{\"type\":{}},\"type\":{\"type\":\"string\"}}}"
        ) { p ->
            val action = p.optString("action")
            if (action == "set") ensureActionsEnabled("prefs:set")
            if (action == "delete") ensureActionsEnabled("prefs:delete")
            val name = p.optString("file").takeIf { it.isNotEmpty() } ?: "${OmniDebugLink.appContext.packageName}_preferences"
            val sp = OmniDebugLink.appContext.getSharedPreferences(name, Context.MODE_PRIVATE)
            val key = p.optString("key")
            when (action) {
                "get" -> {
                    if (key.isEmpty()) throw TaskException("BAD_REQUEST", "key required for get")
                    if (!sp.contains(key)) throw TaskException("NOT_FOUND", "pref \"$key\" not found in $name")
                    JSONObject().put("key", key).put("value", sp.all[key])
                }
                "list" -> JSONObject().put("file", name).put("count", sp.all.size).put("entries", Jsons.fromAny(sp.all))
                "set" -> {
                    if (key.isEmpty()) throw TaskException("BAD_REQUEST", "key required for set")
                    val type = p.optString("type").takeIf { it.isNotEmpty() } ?: "string"
                    sp.edit().apply {
                        when (type) {
                            "int" -> putInt(key, p.optInt("value"))
                            "long" -> putLong(key, p.optLong("value"))
                            "float" -> putFloat(key, p.optDouble("value").toFloat())
                            "bool" -> putBoolean(key, p.optBoolean("value"))
                            else -> putString(key, p.opt("value")?.toString())
                        }
                    }.apply()
                    JSONObject().put("ok", true).put("key", key)
                }
                "delete" -> {
                    if (key.isEmpty()) throw TaskException("BAD_REQUEST", "key required for delete")
                    sp.edit().remove(key).apply()
                    JSONObject().put("ok", true).put("key", key)
                }
                else -> throw TaskException("BAD_REQUEST", "action must be get|set|delete|list")
            }
        }
    }

    // ---------------------------------------------------------------- launch_intent

    private fun registerLaunchIntent(registry: TaskRegistry) {
        registry.register(
            "launch_intent",
            "Starts an Activity: either a deep link (uri, auto ACTION_VIEW) or an explicit component " +
                "(package + className) or an action string. extras (flat string map) optional. " +
                "Useful as the entry point of automated test flows.",
            "{\"type\":\"object\",\"properties\":{\"uri\":{\"type\":\"string\"},\"package\":{\"type\":\"string\"}," +
                "\"className\":{\"type\":\"string\"},\"action\":{\"type\":\"string\"}," +
                "\"extras\":{\"type\":\"object\",\"additionalProperties\":{\"type\":\"string\"}}}}"
        ) { p ->
            ensureActionsEnabled("launch_intent")
            val intent = when {
                p.optString("uri").isNotEmpty() ->
                    Intent(Intent.ACTION_VIEW, android.net.Uri.parse(p.optString("uri")))
                p.optString("className").isNotEmpty() -> {
                    val i = Intent()
                    val pkg = p.optString("package").takeIf { it.isNotEmpty() } ?: OmniDebugLink.appContext.packageName
                    i.setClassName(pkg, p.optString("className"))
                    i
                }
                p.optString("action").isNotEmpty() -> Intent(p.optString("action"))
                else -> throw TaskException("BAD_REQUEST", "one of uri/className/action is required")
            }
            p.optJSONObject("extras")?.let {
                val keys = it.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    intent.putExtra(k, it.optString(k))
                }
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            OmniDebugLink.appContext.startActivity(intent)
            JSONObject().put("ok", true).put("started", intent.component?.className ?: intent.dataString ?: intent.action)
        }
    }
}
