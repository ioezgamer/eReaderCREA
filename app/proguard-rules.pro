-keep class org.readium.** { *; }
-dontwarn org.readium.**

# CREA Reader
-keepattributes *Annotation*, InnerClasses, Signature, Exceptions, SourceFile, LineNumberTable, EnclosingMethod
-keep class org.crea.reader.data.model.** { *; }
-keep class org.crea.reader.sync.** { *; }
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class * extends androidx.work.ListenableWorker {
    <init>(android.content.Context, androidx.work.WorkerParameters);
}
-dontwarn javax.annotation.**
-dontwarn org.slf4j.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
