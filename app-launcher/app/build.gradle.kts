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

// ── Version identity ──────────────────────────────────────────────────────
// versionCode/versionName come from a file, not from literals here, because
// `tools/release.sh` has to read and compare them without running Gradle.
//
// Unlike the keystore above there is NO degraded mode: a build whose version
// cannot be established is a build the OTA system cannot reason about, and
// shipping one would put an unidentifiable APK on a head unit that is also the
// home screen. So this fails the build.
val versionPropsFile = rootProject.file("version.properties")
if (!versionPropsFile.exists()) {
    throw GradleException("missing ${versionPropsFile.path} — see docs/07-ota-design.md")
}
val versionProps = Properties().apply { versionPropsFile.inputStream().use { load(it) } }

fun requiredVersionProp(key: String): String = versionProps.getProperty(key)
    ?: throw GradleException("version.properties has no `$key`")

/**
 * Runs a git command at configuration time, returning null on any failure.
 *
 * Never throws: a source tree exported without .git, or a machine with no git
 * on PATH, must still build. What it must NOT do is silently substitute
 * something that looks like a real answer — hence null, and the explicit
 * "nogit" / commit-less fallbacks at the call sites.
 *
 * ⚠ ProcessBuilder at configuration time is incompatible with Gradle's
 * configuration cache. It is not enabled in gradle.properties today. If it is
 * ever turned on, these two calls must move into a ValueSource.
 */
fun git(vararg args: String): String? = runCatching {
    val proc = ProcessBuilder(listOf("git", *args))
        .directory(rootProject.projectDir)
        .redirectErrorStream(true)
        .start()
    val out = proc.inputStream.bufferedReader().readText().trim()
    if (proc.waitFor() == 0 && out.isNotEmpty()) out else null
}.getOrNull()

// Validated rather than trusted: a `git` that prints a warning on stdout would
// otherwise end up embedded in the APK as a commit id.
val gitSha: String = git("rev-parse", "--short=7", "HEAD")
    ?.takeIf { it.matches(Regex("^[0-9a-f]{7}$")) }
    ?.let { if (git("status", "--porcelain").isNullOrEmpty()) it else "$it+" }
    ?: "nogit"

// The COMMIT time, not the build time. Two reasons, and both matter:
//   - Wall-clock-at-configure-time changes on every build, which destroys
//     reproducibility and every Gradle cache hit that depends on this module.
//   - This value is a lower bound on the device clock at runtime (OtaClock):
//     the unit cannot legitimately read a date before the build it is running.
//     That only holds if the number describes the source, not the build host.
// Falls back to 0, which makes the floor inert rather than wrong.
val buildEpoch: Long = git("log", "-1", "--format=%ct")?.toLongOrNull() ?: 0L


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

        versionCode = requiredVersionProp("versionCode").toInt()
        versionName = requiredVersionProp("versionName")

        // Read by the OTA subsystem. Kept out of versionName because versionName
        // is what the 5x7 LCD renders, and that font has neither lowercase nor
        // the width to spare.
        buildConfigField("String", "GIT_SHA", "\"$gitSha\"")
        buildConfigField("long", "BUILD_EPOCH", "${buildEpoch}L")

        // A build-time constant so a fork, or a staging lane, is a property
        // change rather than a code edit. Overridable from the command line:
        //   ./gradlew assembleRelease -PotaManifestUrl=https://.../ota-manifest.json
        buildConfigField(
            "String",
            "OTA_MANIFEST_URL",
            "\"" + (findProperty("otaManifestUrl") as String?
                ?: "https://github.com/mushitaro/E46M3-Launcher/releases/latest/download/ota-manifest.json") + "\"",
        )
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

    buildFeatures {
        // AGP 8 defaults this off, and nothing needed BuildConfig until the OTA
        // subsystem did.
        buildConfig = true
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

    testOptions {
        unitTests {
            // android.jar on the unit-test classpath is stubs: every method
            // throws "not mocked" unless this is set. The OTA verifier logs on
            // each of its rejection paths, and those paths are exactly the ones
            // worth testing, so without this the security-relevant tests would
            // be the only ones that could not run.
            isReturnDefaultValues = true
        }
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
    // Test-only, and it ships in nothing. android.jar's org.json is a stub, so
    // without a real implementation on the test classpath the manifest parser
    // could not be exercised off-device at all — and "is a signed but malformed
    // manifest rejected" is not a question worth answering on a car.
    testImplementation("org.json:json:20240303")
    // Deliberately absent: Compose, AppCompat, Material Components.
    // Rationale is in docs — Baseline Profiles are inert on API 27, and this
    // launcher cold-starts on every boot, every KILL_APPS and every LMK kill.
}
