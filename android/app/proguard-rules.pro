# Keep BouncyCastle for crypto
-keep class org.bouncycastle.** { *; }
-keep class org.spongycastle.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn org.spongycastle.**

# Keep keyboard classes
-keep class com.typeveil.keyboard.** { *; }
