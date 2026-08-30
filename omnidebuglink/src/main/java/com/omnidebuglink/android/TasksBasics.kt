package com.omnidebuglink.android

import android.os.Build
import android.os.SystemClock
import org.json.JSONObject

internal object TasksBasics {

    fun register(registry: TaskRegistry) {
        registry.register(
            "ping",
            "Round-trip liveness probe; returns pong=true with the local uptime.",
            "{\"type\":\"object\",\"properties\":{}}"
        ) { JSONObject().put("pong", true).put("uptimeMs", SystemClock.elapsedRealtime()) }

        registry.register(
            "echo",
            "Returns the payload unchanged. Useful for smoke-testing the relay loop.",
            "{\"type\":\"object\",\"properties\":{}}"
        ) { it }

        registry.register(
            "get_stats",
            "Basic runtime stats: lib/app version, device info, uptime, task count, connection state.",
            "{\"type\":\"object\",\"properties\":{}}"
        ) {
            JSONObject()
                .put("libVersion", OmniDebugLink.LibVersion)
                .put("appVersion", OmniDebugLink.appVersion())
                .put("platform", "android")
                .put("osVersion", "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
                .put("model", "${Build.MANUFACTURER} ${Build.MODEL}")
                .put("uptimeMs", SystemClock.elapsedRealtime() - OmniDebugLink.startedAtMs)
                .put("tasksCount", OmniDebugLink.tasks.size())
                .put("actionsEnabled", OmniDebugLink.actionsEnabled)
                .put("connected", OmniDebugLink.connection?.connected ?: false)
        }
    }
}
