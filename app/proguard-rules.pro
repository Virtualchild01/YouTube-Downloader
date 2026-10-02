# ProGuard rules for YTDownloader
-keep class com.example.ytdownloader.api.** { *; }
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
