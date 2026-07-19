# 混淆规则 - cleaner
# 保留 Kotlin 协程相关类
-keepattributes *Annotation*
-keepattributes Signature
-keep class kotlinx.coroutines.** { *; }
-keep class net.jpountz.xxhash.** { *; }
-keep class net.jpountz.lz4.** { *; }