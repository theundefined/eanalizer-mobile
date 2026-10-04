# Custom ProGuard rules (minify is disabled in release; kept for safety).

# Tink (security-crypto) references error-prone annotations not on the classpath
-dontwarn com.google.errorprone.annotations.**

# Keep names readable and avoid serialization issues
-dontobfuscate

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
-dontnote kotlinx.serialization.**
-keepclassmembers @kotlinx.serialization.Serializable class com.theundefined.eanalizer.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.theundefined.eanalizer.**$$serializer { *; }

# OkHttp
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
