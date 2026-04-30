# CamixUltra ProGuard rules

# Keep CameraX
-keep class androidx.camera.** { *; }
-keep class androidx.camera.camera2.interop.** { *; }

# Keep Hilt
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keepclasseswithmembernames class * { @dagger.hilt.* <methods>; }

# Keep data classes (Parcelize, FilterParameters)
-keep class com.chastechgroup.camix.filter.** { *; }
-keep class com.chastechgroup.camix.camera.** { *; }
-keep class com.chastechgroup.camix.audio.** { *; }

# Keep Timber in debug
-dontwarn com.jakewharton.timber.**

# Android standard
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Application

# Kotlin
-keep class kotlin.** { *; }
-keep class kotlinx.coroutines.** { *; }
