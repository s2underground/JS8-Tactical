# Native code (js8llm_jni.cpp) looks these up by name: never rename or remove them.
-keepclasseswithmembernames class com.js8call.llm.** {
    native <methods>;
}
-keep interface com.js8call.llm.LlamaNative$TokenSink { *; }
-keep class * implements com.js8call.llm.LlamaNative$TokenSink { *; }
