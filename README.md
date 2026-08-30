# OmniDebugLink Android client

Android client library for [OmniDebugLink](https://omnidebuglinkweb.pages.dev) — remote debugging and automated testing of real devices over a single wss connection. View + Compose UI tree inspection, tap/swipe/text/key injection, screenshots, logcat, performance and app-state snapshots, all driven remotely by AI coding tools through MCP.

## Install

```groovy
repositories {
    maven { url 'https://jitpack.io' }
}
dependencies {
    implementation 'com.github.omnidebuglink:omnidebuglink_android:v0.1.0'
}
```

## Usage

```kotlin
// Application.onCreate — one line, that's it
OmniDebugLink.start(this, "wss://api.omnidebuglink.dev/ws?token=<clientToken>")
```

- Create a device token pair in the OmniDebugLink console; **one token pair = one device**
- `OmniDebugLink.actionsEnabled = false` switches to read-only observation mode
- Custom tasks: `OmniDebugLink.tasks.register("my_task", "description") { payload -> result }`

Notes: the library needs your app to have `INTERNET`; Compose introspection works automatically when your app uses Compose (no extra dependency to add).
