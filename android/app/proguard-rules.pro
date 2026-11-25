# Proguard / R8 rules for GhostKey
# Retain BouncyCastle provider, JCA algorithms, and OpenPGP classes loaded via reflection
-keep class org.bouncycastle.jce.provider.** { *; }
-keep class org.bouncycastle.jcajce.provider.** { *; }
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
