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

# ---- Tink / androidx.security-crypto (EncryptedSharedPreferences) ----
# Compile-only annotations not present at runtime — safe to ignore.
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
