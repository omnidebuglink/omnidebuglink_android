# OmniDebugLink Android client

Android client library for [OmniDebugLink](https://omnidebuglinkweb.pages.dev) — remote debugging and automated testing of real devices over a single wss connection. View + Compose UI tree inspection, tap/swipe/text/key injection, screenshots, logcat, performance and app-state snapshots, all driven remotely by AI coding tools through MCP.

## Install

```groovy
repositories {
    maven { url 'https://jitpack.io' }
}
dependencies {
    implementation 'com.github.omnidebuglink:omnidebuglink_android:v0.2.2'  // pinned (recommended)
    // or always the latest release tag:
    // implementation 'com.github.omnidebuglink:omnidebuglink_android:latest.release'
}
```

## Usage

```kotlin
// Application.onCreate — one line, that's it
OmniDebugLink.start(this, "<clientToken>")
```

- Create a device token pair in the OmniDebugLink console; **one token pair = one device**
- `OmniDebugLink.actionsEnabled = false` switches to read-only observation mode
- Custom tasks: `OmniDebugLink.tasks.register("my_task", "description") { payload -> result }`

Notes: the library needs your app to have `INTERNET`; Compose introspection works automatically when your app uses Compose (no extra dependency to add).

## Built-in tasks (20)

Read tasks:

| Task | What it does |
|---|---|
| `ui_traverse` | View + Compose semantics tree dump in one snapshot (flat-cap 3000 nodes); each node carries path, class, text, id, bounds, visibility, clickability |
| `find_objects` | Search by `text` / `id` / `desc` / `testTag` / `cls` substring (case-insensitive) — cheaper than a full dump for locating one control |
| `view_component` | One node in depth: properties, children paths, plus reflection-scanned readable fields |
| `wait_for` | Poll every 200 ms until a node matching text/id/desc/testTag appears; timeout returns `found: false`, not an error |
| `screenshot` | Current Activity window rendered from the view tree as JPEG (`__odl_file` envelope); reduces quality/size to fit the frame budget |
| `read_logs` | The app's own logcat buffer — full history while the buffer holds it; level / contains / sinceMs / limit filters |
| `get_perf` | Java/native heap, PSS, battery, thread count, optional Choreographer-sampled fps with frame-time percentiles |
| `get_state` | Activity stack + recent history, current intent, Fragment tree, screen metrics, granted permissions, network status |
| `prefs` | Read SharedPreferences (get / list) |

Write tasks (all gated by `actionsEnabled`):

| Task | What it does |
|---|---|
| `ui_click` | Delivered physically since v0.2.1: a full touch sequence at the view's center, dispatched on its own window root — Dialog/PopupWindow targets resolve correctly and overlays (guide masks) receive the click like a real tap. Locates by path or atomically by `text`/`id`/`desc`/`testTag` (+`index`) since v0.2.2; falls back to `performClick()` for zero-size views; Compose nodes go through the accessibility action channel |
| `tap_screen` | Tap at normalized 0-1 coordinates (top-left origin) through the real touch pipeline on the decorView |
| `swipe` | Drag between two points over durationMs with ~16 ms intermediate moves, so scroll/fling inertia works |
| `long_press` | Press and hold (default 800 ms) then release — triggers long-click without a plain click |
| `input_text` | Set text on TextView/EditText (TextWatcher fires normally) or Compose nodes via accessibility |
| `set_component` | Mutate a View node: text / visibility / enabled / selected (Compose nodes are not settable this way) |
| `send_key` | back / home / recents / menu / volume / dpad / enter … — back via activity dispatch (reliable), home/recents may be rejected by the OS |
| `launch_intent` | Start an Activity by deep link (uri), explicit component, or action — useful as the entry of automated flows |
| `prefs` | Write / delete SharedPreferences with valueType coercion |

Basics: `echo` / `ping` / `get_stats`

Coordinates: normalized 0-1, **top-left origin** (same as Flutter/iOS/web; Unity is bottom-left).

## License

Released under the [MIT License](LICENSE).
