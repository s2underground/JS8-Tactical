# Native code (js8llm_jni.cpp) looks these up by name: never rename or remove them.
-keepclasseswithmembernames class com.js8call.llm.** {
    native <methods>;
}
-keep interface com.js8call.llm.LlamaBridge$TokenListener { *; }
-keep class * implements com.js8call.llm.LlamaBridge$TokenListener { *; }
-keep class com.js8call.llm.LlamaBridge$Stats { *; }
