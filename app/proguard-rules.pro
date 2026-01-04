-keepattributes RuntimeVisibleAnnotations
-keepattributes AnnotationDefault

-keep class androidx.compose.runtime.** { *; }
-keep class androidx.compose.ui.platform.** { *; }

-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**

# Remove all Log calls in release builds
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
}
