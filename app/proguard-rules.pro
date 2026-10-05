# libadb accesses Conscrypt APIs reflectively.
-keep class org.conscrypt.** { *; }
-dontwarn org.conscrypt.**

# Bouncy Castle is used only to create the local ADB client certificate.
-dontwarn org.bouncycastle.**
