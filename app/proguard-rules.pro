# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# Guardian ProGuard Rules

# ==================== kotlinx.serialization ====================
# Keep serialization-related annotations and metadata
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

# Keep serializers for all @Serializable classes
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# App-specific serializable classes
-keep,includedescriptorclasses class com.andebugulin.nfcguard.**$$serializer { *; }
-keepclassmembers class com.andebugulin.nfcguard.** {
    *** Companion;
}
-keepclasseswithmembers class com.andebugulin.nfcguard.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep all @Serializable data classes (full keep, not just names, for R8 safety)
-keep class com.andebugulin.nfcguard.AppState { *; }
-keep class com.andebugulin.nfcguard.Mode { *; }
-keep class com.andebugulin.nfcguard.Schedule { *; }
-keep class com.andebugulin.nfcguard.NfcTag { *; }
-keep class com.andebugulin.nfcguard.TimeSlot { *; }
-keep class com.andebugulin.nfcguard.DayTime { *; }
-keep class com.andebugulin.nfcguard.BlockMode { *; }
-keep class com.andebugulin.nfcguard.ConfigManager$ExportData { *; }

# ==================== General ====================
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ==================== Strip debug logs in release ====================
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}