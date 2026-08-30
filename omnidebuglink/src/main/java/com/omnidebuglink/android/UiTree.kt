package com.omnidebuglink.android

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject

/**
 * UI 树快照：统一覆盖传统 View 层级和 Compose 语义树。
 *
 * 寻址方案（path，根为当前 Activity 的 decorView）：
 * - "0" = 根；"/" 分段
 * - 数字段 = ViewGroup 子索引，如 "0/1/3"
 *- "#<id>" 段 = Compose 语义节点（id 为 Compose 语义树节点 id），如 "0/2/#17"
 *
 * 每次执行 task 时重新 capture（树是活的，path 只在单次 task 生命周期内有效）。
 * Compose 探测：宿主 app 不用 Compose 时相关类不存在，全部走反射 + Class.forName 守卫，缺库零影响。
 */
internal class UiTree private constructor(private val root: UiEntry) {

    companion object {
        const val MAX_NODES = 3000

        fun capture(): UiTree {
            val decor = OmniDebugLink.currentActivity()?.window?.decorView
                ?: throw TaskException("NO_WINDOW", "no foreground activity; cannot access the view hierarchy")
            val rootEntry = walkView(decor, null, "0", IntArray(1))
            return UiTree(rootEntry)
        }

        private fun walkView(view: View, parent: UiEntry?, path: String, counter: IntArray): UiEntry {
            counter[0]++
            val entry = UiEntry(parent, path)
            entry.view = view
            val root = findRoot(view)
            val bounds = windowRelativeBounds(view, root)
            val p = JSONObject()
                .put("kind", "view")
                .put("class", view.javaClass.simpleName)
                .put("visible", view.isShown)
                .put("enabled", view.isEnabled)
                .put("clickable", view.isClickable)
                .put("bounds", JSONArray().put(bounds.left).put(bounds.top).put(bounds.right).put(bounds.bottom))
            if (view.id != View.NO_ID) {
                try {
                    p.put("id", view.resources.getResourceEntryName(view.id))
                } catch (_: Exception) {
                }
            }
            (view as? TextView)?.let { t ->
                t.text?.toString()?.takeIf { it.isNotEmpty() }?.let { p.put("text", it) }
                t.hint?.toString()?.takeIf { it.isNotEmpty() }?.let { p.put("hint", it) }
            }
            view.contentDescription?.toString()?.takeIf { it.isNotEmpty() }?.let { p.put("desc", it) }
            entry.props = p

            if (counter[0] < MAX_NODES) {
                if (ComposeBridge.isComposeHost(view)) {
                    val semRoot = ComposeBridge.rootSemanticsNode(view)
                    if (semRoot != null) {
                        entry.children.add(walkSem(semRoot, view, entry, "${path}/#${ComposeBridge.nodeId(semRoot)}", counter))
                    }
                }
                val vg = view as? ViewGroup
                if (vg != null) {
                    for (i in 0 until vg.childCount) {
                        if (counter[0] >= MAX_NODES) break
                        vg.getChildAt(i)?.let { child ->
                            entry.children.add(walkView(child, entry, "$path/$i", counter))
                        }
                    }
                }
            }
            return entry
        }

        private fun walkSem(node: Any, host: View, parent: UiEntry, path: String, counter: IntArray): UiEntry {
            counter[0]++
            val entry = UiEntry(parent, path)
            entry.semNode = node
            entry.composeHost = host
            val props = ComposeBridge.describe(node)
            props.put("kind", "compose")
            props.put("path", path)
            entry.props = props
            if (counter[0] < MAX_NODES) {
                for (child in ComposeBridge.nodeChildren(node)) {
                    if (counter[0] >= MAX_NODES) break
                    val id = ComposeBridge.nodeId(child)
                    entry.children.add(walkSem(child, host, entry, "$path/#$id", counter))
                }
            }
            return entry
        }

        private fun findRoot(view: View): View {
            var v = view
            while (v.parent is View) v = v.parent as View
            return v
        }

        private fun windowRelativeBounds(view: View, root: View): Rect {
            val loc = IntArray(2)
            val rootLoc = IntArray(2)
            view.getLocationOnScreen(loc)
            root.getLocationOnScreen(rootLoc)
            return Rect(
                loc[0] - rootLoc[0], loc[1] - rootLoc[1],
                loc[0] - rootLoc[0] + view.width, loc[1] - rootLoc[1] + view.height
            )
        }
    }

    fun resolve(path: String): UiEntry {
        val segments = path.trim('/').split("/")
        if (segments.isEmpty() || segments[0] != rootPathRoot) {
            throw TaskException("BAD_PATH", "path must start with \"$rootPathRoot\" (the decorView)")
        }
        var entry: UiEntry = root
        for (seg in segments.drop(1)) {
            val next = entry.children.firstOrNull {
                if (seg.startsWith("#")) it.path.substringAfterLast('/') == seg
                else it.path.substringAfterLast('/') == seg
            } ?: throw TaskException(
                "NOT_FOUND",
                "path \"$path\" no longer resolves (UI changed since it was captured); re-run ui_traverse/find_objects"
            )
            entry = next
        }
        return entry
    }

    /** 条件查找（text/id/desc/testTag 子串，或 cls 类名子串）。返回条目列表。 */
    fun find(
        text: String?, id: String?, desc: String?, testTag: String?, cls: String?,
        limit: Int = 200,
    ): List<UiEntry> {
        val out = mutableListOf<UiEntry>()
        fun visit(e: UiEntry) {
            if (out.size >= limit) return
            val p = e.props
            val match =
                (text != null && p.optString("text").contains(text, true)) ||
                    (id != null && p.optString("id").contains(id, true)) ||
                    (desc != null && p.optString("desc").contains(desc, true)) ||
                    (testTag != null && p.optString("testTag").contains(testTag, true)) ||
                    (cls != null && p.optString("class").contains(cls, true))
            if (match && p.optBoolean("visible", true)) out.add(e)
            e.children.forEach(::visit)
        }
        visit(root)
        return out
    }

    fun traverseJson(maxNodes: Int): JSONObject {
        var count = 0
        fun toJson(e: UiEntry): JSONObject {
            count++
            val o = JSONObject(e.props.toString())
            o.put("path", e.path)
            if (e.children.isNotEmpty()) {
                val arr = JSONArray()
                for (c in e.children) {
                    if (count >= maxNodes) {
                        o.put("truncated", true)
                        break
                    }
                    arr.put(toJson(c))
                }
                o.put("children", arr)
            }
            return o
        }
        return toJson(root)
    }

    class UiEntry(val parent: UiEntry?, val path: String) {
        var view: View? = null
        var semNode: Any? = null
        var composeHost: View? = null
        var props: JSONObject = JSONObject()
        val children = mutableListOf<UiEntry>()

        fun summary(): JSONObject {
            val o = JSONObject(props.toString())
            o.put("path", path)
            return o
        }
    }
}

private const val rootPathRoot = "0"

/**
 * Compose 语义树反射桥。宿主没有 Compose 时 available=false，一切调用为 no-op。
 * 反射面收敛在少量方法名上：AndroidComposeView.getSemanticsOwner（internal → 带 $ui_release 后缀）
 * 和 SemanticsOwner.getRootSemanticsNode；拿到 SemanticsNode 后其 public API 仍需反射调用
 * （compileOnly 依赖，避免把 Compose 强加给不用它的 app）。
 */
internal object ComposeBridge {

    val available: Boolean by lazy {
        try {
            Class.forName("androidx.compose.ui.semantics.SemanticsNode")
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun isComposeHost(view: View): Boolean {
        if (!available) return false
        if (!view.javaClass.name.endsWith("AndroidComposeView")) return false
        // 有 AccessibilityNodeProvider 说明语义树确实挂在这个 view 上
        return try {
            view.accessibilityNodeProvider != null
        } catch (_: Throwable) {
            false
        }
    }

    fun rootSemanticsNode(host: View): Any? = try {
        val owner = findMethod(host.javaClass, "getSemanticsOwner", "getSemanticsOwner\$ui_release")
            ?.invoke(host) ?: return null
        findMethod(owner.javaClass, "getRootSemanticsNode", "getRootSemanticsNode\$ui_release", "getRoot")
            ?.invoke(owner)
    } catch (_: Throwable) {
        null
    }

    fun nodeId(node: Any): Long = try {
        (findMethod(node.javaClass, "getId")?.invoke(node) as? Number)?.toLong() ?: -1L
    } catch (_: Throwable) {
        -1L
    }

    fun nodeChildren(node: Any): List<Any> = try {
        @Suppress("UNCHECKED_CAST")
        (findMethod(node.javaClass, "getChildren")?.invoke(node) as? List<Any>) ?: emptyList()
    } catch (_: Throwable) {
        emptyList()
    }

    /** SemanticsNode → 可读 props：text/desc/testTag/clickable/enabled/bounds/class（Role）。 */
    fun describe(node: Any): JSONObject {
        val o = JSONObject()
        try {
            val config = findMethod(node.javaClass, "getConfig")?.invoke(node)
            val position = call2Ints(node, "getPositionInWindow")
            val size = call2Ints(node, "getSize")
            if (position != null && size != null) {
                o.put(
                    "bounds",
                    JSONArray().put(position[0]).put(position[1]).put(position[0] + size[0]).put(position[1] + size[1])
                )
            }
            o.put("semId", nodeId(node))
            if (config is Map<*, *>) {
                for ((k, v) in config) {
                    val keyName = try {
                        k?.javaClass?.getMethod("getName")?.invoke(k)?.toString()
                    } catch (_: Throwable) {
                        null
                    } ?: continue
                    when (keyName) {
                        "Text" -> o.put("text", firstString(v))
                        "ContentDescription" -> o.put("desc", firstString(v))
                        "TestTag" -> o.put("testTag", v?.toString() ?: continue)
                        "Role" -> o.put("role", v.toString())
                        "OnClick" -> o.put("clickable", true)
                        "Disabled" -> o.put("enabled", false)
                    }
                }
            }
            if (!o.has("clickable")) o.put("clickable", false)
            if (!o.has("enabled")) o.put("enabled", true)
            o.put("class", "ComposeNode")
        } catch (_: Throwable) {
        }
        return o
    }

    /**
     * Compose 节点点击/输入：虚拟节点走宿主 view 的 AccessibilityNodeProvider.performAction
     * （三参版本，无需开启系统无障碍；View.performAccessibilityAction 是两参，不适用于虚拟节点）。
     */
    fun performAction(entry: UiTree.UiEntry, action: Int, args: android.os.Bundle?): Boolean {
        val host = entry.composeHost ?: return false
        val id = entry.semNode?.let { nodeId(it) } ?: return false
        return try {
            host.accessibilityNodeProvider?.performAction(id.toInt(), action, args) ?: false
        } catch (_: Throwable) {
            false
        }
    }

    private fun firstString(v: Any?): String? = when (v) {
        null -> null
        is List<*> -> v.firstOrNull()?.let { unwrapAnnotated(it) }
        else -> unwrapAnnotated(v)
    }

    private fun unwrapAnnotated(v: Any): String? {
        // AnnotatedString → getText()；String 直接 toString
        return try {
            findMethod(v.javaClass, "getText")?.invoke(v)?.toString() ?: v.toString()
        } catch (_: Throwable) {
            v.toString()
        }
    }

    private fun call2Ints(node: Any, method: String): IntArray? = try {
        val r = findMethod(node.javaClass, method)?.invoke(node) ?: return null
        val x = (findMethod(r.javaClass, "getX")?.invoke(r) as? Number)?.toInt()
        val y = (findMethod(r.javaClass, "getY")?.invoke(r) as? Number)?.toInt()
        if (x != null && y != null) intArrayOf(x, y) else null
    } catch (_: Throwable) {
        null
    }

    private fun findMethod(cls: Class<*>, vararg names: String): java.lang.reflect.Method? {
        var c: Class<*>? = cls
        while (c != null) {
            for (name in names) {
                try {
                    return c.getMethod(name).also { it.isAccessible = true }
                } catch (_: NoSuchMethodException) {
                }
            }
            c = c.superclass
        }
        return null
    }
}
