# Giữ Jsoup + Worker/Data khi minify release
-keep class org.jsoup.** { *; }
-dontwarn org.jsoup.**
-keep class vn.bongdaplus.reader.notify.** { *; }
-keep class vn.bongdaplus.reader.data.** { *; }
-keep class androidx.work.** { *; }
-dontwarn androidx.work.**
-dontwarn kotlinx.coroutines.**
-dontwarn okhttp3.**
-dontwarn okio.**
# NewPipeExtractor + Rhino (giải mã chữ ký YouTube): giữ nguyên, bỏ qua lớp desktop thiếu trên Android
-keep class org.schabi.newpipe.extractor.** { *; }
-keep class org.mozilla.javascript.** { *; }
-dontwarn org.schabi.newpipe.extractor.**
-dontwarn org.mozilla.**
-dontwarn java.beans.**
