# Native code calls SdrReceiver.AudioListener.onAudio(short[], int) by name.
-keep class com.js8call.sdr.RtlSdr { native <methods>; }
-keep class com.js8call.sdr.AirspyHf { native <methods>; }
-keep interface com.js8call.sdr.SdrReceiver$AudioListener { *; }
-keepclassmembers class * implements com.js8call.sdr.SdrReceiver$AudioListener { void onAudio(short[], int); }
