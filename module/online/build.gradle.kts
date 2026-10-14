plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.rawsmusic.module.online"
    compileSdk = 37

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    testOptions {
        // 让纯 JVM 单测里的 android.* 桩方法**返回默认值**而不是抛
        // "Method X in android.util.Log not mocked"。
        //
        // 为什么这个模块需要：`core:common` 的 AppLogger 直接调 `android.util.Log`，
        // 所以**任何带日志的分支都跑不了单测** —— 而本模块的错误/边界分支（非法 JSON、
        // 缺脚本正文等）恰恰是要重点测的。全工程目前都没配这一项，别的模块的测试能过
        // 只是因为它们没走到记日志的代码。
        //
        // 取舍：开了之后，误用未 mock 的 Android API 会**静默拿到默认值**而不是报错，
        // 所以这里仍然只测不依赖 Android 运行时的纯逻辑。
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":module:data"))

    // 本模块要拿 MusicDatabase 取 DAO，而 RoomDatabase 是它的父类型；
    // :module:data 把 Room 声明成 implementation，不会传递过来，所以这里必须显式声明。
    implementation(libs.androidx.room.runtime)

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    // 平台接口全部是 JSON，解析统一走 org.json（Android 内置）；持久化 DTO 用 Gson。
    implementation(libs.okhttp)
    implementation(libs.gson)

    // QQ / 酷狗扫码登录的二维码生成。
    implementation(libs.zxing.core)

    testImplementation("junit:junit:4.13.2")
}
