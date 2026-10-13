# kotlinx.serialization: keep generated serializers of the state models
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.kalpande.nkpt.**$$serializer { *; }
-keepclassmembers class com.kalpande.nkpt.** { *** Companion; }
-keepclasseswithmembers class com.kalpande.nkpt.** { kotlinx.serialization.KSerializer serializer(...); }

-keep class com.kalpande.nkpt.work.** extends androidx.work.ListenableWorker { <init>(...); }

# OkHttp ships its own rules; silence optional platform warnings
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
# Chart bridge called from JavaScript
-keepclassmembers class * { @android.webkit.JavascriptInterface <methods>; }
