# JNI: native code looks these up by name.
-keep class io.kestrel.engine.llm.LlamaNative { *; }
-keep interface io.kestrel.engine.llm.ByteSink { *; }
-keep class * implements io.kestrel.engine.llm.ByteSink { *; }
# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class io.kestrel.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class io.kestrel.**$$serializer { *; }
-keepclassmembers class io.kestrel.** { *** Companion; }
# JDBC implementation in the engine is desktop-only.
-dontwarn java.sql.**
-dontwarn org.sqlite.**
