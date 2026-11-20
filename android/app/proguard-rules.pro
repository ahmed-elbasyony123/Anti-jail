# Proguard / R8 rules for GhostKey
# Retain BouncyCastle provider classes that are loaded via JCA reflection
-keep class org.bouncycastle.jce.provider.BouncyCastleProvider { *; }
-keep class org.bouncycastle.jcajce.provider.** { *; }
-dontwarn org.bouncycastle.**

# Standard Kotlin & Coroutines reflection metadata
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-dontwarn java.lang.invoke.**

# Preserve JNI native bindings for embedded Arti Tor Engine
-keepclassmembers class * {
    native <methods>;
}
-keep class com.ghostkey.app.net.ArtiTorManager { *; }

# ZXing Core
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**
