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
