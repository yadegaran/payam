# JNI entry points are looked up by name from Rust.
-keep class ir.payam.messenger.core.Olm { native <methods>; }
# zxing-android-embedded is pulled in without transitive deps.
-dontwarn com.journeyapps.**
