# Add project specific ProGuard rules here.
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable

# Vosk speech recognition
-keep class org.vosk.** { *; }
-dontwarn org.vosk.**

# JNA (used by Vosk) — JNA uses reflection to access the 'peer' field
# in com.sun.jna.Pointer and native method registration in com.sun.jna.Native.
# Without these rules, R8 strips/renames fields causing UnsatisfiedLinkError.
-keep class com.sun.jna.** { *; }
-dontwarn com.sun.jna.**

# Markdown renderer (JetBrains markdown parser)
-keep class org.intellij.markdown.** { *; }
-dontwarn org.intellij.markdown.**
