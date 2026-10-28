# R8 keeps what the Android framework and Compose consumer rules do not already.
# TensorFlow Lite's native library calls back into these classes by name.
-keep class org.tensorflow.lite.** { *; }
-dontwarn org.tensorflow.lite.gpu.**
-keepclasseswithmembernames class * { native <methods>; }
