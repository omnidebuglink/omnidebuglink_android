package com.omnidebuglink.android

import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

/**
 * UI 观察/操作类 task。path 寻址与 UiTree 一致：view 子索引段 + Compose "#id" 段。
 * 坐标类（tap/swipe）见 TasksInput；这里都是控件级操作。
 */
internal object TasksUi {

    fun register(registry: TaskRegistry) {
        registry.register(
            "ui_traverse",
            "Dumps the current Activity's UI tree as nested JSON: classic Views plus Compose semantics nodes " +
                "(kind=view|compose). Each node carries path (for other tasks), class, text, id, bounds, visibility, " +
                "clickability. Caps at maxNodes with truncated=true.",
            "{\"type\":\"object\",\"properties\":{\"maxNodes\":{\"type\":\"integer\",\"default\":3000}}}"
        ) { p ->
            val tree = UiTree.capture()
            tree.traverseJson(p.optInt("maxNodes", UiTree.MAX_NODES))
        }

        registry.register(
            "find_objects",
            "Searches the current UI tree by substring match (case-insensitive) on any of text/id/desc/testTag/cls, " +
                "returning matching nodes' path + summary. Cheaper than ui_traverse for locating one control.",
            "{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"},\"id\":{\"type\":\"string\"}," +
                "\"desc\":{\"type\":\"string\"},\"testTag\":{\"type\":\"string\"},\"cls\":{\"type\":\"string\"}," +
                "\"limit\":{\"type\":\"integer\",\"default\":200}}}"
        ) { p ->
            if (!p.has("text") && !p.has("id") && !p.has("desc") && !p.has("testTag") && !p.has("cls")) {
                throw TaskException("BAD_REQUEST", "at least one of text/id/desc/testTag/cls is required")
            }
            val tree = UiTree.capture()
            val arr = JSONArray()
            for (e in tree.find(
                p.optString("text").takeIf { it.isNotEmpty() },
                p.optString("id").takeIf { it.isNotEmpty() },
                p.optString("desc").takeIf { it.isNotEmpty() },
                p.optString("testTag").takeIf { it.isNotEmpty() },
                p.optString("cls").takeIf { it.isNotEmpty() },
                p.optInt("limit", 200)
            )) {
                arr.put(e.summary())
            }
            JSONObject().put("matches", arr).put("count", arr.length())
        }

        registry.register(
            "view_component",
            "Inspects a single node by path (from ui_traverse/find_objects): full props plus children paths. " +
                "For View nodes also includes reflection-scanned readable fields (text-related getters).",
            "{\"type\":\"object\",\"required\":[\"path\"],\"properties\":{\"path\":{\"type\":\"string\"}}}"
        ) { p ->
            val tree = UiTree.capture()
            val entry = tree.resolve(p.optString("path"))
            val o = entry.summary()
            val children = JSONArray()
            for (c in entry.children) children.put(c.summary())
            o.put("children", children)
            entry.view?.let { o.put("fields", inspectFields(it)) }
            o
        }

        registry.register(
            "wait_for",
            "Polls every 200ms until a node matching text/id/desc/testTag appears (or timeoutMs elapses), " +
                "returning found=true/false plus the matched path(s) — timeouts are a result, not an error.",
            "{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"},\"id\":{\"type\":\"string\"}," +
                "\"desc\":{\"type\":\"string\"},\"testTag\":{\"type\":\"string\"},\"cls\":{\"type\":\"string\"}," +
                "\"timeoutMs\":{\"type\":\"integer\",\"default\":10000}}}"
        ) { p ->
            val timeout = p.optInt("timeoutMs", 10_000).toLong()
            val start = android.os.SystemClock.elapsedRealtime()
            var result = JSONObject().put("found", false)
            while (true) {
                val tree = UiTree.capture()
                val matches = tree.find(
                    p.optString("text").takeIf { it.isNotEmpty() },
                    p.optString("id").takeIf { it.isNotEmpty() },
                    p.optString("desc").takeIf { it.isNotEmpty() },
                    p.optString("testTag").takeIf { it.isNotEmpty() },
                    p.optString("cls").takeIf { it.isNotEmpty() },
                    5
                )
                if (matches.isNotEmpty()) {
                    val paths = JSONArray()
                    matches.forEach { paths.put(it.path) }
                    result = JSONObject()
                        .put("found", true)
                        .put("paths", paths)
                        .put("elapsedMs", android.os.SystemClock.elapsedRealtime() - start)
                    break
                }
                if (android.os.SystemClock.elapsedRealtime() - start >= timeout) {
                    result = JSONObject()
                        .put("found", false)
                        .put("elapsedMs", android.os.SystemClock.elapsedRealtime() - start)
                    break
                }
                delay(200)
            }
            result
        }

        registry.register(
            "ui_click",
            "Clicks a control by path: Views get performClick(); Compose nodes go through the accessibility " +
                "action channel. For locating the path first use find_objects or wait_for.",
            "{\"type\":\"object\",\"required\":[\"path\"],\"properties\":{\"path\":{\"type\":\"string\"}}}"
        ) { p ->
            ensureActionsEnabled("ui_click")
            val tree = UiTree.capture()
            val entry = tree.resolve(p.optString("path"))
            val view = entry.view
            if (view != null) {
                if (!view.isEnabled) throw TaskException("NOT_INTERACTABLE", "view is disabled: ${entry.path}")
                // performClick() 返回值只表示"是否有 OnClickListener 消费"，不代表成败：
                // Switch/CheckBox 无 listener 时返回 false 但状态照常切换。调用成功即 ok，原值放 handled。
                val handled = view.performClick()
                JSONObject().put("ok", true).put("via", "performClick").put("handled", handled)
            } else {
                val ok = ComposeBridge.performAction(entry, AccessibilityNodeInfo.ACTION_CLICK, null)
                if (!ok) throw TaskException(
                    "NOT_INTERACTABLE",
                    "compose node did not accept ACTION_CLICK (missing OnClick semantics?): ${entry.path}"
                )
                JSONObject().put("ok", true).put("via", "accessibility")
            }
        }

        registry.register(
            "input_text",
            "Sets text on a control by path: TextView/EditText via setText (TextWatcher fires normally); " +
                "Compose nodes via the accessibility ACTION_SET_TEXT channel.",
            "{\"type\":\"object\",\"required\":[\"path\",\"text\"],\"properties\":{\"path\":{\"type\":\"string\"}," +
                "\"text\":{\"type\":\"string\"}}}"
        ) { p ->
            ensureActionsEnabled("input_text")
            val text = p.optString("text")
            val tree = UiTree.capture()
            val entry = tree.resolve(p.optString("path"))
            val view = entry.view
            if (view != null) {
                if (view !is TextView) throw TaskException(
                    "NOT_INTERACTABLE",
                    "path resolves to ${view.javaClass.name}, not a TextView"
                )
                view.text = text
                JSONObject().put("ok", true).put("via", "setText")
            } else {
                val args = android.os.Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                }
                val ok = ComposeBridge.performAction(entry, AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                if (!ok) throw TaskException(
                    "NOT_INTERACTABLE",
                    "compose node did not accept ACTION_SET_TEXT (not an editable field?): ${entry.path}"
                )
                JSONObject().put("ok", true).put("via", "accessibility")
            }
        }

        registry.register(
            "set_component",
            "Mutates a View node by path: set any of text, visibility (visible|invisible|gone), enabled, selected. " +
                "Compose nodes are not settable this way (returned in error) — drive them via input_text/ui_click.",
            "{\"type\":\"object\",\"required\":[\"path\"],\"properties\":{\"path\":{\"type\":\"string\"}," +
                "\"text\":{\"type\":\"string\"},\"visibility\":{\"type\":\"string\",\"enum\":[\"visible\",\"invisible\",\"gone\"]}," +
                "\"enabled\":{\"type\":\"boolean\"},\"selected\":{\"type\":\"boolean\"}}}"
        ) { p ->
            ensureActionsEnabled("set_component")
            val tree = UiTree.capture()
            val entry = tree.resolve(p.optString("path"))
            val view = entry.view ?: throw TaskException(
                "NOT_SUPPORTED",
                "compose nodes cannot be set via set_component; use input_text/ui_click instead: ${entry.path}"
            )
            val applied = JSONArray()
            p.optString("text").takeIf { it.isNotEmpty() }?.let {
                (view as? TextView) ?: throw TaskException(
                    "NOT_INTERACTABLE", "${view.javaClass.name} is not a TextView"
                )
                view.text = it
                applied.put("text")
            }
            p.optString("visibility").takeIf { it.isNotEmpty() }?.let {
                view.visibility = when (it) {
                    "visible" -> View.VISIBLE
                    "invisible" -> View.INVISIBLE
                    "gone" -> View.GONE
                    else -> throw TaskException("BAD_REQUEST", "visibility must be visible|invisible|gone")
                }
                applied.put("visibility")
            }
            if (p.has("enabled")) {
                view.isEnabled = p.getBoolean("enabled")
                applied.put("enabled")
            }
            if (p.has("selected")) {
                view.isSelected = p.getBoolean("selected")
                applied.put("selected")
            }
            if (applied.length() == 0) throw TaskException(
                "BAD_REQUEST", "nothing to set: provide text/visibility/enabled/selected"
            )
            JSONObject().put("ok", true).put("applied", applied)
        }
    }

    /**
     * 语义状态字段优先输出（checked/progress/rating 等），其余反射扫描补齐。
     * 反射按字母序 ~40 个截断会把这些关键状态挤掉，所以必须显式置前。
     */
    private fun inspectFields(view: View): JSONObject {
        val out = JSONObject()
        try {
            if (view is android.widget.CompoundButton) out.put("checked", view.isChecked)
            if (view is android.widget.RatingBar) out.put("rating", view.rating)
            if (view is android.widget.ProgressBar) {
                out.put("progress", view.progress).put("max", view.max)
                try {
                    out.put("secondaryProgress", view.secondaryProgress)
                } catch (_: Exception) {
                    // indeterminate ProgressBar 会抛异常，忽略
                }
            }
        } catch (_: Exception) {
        }
        reflectFields(view, out)
        return out
    }

    /** 反射扫 View 的可读字段：无参 getter 返回 String/原始类型，追加到 out 已有键之后，上限 30 个新增。 */
    private fun reflectFields(view: View, out: JSONObject) {
        try {
            var count = 0
            val skip = setOf(
                "getContext", "getResources", "getParent", "getRootView", "getApplicationWindowToken",
                "getWindowToken", "getWindowId", "getHandler", "getViewTreeObserver", "getDrawingCache",
                "getOverlay", "getLayoutAnimationController", "getKeyDispatcherState", "getListenerInfo",
            )
            for (m in view.javaClass.methods) {
                if (count >= 30) break
                if (!m.name.startsWith("get") || m.parameterCount != 0) continue
                if (m.name in skip) continue
                val rt = m.returnType
                if (rt != java.lang.String::class.java && !rt.isPrimitive) continue
                if (m.name == "getId") continue
                val key = m.name.removePrefix("get").replaceFirstChar { it.lowercaseChar() }
                if (out.has(key)) continue
                try {
                    val v = m.invoke(view) ?: continue
                    out.put(key, v.toString())
                    count++
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        }
    }
}
