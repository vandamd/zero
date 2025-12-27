-keepattributes RuntimeVisibleAnnotations
-keepattributes AnnotationDefault

-keep class androidx.compose.runtime.** { *; }
-keep class androidx.compose.ui.platform.** { *; }

-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
