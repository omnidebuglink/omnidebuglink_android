package com.omnidebuglink.android

/** 内置 task 注册总表（新内置能力在此挂）。 */
internal object BuiltinTasks {
    fun registerAll(registry: TaskRegistry) {
        TasksBasics.register(registry)
        TasksUi.register(registry)
        TasksInput.register(registry)
        TasksDevice.register(registry)
    }
}
