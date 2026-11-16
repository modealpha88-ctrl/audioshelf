-keep class org.xmlpull.v1.** { *; }

# Vosk binds JNI by Java method names, so R8 must preserve these declarations.
-keep class org.vosk.** { *; }

# Keep Android JNA reflection/native method bindings in minified releases.
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { public *; }
-dontwarn java.awt.**
