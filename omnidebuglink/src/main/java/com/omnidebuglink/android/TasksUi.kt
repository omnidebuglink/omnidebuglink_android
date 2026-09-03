package com.omnidebuglink.android

import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

/**
 * UI 观察/操作类 task。path 寻址与 UiTree 一致：view 子索引段 + Compose "#id" 段。
 * 坐标类（tap/swipe）见 TasksInput；ui_click 为物理投递（中心点触摸），其余控件级操作。
 */
internal object TasksUi {

    /**
     * 物理点击：取 view 中心，在其自己的 window root 上派发完整触摸序列（与 RN 端同法，
     * Dialog/PopupWindow 中的目标也能正确命中；指引遮罩等覆盖层像真人点击一样收到事件）。
     * 返回 null 表示坐标不可算（无尺寸/未附着），调用方回退 performClick。
     */
    private fun dispatchPhysicalClick(view: View): JSONObject? {
        if (view.width <= 0 || view.height <= 0 || !view.isAttachedToWindow) return null
        val root = view.rootView
        if (root.width <= 0 || root.height <= 0) return null
        val targetLoc = IntArray(2)
        view.getLocationOnScreen(targetLoc)
        val rootLoc = IntArray(2)
        root.getLocationOnScreen(rootLoc)
        val cx = (targetLoc[0] - rootLoc[0] + view.width / 2f).toInt()
        val cy = (targetLoc[1] - rootLoc[1] + view.height / 2f).toInt()
        TasksInput.dispatchTap(root, cx, cy)
        return JSONObject()
            .put("via", "touch")
            .put("px", JSONArray().put(cx).put(cy))
            .put(
                "inDialogWindow",
                root !== OmniDebugLink.currentActivity()?.window?.decorView
            )
    }

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
            "Clicks a control, delivered physically: a full touch sequence at the view's " +
                "center, dispatched on its own window root — overlays (guide masks, intercept layers) " +
                "receive the click exactly like a real tap. Locate either by path, or directly by " +
                "text/id/desc/testTag (+index) with the same matching as find_objects — locating and " +
                "clicking happen atomically in one snapshot. Falls back to performClick() when the " +
                "view has no usable size; Compose nodes go through the accessibility action channel.",
            "{\"type\":\"object\",\"properties\":{" +
                "\"path\":{\"type\":\"string\",\"description\":\"node path from ui_traverse/find_objects\"}," +
                "\"text\":{\"type\":\"string\",\"description\":\"displayed text substring (case-insensitive), e.g. the label on the button\"}," +
                "\"id\":{\"type\":\"string\",\"description\":\"view id substring\"}," +
                "\"desc\":{\"type\":\"string\",\"description\":\"content-description substring\"}," +
                "\"testTag\":{\"type\":\"string\",\"description\":\"Compose testTag substring\"}," +
                "\"cls\":{\"type\":\"string\",\"description\":\"view class substring\"}," +
                "\"index\":{\"type\":\"integer\",\"default\":0,\"description\":\"which match to use when several nodes match\"}" +
                "}}"
        ) { p ->
            ensureActionsEnabled("ui_click")
            val tree = UiTree.capture()
            val path = p.optString("path")
            val entry = if (path.isNotEmpty()) {
                tree.resolve(path)
            } else {
                val hasFilter = listOf("text", "id", "desc", "testTag", "cls").any { p.has(it) && p.optString(it).isNotEmpty() }
                if (!hasFilter) throw TaskException(
                    "BAD_REQUEST",
                    "provide 'path' or at least one of text/id/desc/testTag/cls (same filters as find_objects)"
                )
                val matches = tree.find(
                    p.optString("text").takeIf { it.isNotEmpty() },
                    p.optString("id").takeIf { it.isNotEmpty() },
                    p.optString("desc").takeIf { it.isNotEmpty() },
                    p.optString("testTag").takeIf { it.isNotEmpty() },
                    p.optString("cls").takeIf { it.isNotEmpty() },
                    200
                )
                if (matches.isEmpty()) throw TaskException(
                    "NOT_FOUND",
                    "no node matches the given filter; run find_objects to see what is on screen"
                )
                val index = p.optInt("index", 0)
                if (index < 0 || index >= matches.size) throw TaskException(
                    "NOT_FOUND",
                    "${matches.size} nodes match the filter, index $index is out of range"
                )
                matches[index]
            }
            val view = entry.view
            if (view != null) {
                if (!view.isEnabled) throw TaskException("NOT_INTERACTABLE", "view is disabled: ${entry.path}")
                val physical = dispatchPhysicalClick(view)
                if (physical != null) {
                    physical.put("ok", true).put("path", entry.path)
                } else {
                    // performClick() 返回值只表示"是否有 OnClickListener 消费"，不代表成败：
                    // Switch/CheckBox 无 listener 时返回 false 但状态照常切换。调用成功即 ok，原值放 handled。
                    val handled = view.performClick()
                    JSONObject().put("ok", true).put("via", "performClick").put("handled", handled)
                        .put("path", entry.path)
                }
            } else {
                val ok = ComposeBridge.performAction(entry, AccessibilityNodeInfo.ACTION_CLICK, null)
                if (!ok) throw TaskException(
                    "NOT_INTERACTABLE",
                    "compose node did not accept ACTION_CLICK (missing OnClick semantics?): ${entry.path}"
                )
                JSONObject().put("ok", true).put("via", "accessibility").put("path", entry.path)
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
