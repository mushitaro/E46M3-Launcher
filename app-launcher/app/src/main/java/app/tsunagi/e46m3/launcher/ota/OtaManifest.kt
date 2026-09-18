package app.tsunagi.e46m3.launcher.ota

/**
 * The update manifest, as published by `tools/release.sh`.
 *
 * This file holds the shape only. Parsing lives in [ManifestVerifier], and it is
 * private to that file on purpose: a manifest must never be parsed before its
 * signature has been checked, and the cheapest way to guarantee that is to make
 * the parser unreachable from anywhere the check has not already happened.
 */
internal data class OtaManifest(
    val schema: Int,

    /**
     * A counter, not a timestamp.
     *
     * Freshness cannot depend on the RTC (see [Ota]). The device remembers the
     * highest serial it has seen and refuses anything lower, which also makes
     * replaying an old signed manifest useless to an attacker who controls the
     * transport.
     *
     * Withdrawing a bad release therefore means publishing a HIGHER serial that
     * omits it. Lowering a serial would be ignored by every device that already
     * saw the higher one, i.e. exactly the devices that need the withdrawal.
     */
    val serial: Int,

    val packages: List<OtaPackage>,
) {
    /** The entry for one package, or null. Unknown kinds never match. */
    fun apkFor(packageName: String): OtaPackage? = packages.firstOrNull {
        it.kind == Ota.KIND_APK && it.packageName == packageName
    }
}

internal data class OtaPackage(
    /**
     * `"apk"` today. Present so that a future entry — a web tool, an asset
     * bundle — can be added without a schema bump: an unknown kind is skipped
     * silently rather than failing the whole manifest, so an old build keeps
     * updating itself after a new kind appears.
     */
    val kind: String,

    val packageName: String,
    val versionCode: Int,
    val versionName: String,
    val url: String,
    val size: Long,

    /** SHA-256 of the APK bytes, lowercase hex. Checked while downloading. */
    val sha256: String,

    /**
     * SHA-256 of the signing certificate's DER, uppercase hex, no colons.
     *
     * The same value `apksigner verify --print-certs` prints, the same one
     * assetlinks.json carries, and the same one
     * `PackageManager.getPackageArchiveInfo(GET_SIGNATURES)` yields on device —
     * so all three are directly comparable.
     *
     * Per entry rather than a constant in the app, because it is covered by the
     * manifest signature and is therefore already authoritative. A second app
     * package signed with a different key needs no code change here.
     */
    val signerSha256: String,

    val minSdk: Int,
    val maxSdk: Int?,

    /**
     * This entry is the home screen.
     *
     * Marked in data rather than inferred by comparing package names, so the
     * "install it last, guard it hardest" rule is something the manifest states
     * rather than something the code has to deduce about itself.
     */
    val isHome: Boolean,

    val mandatory: Boolean,
    val releaseNotes: List<String>,
) {
    /** Uppercase, colon-free, for comparing against a locally computed digest. */
    val signerNormalised: String get() = signerSha256.replace(":", "").uppercase()

    fun runsOn(sdkInt: Int): Boolean =
        sdkInt >= minSdk && (maxSdk == null || sdkInt <= maxSdk)
}
