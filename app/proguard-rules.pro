# Keep any future @JavascriptInterface methods (WebView bridge)
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# ---- App data models (Gson (de)serialization) ----
-keep class com.agani.syncup.data.** { *; }
-keepattributes Signature, RuntimeVisibleAnnotations, AnnotationDefault, EnclosingMethod, InnerClasses

# ---- Gson ----
-keep class com.google.gson.** { *; }
-keep class * extends com.google.gson.TypeAdapter
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-keepclassmembers enum * { *; }

# ---- Retrofit / OkHttp (ship their own rules; these are safe extras) ----
-keepattributes Exceptions
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn retrofit2.**
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response

# ---- Media3 radio player (service is manifest-kept; keep our package + silence lib warnings) ----
-keep class com.agani.syncup.radio.** { *; }
-dontwarn androidx.media3.**

# ---- Tink / androidx.security-crypto (EncryptedSharedPreferences) ----
# Compile-only annotations not present at runtime — safe to ignore.
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**

# ---- smbj (SMB client) + BouncyCastle (SMB2/3 crypto) + SLF4J ----
# BouncyCastle looks up algorithm providers via reflection; keep it whole to avoid
# NoSuchAlgorithmException/ClassNotFoundException at runtime under R8.
-keep class org.bouncycastle.** { *; }
-keepclassmembers class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn org.slf4j.**
-keep class com.hierynomus.** { *; }
-dontwarn com.hierynomus.**
# smbj's event bus (mbassador) optionally supports EL-filtered listeners (javax.el.*, a desktop/
# Java EE API not present on Android and never exercised by smbj's actual usage) — dead code, safe to ignore.
-dontwarn javax.el.**
