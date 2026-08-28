plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.androidx.baselineprofile)
}

// docs/CHANGELOG.md § Baseline Profile.
//
// This is a `com.android.test` module: it builds an instrumentation APK that drives the
// app APK on a connected device. It contains two things:
//   - BaselineProfileGenerator — records the hot classes/methods of the critical user
//     journey (cold start → home gitgraph → open a routine → open an exercise → back →
//     open a history square → back → routine list → exercise list). Output committed as
//     app/src/<variant>/generated/baselineProfiles/baseline-prof.txt.
//   - StartupBenchmark — measures cold-start timeToInitialDisplay with the profile off
//     vs. required, to record the delta.
//
// Neither runs in the normal unit/instrumented CI job (slow, device-specific) — see the
// CHANGELOG gotchas.
android {
    namespace = "com.mygymapp.baselineprofile"
    compileSdk = 36

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }

    defaultConfig {
        // Baseline-profile generation needs an API 28+ device where the profile can
        // actually be installed (physical phone or AOSP image — not a Play emulator image).
        minSdk = 28
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    targetProjectPath = ":app"
    // The androidx.baselineprofile plugin adds the `nonMinifiedRelease` (generation) and
    // `benchmarkRelease` (measurement) build types itself, derived from the app's
    // `release` — don't declare them here or the variant names get doubled up.
}

// Run the generator on the one connected device without needing a managed AVD.
baselineProfile {
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.runner)
    implementation(libs.androidx.test.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}
