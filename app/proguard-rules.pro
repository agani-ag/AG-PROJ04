# Keep any future @JavascriptInterface methods (WebView bridge)
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Retrofit / Gson models (safe defaults for release builds)
-keep class com.agani.syncup.data.** { *; }
-keepattributes Signature, RuntimeVisibleAnnotations, AnnotationDefault
