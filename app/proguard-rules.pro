# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# --- Room ---
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keepclassmembers class * { @androidx.room.* <methods>; }
-dontwarn androidx.room.paging.**

# --- ZXing (QR, formats and reflection) ---
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**

# --- CameraX ---
-keep class androidx.camera.** { *; }
-dontwarn androidx.camera.**

# --- Preserve stack traces for field crash triage ---
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
