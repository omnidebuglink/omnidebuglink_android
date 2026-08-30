# OmniDebugLink Android — consumer proguard rules
# 客户端通过反射读取 Compose 语义树，宿主开启混淆时需保留相关类
-keep class androidx.compose.ui.semantics.** { *; }
-keep class androidx.compose.ui.platform.AndroidComposeView { *; }
