-dontwarn io.github.libxposed.annotation.**
-dontwarn androidx.**
-dontwarn com.google.android.material.**
-adaptresourcefilecontents META-INF/xposed/java_init.list

-keep public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}

# Module app + hooks (reflection, layout inflation, libxposed)
-keep class io.github.howard20181.hyperos.fcmlive.** { *; }

# Two package-wide keeps that used to sit below were removed on 2026-10-05.
#
# ## com.google.android.material.**
#
# The removed rule was justified as "the styles and attrs are resolved by name
# out of resources, which R8 cannot see". That reason is real, but it points at
# the wrong thing: styles and attrs are *resources*, and they stay in the merged
# resource table either way (shrinkResources is off). Keeping every Material
# *class* is not what protects them.
#
# The one place Material is genuinely reached by name is the `viewInflaterClass`
# theme attribute, which AppCompat reads and instantiates reflectively — and
# material-1.14.0.aar declares exactly that itself, in its own proguard.txt:
#
#     -if class androidx.appcompat.app.AppCompatViewInflater
#     -keep class com.google.android.material.theme.MaterialComponentsViewInflater {
#         <init>();
#     }
#
# So the reflective contract is stated per class, and conditionally, by the
# library that owns it; the blanket keep only duplicated it and made it
# unconditional. This app's own use of Material is a single direct import
# (`com.google.android.material.color.DynamicColors`, theme/ThemeSupport.kt),
# res/ contains no Material class name at all, and there is no layout XML left to
# inflate. Measured before removal: `com.google.android.material` was 1163 of the
# 4664 classes defined in the release dex, plus the RecyclerView / Fragment /
# CoordinatorLayout / ConstraintLayout classes it held open behind it.
#
# ## androidx.compose.**
#
# The removed rule was `-keep class androidx.compose.** { *; }` ("R8 without
# these keeps can crash at setContent"). That was a blank cheque: it pinned every
# Compose class to its original name and switched off shrinking and optimisation
# for what is the largest single block of code in this APK. Compose declares its
# own reflective surface in the proguard.txt carried by each AAR
# (runtime / ui / foundation — `-assumenosideeffects` on the Composer source
# information hooks, the stubs it links against, the functions that throw), and
# AGP applies those files automatically, so the blanket keep only ever added
# retention on top of them.
#
# The companion rule `-keep class androidx.activity.compose.** { *; }` went for a
# different reason: nothing in this app uses it. Every screen is a `ComposeView`
# + `setContentView` (see the five Activities), i.e.
# `androidx.compose.ui.platform.ComposeView#setContent`, never
# `androidx.activity.compose.setContent`. That module is on the runtime
# classpath, pulled in transitively, but no source file references it.
#
# `-dontwarn androidx.**` / `-dontwarn com.google.android.material.**` above still
# cover anything R8 cannot resolve. Note what that means: R8 will not report a
# missing class here, so neither removal is self-verifying — the class list the
# two rules above rely on is the evidence, and a launch on a device is the check.

-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,MethodParameters
-keepattributes SourceFile,LineNumberTable
