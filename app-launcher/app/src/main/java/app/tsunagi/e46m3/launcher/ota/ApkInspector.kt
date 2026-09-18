package app.tsunagi.e46m3.launcher.ota

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import java.io.File
import java.security.MessageDigest

/**
 * Reads a downloaded APK and says whether it is the one the manifest described.
 *
 * ## Why this exists when the hash already matched
 *
 * The hash proves the bytes are the ones the publisher signed *the manifest*
 * about. This proves something different and independent: that the archive is
 * signed by the key we expect, and that it declares the package and version it
 * claims. Those are the facts `PackageManagerService` will act on, and they are
 * worth reading before committing an install session rather than after.
 *
 * For our own package there is a stronger check available and it is used:
 * **the certificate must equal the one this very process is running under.**
 * Android would refuse the install anyway with
 * `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, but that refusal arrives after the
 * session is committed, on a device that is also the home screen. Comparing
 * against our own signature instead of a hardcoded constant also means the
 * check cannot drift the way a copied fingerprint can.
 *
 * ## GET_SIGNATURES, not GET_SIGNING_CERTIFICATES
 *
 * `GET_SIGNING_CERTIFICATES` is API 28. This unit is 27. `GET_SIGNATURES`
 * carries the v1/v2 signer, which is exactly what API 27's `PackageParser`
 * understands — and why `tools/release.sh` GATE-2 insists the published APK is
 * v1- and v2-signed. A v3-only APK would install here and return **null**
 * signatures, so a null is a hard failure below, never a skip.
 */
internal object ApkInspector {

    sealed class Verdict {
        data class Ok(val versionCode: Int, val versionName: String?, val signer: String) : Verdict()
        data class Bad(val reason: String) : Verdict()
    }

    fun inspect(context: Context, apk: File, expected: OtaPackage): Verdict {
        val signer = signerOf(context, apk)
            ?: return Verdict.Bad("NO_SIGNATURE")

        @Suppress("DEPRECATION")  // PackageInfoFlags / getLongVersionCode are API 28+
        val info = context.packageManager.getPackageArchiveInfo(apk.absolutePath, 0)
            ?: return Verdict.Bad("UNPARSEABLE")

        if (info.packageName != expected.packageName) {
            return Verdict.Bad("PACKAGE_${info.packageName}")
        }
        @Suppress("DEPRECATION")
        val code = info.versionCode
        if (code != expected.versionCode) {
            return Verdict.Bad("VERSION_${code}_WANTED_${expected.versionCode}")
        }
        if (!signer.equals(expected.signerNormalised, ignoreCase = true)) {
            Log.e(Ota.TAG, "apk signer $signer, manifest says ${expected.signerNormalised}")
            return Verdict.Bad("SIGNER")
        }

        // The extra rung for our own package. See the class docs.
        if (expected.packageName == context.packageName) {
            val mine = selfSigner(context)
                ?: return Verdict.Bad("SELF_SIGNATURE_UNREADABLE")
            if (!signer.equals(mine, ignoreCase = true)) {
                Log.e(Ota.TAG, "apk signer $signer is not this process's $mine")
                return Verdict.Bad("SIGNER_NOT_SELF")
            }
        }

        @Suppress("DEPRECATION")
        return Verdict.Ok(code, info.versionName, signer)
    }

    /** Uppercase hex SHA-256 of the signing certificate's DER — what apksigner prints. */
    fun signerOf(context: Context, apk: File): String? = try {
        @Suppress("DEPRECATION")
        val info = context.packageManager
            .getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNATURES)
        @Suppress("DEPRECATION")
        val signatures = info?.signatures
        if (signatures.isNullOrEmpty()) {
            // Never treated as "unknown, carry on". A v3-only APK looks exactly
            // like this, and so does a tampered archive with its v1 block
            // stripped.
            Log.e(Ota.TAG, "${apk.name} has no readable v1/v2 signature")
            null
        } else {
            digestOf(signatures[0].toByteArray())
        }
    } catch (e: Exception) {
        Log.e(Ota.TAG, "could not read the signature of ${apk.name}", e)
        null
    }

    /** The certificate this process is running under. */
    fun selfSigner(context: Context): String? = try {
        @Suppress("DEPRECATION")
        val info = context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
        @Suppress("DEPRECATION")
        val signatures = info.signatures
        if (signatures.isNullOrEmpty()) null else digestOf(signatures[0].toByteArray())
    } catch (e: Exception) {
        Log.e(Ota.TAG, "could not read this app's own signature", e)
        null
    }

    private fun digestOf(certDer: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(certDer).toHex().uppercase()
}
