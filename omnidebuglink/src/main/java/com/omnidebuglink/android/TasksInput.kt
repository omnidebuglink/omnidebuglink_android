package com.omnidebuglink.android

import android.app.Instrumentation
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

/**
 * 坐标级输入。约定与 Unity 端不同的点：坐标 0-1 归一化、**原点左上**（Android 原生方向，不再换算）。
 * 事件直接 dispatch 到 decorView（走完整事件分发管线，listener 正常触发）。
 */
internal object TasksInput {

    private fun decor(): View = OmniDebugLink.currentActivity()?.window?.decorView
        ?: throw TaskException("NO_WINDOW", "no foreground activity; cannot dispatch input")

    private fun px(v: View, f: Float, size: Int) = (f * size).toInt().coerceIn(0, size)

    fun register(registry: TaskRegistry) {
        registry.register(
            "tap_screen",
            "Taps at normalized screen coordinates (x,y in 0..1, origin TOP-LEFT of the window). " +
                "Events are dispatched through the real touch pipeline on the decorView.",
            "{\"type\":\"object\",\"required\":[\"x\",\"y\"],\"properties\":{\"x\":{\"type\":\"number\"}," +
                "\"y\":{\"type\":\"number\"}}}"
        ) { p ->
            ensureActionsEnabled("tap_screen")
            val v = decor()
            val x = px(v, p.optDouble("x", -1.0).toFloat(), v.width)
            val y = px(v, p.optDouble("y", -1.0).toFloat(), v.height)
            dispatchTap(v, x, y)
            JSONObject().put("ok", true).put("px", JSONArray().put(x).put(y)).put("hit", hitJson(x, y))
        }

        registry.register(
            "swipe",
            "Swipes from (from) to (to) over durationMs (default 400), normalized 0..1 coordinates, " +
                "origin top-left. Keep both endpoints near the CENTER of the target control, not its edges. " +
                "Intermediate move events are dispatched every ~16ms so scroll/fling inertia works.",
            "{\"type\":\"object\",\"required\":[\"from\",\"to\"],\"properties\":{" +
                "\"from\":{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"number\"},\"y\":{\"type\":\"number\"}}}," +
                "\"to\":{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"number\"},\"y\":{\"type\":\"number\"}}}," +
                "\"durationMs\":{\"type\":\"integer\",\"default\":400}}}"
        ) { p ->
            ensureActionsEnabled("swipe")
            val v = decor()
            val from = p.optJSONObject("from") ?: throw TaskException("BAD_REQUEST", "from {x,y} required")
            val to = p.optJSONObject("to") ?: throw TaskException("BAD_REQUEST", "to {x,y} required")
            val x0 = px(v, from.optDouble("x", -1.0).toFloat(), v.width)
            val y0 = px(v, from.optDouble("y", -1.0).toFloat(), v.height)
            val x1 = px(v, to.optDouble("x", -1.0).toFloat(), v.width)
            val y1 = px(v, to.optDouble("y", -1.0).toFloat(), v.height)
            val duration = p.optInt("durationMs", 400).toLong().coerceAtLeast(50)
            dispatchSwipe(v, x0, y0, x1, y1, duration)
            JSONObject()
                .put("ok", true)
                .put("hitStart", hitJson(x0, y0))
                .put("hitEnd", hitJson(x1, y1))
        }

        registry.register(
            "long_press",
            "Presses and holds at normalized 0..1 coordinates (origin top-left) for durationMs (default 800) " +
                "then releases; triggers long-click without firing a plain click.",
            "{\"type\":\"object\",\"required\":[\"x\",\"y\"],\"properties\":{\"x\":{\"type\":\"number\"}," +
                "\"y\":{\"type\":\"number\"},\"durationMs\":{\"type\":\"integer\",\"default\":800}}}"
        ) { p ->
            ensureActionsEnabled("long_press")
            val v = decor()
            val x = px(v, p.optDouble("x", -1.0).toFloat(), v.width)
            val y = px(v, p.optDouble("y", -1.0).toFloat(), v.height)
            val duration = p.optInt("durationMs", 800).toLong().coerceAtLeast(200)
            dispatchSwipe(v, x, y, x, y, duration)
            JSONObject().put("ok", true).put("hit", hitJson(x, y))
        }

        registry.register(
            "send_key",
            "Sends a key event to the app: back|home|recents|menu|volume_up|volume_down|dpad_up|dpad_down|" +
                "dpad_left|dpad_right|dpad_center|enter|del|tab. back works via activity dispatch; home/recents " +
                "need system-level injection and may be rejected by the OS.",
            "{\"type\":\"object\",\"required\":[\"key\"],\"properties\":{\"key\":{\"type\":\"string\"}}}"
        ) { p ->
            ensureActionsEnabled("send_key")
            val key = p.optString("key")
            val code = KEY_MAP[key] ?: throw TaskException(
                "BAD_REQUEST", "unknown key \"$key\"; supported: ${KEY_MAP.keys.joinToString("|")}"
            )
            sendKey(code)
        }
    }

    /** 落点回执：事件实际落在哪个控件上（坐标命中测试），供 AI 发现"拖 A 动 B"类串扰。 */
    private fun hitJson(x: Int, y: Int): JSONObject? = try {
        UiTree.capture().hitTest(x, y)?.hitSummary()
    } catch (_: Exception) {
        null
    }

    private fun dispatchTap(v: View, x: Int, y: Int) {
        val now = SystemClock.uptimeMillis()
        v.dispatchTouchEvent(
            MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x.toFloat(), y.toFloat(), 0)
        )
        v.dispatchTouchEvent(
            MotionEvent.obtain(now, now + 40, MotionEvent.ACTION_UP, x.toFloat(), y.toFloat(), 0)
        )
    }

    private suspend fun dispatchSwipe(v: View, x0: Int, y0: Int, x1: Int, y1: Int, durationMs: Long) {
        val down = SystemClock.uptimeMillis()
        v.dispatchTouchEvent(
            MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x0.toFloat(), y0.toFloat(), 0)
        )
        val steps = (durationMs / 16).coerceIn(2, 200)
        for (i in 1 until steps) {
            val t = i.toFloat() / steps
            delay(durationMs / steps)
            v.dispatchTouchEvent(
                MotionEvent.obtain(
                    down, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE,
                    x0 + (x1 - x0) * t, y0 + (y1 - y0) * t, 0
                )
            )
        }
        v.dispatchTouchEvent(
            MotionEvent.obtain(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x1.toFloat(), y1.toFloat(), 0)
        )
    }

    private fun sendKey(keyCode: Int): JSONObject {
        // 1) Activity 管线（back/menu/dpad 等应用内按键可靠）
        val activity = OmniDebugLink.currentActivity()
        if (activity != null) {
            val now = SystemClock.uptimeMillis()
            val handledDown = activity.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
            activity.dispatchKeyEvent(KeyEvent(now, now + 10, KeyEvent.ACTION_UP, keyCode, 0))
            if (handledDown || keyCode != KeyEvent.KEYCODE_HOME) {
                return JSONObject().put("ok", true).put("via", "activity.dispatchKeyEvent")
            }
        }
        // 2) Instrumentation 注入（home/recents 只能走这条；可能被系统拒绝）
        return try {
            Instrumentation().sendKeyDownUpSync(keyCode)
            JSONObject().put("ok", true).put("via", "instrumentation")
        } catch (e: Exception) {
            throw TaskException(
                "NOT_INTERACTABLE",
                "key ${KeyEvent.keyCodeToString(keyCode)} rejected via both channels: " +
                    "activity=${activity != null}, instrumentation=${e.message}"
            )
        }
    }

    private val KEY_MAP = mapOf(
        "back" to KeyEvent.KEYCODE_BACK,
        "home" to KeyEvent.KEYCODE_HOME,
        "recents" to KeyEvent.KEYCODE_APP_SWITCH,
        "menu" to KeyEvent.KEYCODE_MENU,
        "volume_up" to KeyEvent.KEYCODE_VOLUME_UP,
        "volume_down" to KeyEvent.KEYCODE_VOLUME_DOWN,
        "dpad_up" to KeyEvent.KEYCODE_DPAD_UP,
        "dpad_down" to KeyEvent.KEYCODE_DPAD_DOWN,
        "dpad_left" to KeyEvent.KEYCODE_DPAD_LEFT,
        "dpad_right" to KeyEvent.KEYCODE_DPAD_RIGHT,
        "dpad_center" to KeyEvent.KEYCODE_DPAD_CENTER,
        "enter" to KeyEvent.KEYCODE_ENTER,
        "del" to KeyEvent.KEYCODE_DEL,
        "tab" to KeyEvent.KEYCODE_TAB,
    )
}
