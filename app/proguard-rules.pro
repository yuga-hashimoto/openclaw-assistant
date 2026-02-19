# Add project specific ProGuard rules here.
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }

# Gson
-keepattributes Signature
-keep class com.google.gson.** { *; }
-keep class com.openclaw.assistant.api.** { *; }


# Google Error Prone Annotations
-dontwarn com.google.errorprone.annotations.**

# Tink (Security Crypto)
-dontwarn com.google.crypto.tink.**

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
