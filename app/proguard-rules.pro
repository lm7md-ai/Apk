# Universal APK Factory & Studio — R8 keep rules.
# (minification is disabled by default; these rules make `minifyEnabled true`
# safe for the binary-engineering stack.)

# ---- BouncyCastle (isolated provider registered by class name) ----
-keep class org.bouncycastle.jcajce.provider.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn javax.naming.**

# ---- apksig (reflectively loads signers/verifiers) ----
-keep class com.android.apksig.** { *; }
-dontwarn com.android.apksig.**

# ---- ARSCLib (heavy reflection-free, but implements optional SPIs) ----
-keep class com.reandroid.** { *; }
-dontwarn com.reandroid.**

# ---- zipalign-java ----
-keep class com.iyxan23.zipalignjava.** { *; }
-keep class com.macfaq.io.** { *; }

# ---- OkHttp ----
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.jsse.**
-dontwarn org.openjsse.**

# ---- JavaScript-to-Android bridge (shell apps) ----
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# ---- Kotlinx Serialization ----
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
