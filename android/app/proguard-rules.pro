# Proguard / R8 rules for GhostKey
# Retain only the BouncyCastle JCA provider entries resolved via string reflection;
# direct calls into bcpg/openpgp are traced by R8 so unused PQC/X509/legacy ciphers can be stripped.
-keep class org.bouncycastle.jce.provider.BouncyCastleProvider { *; }
-keep class org.bouncycastle.jcajce.provider.asymmetric.edec.** { *; }
-keep class org.bouncycastle.jcajce.provider.symmetric.AES** { *; }
-keep class org.bouncycastle.jcajce.provider.digest.SHA** { *; }
-keep class org.bouncycastle.jcajce.provider.drbg.** { *; }
-dontwarn org.bouncycastle.**

# Standard Kotlin & Coroutines reflection metadata
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-dontwarn java.lang.invoke.**

# Preserve JNI native bindings for embedded Arti Tor Engine
-keepclassmembers class * {
    native <methods>;
}
-keep class com.ghostkey.app.net.ArtiTorManager { *; }

# ZXing Core (QR code generation & scanning only)
-keep class com.google.zxing.qrcode.** { *; }
-dontwarn com.google.zxing.**
