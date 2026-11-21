# ProGuard/R8 rules for JS8-Tactical.
# Native code calls back into these by name (JNI): keep them whole.
-keep class com.js8call.core.** { *; }
-keep class com.js8call.llm.** { *; }

# Readable crash reports from testers (line numbers in stack traces).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# MapLibre loads some classes by name from native code.
-keep class org.maplibre.** { *; }
-dontwarn org.maplibre.**

# The app's own classes keep their names (unused code is still removed): enum names are saved in
# files and settings (bands, report kinds, observation kinds), so a renaming between two builds
# would make an update misread what the previous one saved. Also keeps crash reports readable.
-keepnames class com.js8call.example.** { *; }
