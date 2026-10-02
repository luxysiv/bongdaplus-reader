# Giữ Jsoup + Worker/Data khi minify release
-keep class org.jsoup.** { *; }
-dontwarn org.jsoup.**
-keep class vn.bongdaplus.reader.notify.** { *; }
-keep class vn.bongdaplus.reader.data.** { *; }
-keep class androidx.work.** { *; }
-dontwarn androidx.work.**
-dontwarn kotlinx.coroutines.**
# WebFetcher dùng addJavascriptInterface (bridge @JavascriptInterface)
-keepclassmembers class vn.bongdaplus.reader.data.WebFetcher* {
    @android.webkit.JavascriptInterface <methods>;
}
