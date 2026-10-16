# Online music module ProGuard rules

# Gson 直接按字段名映射 JSON，模型与 DTO 的字段名不能被混淆。
-keep class com.rawsmusic.module.online.model.** { *; }
-keep class com.rawsmusic.module.online.sync.** { *; }
-keep class com.rawsmusic.module.online.source.** { *; }

# ZXing 二维码生成（扫码登录）。
-dontwarn com.google.zxing.**

# QuickJS Native JNI Wrapper — 防止 C++ JNI 反射调用类被混淆导致 ClassNotFoundException 闪退
-keep class com.whl.quickjs.wrapper.** { *; }
-keep interface com.whl.quickjs.wrapper.** { *; }
-keepclasseswithmembernames class com.whl.quickjs.wrapper.** {
    native <methods>;
}
-keep class com.rawsmusic.module.online.engine.** { *; }

