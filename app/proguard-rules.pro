# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.

-keepclassmembers class fqcn.of.javascript.interface.for.webview {
   public *;
}

-keepattributes SourceFile,LineNumberTable,JavascriptInterface,*Annotation*,Signature,InnerClasses,EnclosingMethod
-dontwarn java.beans.**
-dontwarn javax.script.**

# --- YouTube Extractor Logic (CRITICAL FIX) ---
# NewPipe uses reflection and internal dependencies that must be kept for analysis to work
-keep class org.schabi.newpipe.extractor.** { *; }
-keep interface org.schabi.newpipe.extractor.** { *; }
-keep class com.grack.nanojson.** { *; }
-keep class org.jsoup.** { *; }
-dontwarn org.schabi.newpipe.extractor.**
-dontwarn org.jsoup.**
-dontwarn com.grack.nanojson.**

# --- AdMob Specific Rules ---
-keep class com.google.android.gms.ads.** { *; }
-keep class com.google.ads.** { *; }
-keep class android.webkit.** { *; }
