# hCaptcha SDK: работает с WebView и JSON, держим его классы целыми.
-keep class com.hcaptcha.sdk.** { *; }
-dontwarn com.hcaptcha.sdk.**

# Читаемые стектрейсы в отчётах: номера строк оставляем.
-keepattributes SourceFile,LineNumberTable

# Ядро Xray (gomobile): классы вызываются из нативного кода, R8 не должен их переименовывать или удалять.
-keep class libv2ray.** { *; }
-keep class go.** { *; }
-dontwarn libv2ray.**
