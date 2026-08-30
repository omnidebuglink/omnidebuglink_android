plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    `maven-publish`
}

android {
    namespace = "com.omnidebuglink.android"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Compose 语义树内省只在宿主 app 使用 Compose 时才存在（运行时反射探测，缺库不影响）
    compileOnly("androidx.compose.ui:ui:1.7.8")
    // FragmentActivity 栈读取（get_state），同为运行时可选
    compileOnly("androidx.fragment:fragment:1.8.5")
}

// JitPack：./gradlew publishToMavenLocal 产出可依赖坐标（version 被 JitPack 的 tag 覆盖）
afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("maven") {
                from(components["release"])
                groupId = "com.github.omnidebuglink"
                artifactId = "omnidebuglink_android"
                version = OmniDebugAndroid.version
            }
        }
    }
}

object OmniDebugAndroid {
    const val version = "0.1.0" // 发版时与 OmniDebugLink.LibVersion 同步 bump
}
