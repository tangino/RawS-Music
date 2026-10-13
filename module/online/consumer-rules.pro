# Online music module ProGuard rules

# Gson 直接按字段名映射 JSON，模型与 DTO 的字段名不能被混淆。
-keep class com.rawsmusic.module.online.model.** { *; }
-keep class com.rawsmusic.module.online.sync.** { *; }
-keep class com.rawsmusic.module.online.source.** { *; }

# ZXing 二维码生成（扫码登录）。
-dontwarn com.google.zxing.**
