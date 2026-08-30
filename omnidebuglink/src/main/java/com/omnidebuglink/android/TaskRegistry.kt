package com.omnidebuglink.android

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** task handler 签名：入参 payload（可能为空对象），返回值会被 JSON 化回传。 */
typealias TaskHandler = suspend (payload: JSONObject) -> Any?

class TaskSpec(
    val type: String,
    val description: String,
    val payloadSchema: String?,
    val handler: TaskHandler,
)

/**
 * task 注册表。注册表变化会自动触发重发 hello（能力清单动态更新），服务端零改动。
 * description 只写主路径 3-5 句；容错信息放返回值，不放提示词。
 */
class TaskRegistry(private val onChanged: () -> Unit) {

    private val map = ConcurrentHashMap<String, TaskSpec>()

    fun register(
        type: String,
        description: String,
        payloadSchema: String? = null,
        handler: TaskHandler,
    ) {
        map[type] = TaskSpec(type, description, payloadSchema, handler)
        onChanged()
    }

    fun tryGet(type: String): TaskSpec? = map[type]

    fun snapshot(): List<TaskSpec> = map.values.sortedBy { it.type }

    fun size(): Int = map.size

    /** hello 里的 tasks 数组。 */
    fun tasksJson(): JSONArray {
        val arr = JSONArray()
        for (spec in snapshot()) {
            val o = JSONObject().put("type", spec.type)
            if (spec.description.isNotEmpty()) o.put("description", spec.description)
            if (!spec.payloadSchema.isNullOrEmpty()) {
                try {
                    o.put("payloadSchema", JSONObject(spec.payloadSchema))
                } catch (_: Exception) {
                    o.put("payloadSchema", spec.payloadSchema)
                }
            }
            arr.put(o)
        }
        return arr
    }
}
