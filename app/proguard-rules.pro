# MagnetRush 混淆规则
# jlibtorrent 是 JNI 封装：所有 libtorrent 类 + native 方法名都不能被混淆，
# 否则 System.loadLibrary 之后 JNI 找不到对应符号会直接崩。

-keep class org.libtorrent4j.** { *; }
-keep class com.frostwire.jlibtorrent.** { *; }
-keepclassmembers class org.libtorrent4j.** {
    native <methods>;
}
-keepclassmembers class com.frostwire.jlibtorrent.** {
    native <methods>;
}

# 保留注解，避免 Swig 生成的辅助类出错
-keepattributes *Annotation*, InnerClasses, Signature, Exceptions

# 常见告警压制
-dontwarn org.libtorrent4j.**
-dontwarn com.frostwire.jlibtorrent.**
