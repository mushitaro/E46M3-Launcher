import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// ── Signing ───────────────────────────────────────────────────────────────
// The release certificate is deliberately applied to EVERY build type.
// TWA Digital Asset Links binds to the signing certificate's SHA-256, so a
// debug build signed with the usual auto-generated debug key would silently
// fail verification and degrade to a Custom Tab — with no error anywhere.
// One key, one fingerprint, one assetlinks.json that never changes.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}
val hasKeystore = keystoreProps.getProperty("storeFile") != null

android {
    namespace = "app.tsunagi.e46m3.launcher"
    compileSdk = 35

    // Pinned to what is actually installed. The SDK lives under Program Files and
    // is therefore read-only without elevation, so Gradle cannot auto-install the
    // version it would otherwise pick — it would fail on an unaccepted licence for
    // a package it cannot write anyway.
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "app.tsunagi.e46m3.launcher"
        minSdk = 21

        // Pinned to the device's own API level on purpose. Matching targetSdk to
        // the running platform means the framework applies NO compatibility shims,
        // so the app behaves exactly as tested. Raising this later would require
        // adding a <queries> block for the app list (package visibility, API 30+).
        targetSdk = 27

        versionCode = 1
        versionName = "0.1.0"
    }

    // No ndk/abiFilters: there is zero native code. NanoHTTPD (added in Phase 4)
    // is pure Java, so armeabi-v7a never becomes a build constraint.

    signingConfigs {
        if (hasKeystore) {
            create("app") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            if (hasKeystore) signingConfig = signingConfigs.getByName("app")
            isMinifyEnabled = false
        }
        release {
            if (hasKeystore) signingConfig = signingConfigs.getByName("app")
            // Phase 1 ships unminified on purpose: finding an R8-induced crash in a
            // HOME app after the fact is the worst possible place to find one.
            // Turned on in Phase 2, with a re-test on the device.
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    androidResources {
        // Assets that AAPT compresses cannot be opened with AssetManager.openFd(),
        // which breaks Content-Length and Range for the loopback server in Phase 4.
        // Declared now so the trap is never rediscovered.
        noCompress += listOf("js", "json", "html", "css", "woff2", "svg", "txt", "webmanifest", "map")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        // Not distributed through Play, so the Play target-API floor does not apply.
        disable += setOf("ExpiredTargetSdkVersion", "OldTargetApi")
        abortOnError = true
    }
}

dependencies {
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.browser)
    implementation(libs.usb.serial)

    // The DS2 codec is pure Kotlin and can be proven correct against known-good
    // telegrams without a car attached. That is the only part of the vehicle
    // link that can be verified off-vehicle, so it is verified.
    testImplementation("junit:junit:4.13.2")
    // Deliberately absent: Compose, AppCompat, Material Components.
    // Rationale is in docs — Baseline Profiles are inert on API 27, and this
    // launcher cold-starts on every boot, every KILL_APPS and every LMK kill.
}
