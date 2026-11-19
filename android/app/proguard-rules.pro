# Proguard / R8 rules for GhostKey
# Retain BouncyCastle provider classes that are loaded via JCA reflection
-keep class org.bouncycastle.jce.provider.BouncyCastleProvider { *; }
-keep class org.bouncycastle.jcajce.provider.** { *; }
-dontwarn org.bouncycastle.**

# Standard Kotlin & Coroutines reflection metadata
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-dontwarn java.lang.invoke.**
