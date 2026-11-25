# Proguard / R8 rules for GhostKey
# Retain only the BouncyCastle JCA/OpenPGP classes actually required (PQC/X509 are stripped)
-keep class org.bouncycastle.jce.provider.** { *; }
-keep class org.bouncycastle.jce.spec.** { *; }
-keep class org.bouncycastle.jcajce.spec.** { *; }
-keep class org.bouncycastle.jcajce.provider.config.** { *; }
-keep class org.bouncycastle.jcajce.provider.util.** { *; }
-keep class org.bouncycastle.jcajce.provider.asymmetric.EdEC** { *; }
-keep class org.bouncycastle.jcajce.provider.asymmetric.edec.** { *; }
-keep class org.bouncycastle.jcajce.provider.asymmetric.EC** { *; }
-keep class org.bouncycastle.jcajce.provider.asymmetric.ec.** { *; }
-keep class org.bouncycastle.jcajce.provider.asymmetric.RSA** { *; }
-keep class org.bouncycastle.jcajce.provider.asymmetric.rsa.** { *; }
-keep class org.bouncycastle.jcajce.provider.asymmetric.DSA** { *; }
-keep class org.bouncycastle.jcajce.provider.asymmetric.dsa.** { *; }
-keep class org.bouncycastle.jcajce.provider.asymmetric.ElGamal** { *; }
-keep class org.bouncycastle.jcajce.provider.asymmetric.elgamal.** { *; }
-keep class org.bouncycastle.jcajce.provider.symmetric.AES** { *; }
-keep class org.bouncycastle.jcajce.provider.digest.SHA** { *; }
-keep class org.bouncycastle.jcajce.provider.digest.MD5** { *; }
-keep class org.bouncycastle.jcajce.provider.digest.RIPEMD160** { *; }
-keep class org.bouncycastle.jcajce.provider.drbg.** { *; }
-keep class org.bouncycastle.openpgp.** { *; }
-keep class org.bouncycastle.bcpg.** { *; }
-keep class org.bouncycastle.crypto.** { *; }
-dontwarn org.bouncycastle.**

# Standard Kotlin & Coroutines reflection metadata
-keepclassmembers class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**

# JNI entrypoints for Embedded Arti Tor (Rust)
-keepclasseswithmembers class com.ghostkey.app.net.ArtiTorManager {
    native <methods>;
}
-keep class com.ghostkey.app.net.ArtiTorManager { *; }
-keep class com.ghostkey.app.net.ArtiTorState { *; }
-keep class com.ghostkey.app.net.ArtiTorState$* { *; }

# ZXing Core (QR code generation & scanning)
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**
