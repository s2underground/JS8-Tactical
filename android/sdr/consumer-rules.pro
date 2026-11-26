# Native code calls RtlSdr.AudioListener.onAudio(short[], int) by name.
-keep class com.js8call.sdr.RtlSdr { native <methods>; }
-keep interface com.js8call.sdr.RtlSdr$AudioListener { *; }
-keepclassmembers class * implements com.js8call.sdr.RtlSdr$AudioListener { void onAudio(short[], int); }
