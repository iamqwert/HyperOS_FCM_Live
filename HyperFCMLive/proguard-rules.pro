-dontwarn io.github.libxposed.annotation.**
-dontwarn androidx.**
-dontwarn com.google.android.material.**
-adaptresourcefilecontents META-INF/xposed/java_init.list

-keep public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}

# Module app + hooks (reflection, layout inflation, libxposed)
-keep class io.github.howard20181.hyperos.fcmlive.** { *; }

# Material Views: the app theme's parent (res/values/themes.xml) and the
# DynamicColors wallpaper accent in theme/ThemeSupport.kt. The styles and attrs
# are resolved by name out of resources, which R8 cannot see.
-keep class com.google.android.material.** { *; }

# Jetpack Compose (the five screens). R8 without these keeps can crash at
# setContent.
-keep class androidx.compose.** { *; }
-keep class androidx.activity.compose.** { *; }
-dontwarn androidx.compose.**

-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,MethodParameters
-keepattributes SourceFile,LineNumberTable
