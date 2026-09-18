# MyGymApp release keep-rules. Verified with `./gradlew assembleRelease` + on-device
# smoke (session + Polar + sync) + the Options → Debug self-test, which doubles as a
# post-minify math check. See docs/CONVENTIONS.md#release-minify.

# --- Generics signatures (Hilt, Retrofit-style generated code, baseline tooling) ---
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes InnerClasses,EnclosingMethod

# --- Hilt (generated components reference app classes by name) ---
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.internal.GeneratedComponent { *; }
-keep class * extends dagger.hilt.internal.GeneratedComponentManager { *; }
-keep @dagger.hilt.android.HiltAndroidApp class * { *; }
-keep @dagger.hilt.android.AndroidEntryPoint class * { *; }
-keep @dagger.hilt.android.lifecycle.HiltViewModel class * { *; }
-keepclasseswithmembernames class * {
    @dagger.Provides <methods>;
}

# --- snakeyaml-engine (YAML frontmatter; internal reflective construction) ---
-keep class org.snakeyaml.** { *; }
-dontwarn org.snakeyaml.**

# --- Polar BLE SDK (vendor SDK, internal reflection/serialization) ---
-keep class com.polar.** { *; }
-keep class polar.** { *; }
-dontwarn com.polar.**
-dontwarn polar.**

# --- RxJava 3 (Polar SDK transport) ---
-dontwarn io.reactivex.**
-dontwarn com.google.errorprone.annotations.**

# --- Native + services referenced from the manifest ---
-keepclasseswithmembernames class * {
    native <methods>;
}
