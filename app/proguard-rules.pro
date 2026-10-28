# R8 keeps what the Android framework and Compose consumer rules do not already.
# LiteRT's native libraries call back into these classes by name, and the probe loads them by reflection-like JNI lookups.
-keep class com.google.ai.edge.litert.** { *; }
-keepclasseswithmembernames class * { native <methods>; }
