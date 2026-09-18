package app.tsunagi.e46m3.launcher.ota

import android.content.Context
import android.util.Base64
import android.util.Log
import app.tsunagi.e46m3.launcher.R
import org.json.JSONObject
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/**
 * Checks the manifest's signature, and only then parses it.
 *
 * ## The one rule
 *
 * **Verify the bytes, then parse the bytes.** Never parse first, never
 * re-serialise, never canonicalise. The signature `tools/release.sh` produces
 * covers the exact bytes of `ota-manifest.json`, so anything that reformats the
 * document before the check — a JSON round trip, a pretty-printer, an encoding
 * conversion — silently changes what is being verified.
 *
 * That rule is enforced structurally: [parse] is private to this file, so there
 * is no route to a parsed manifest that does not pass through [verify].
 *
 * ## Why our own signature at all, when this arrives over HTTPS
 *
 * Because on this unit HTTPS may simply not work. The RTC frequently reads
 * 2006 (see [Ota]), and a certificate that is not yet valid fails the handshake
 * before any payload exists to check. Making integrity independent of TLS is
 * what lets a wrong clock be an availability problem instead of a security one
 * — and it is what would make a plain-HTTP mirror safe, if the deferred-check
 * path ever proves insufficient on the car.
 *
 * ## RSA-2048, not Ed25519
 *
 * `Signature.getInstance("Ed25519")` throws `NoSuchAlgorithmException` below
 * API 33; this device is 27. `SHA256withRSA` has been present since API 1 and
 * verification is a single small-exponent modexp — sub-millisecond even on an
 * in-order Cortex-A7. ECDSA would also work and would be smaller, at the cost
 * of a DER-encoding failure mode, which buys nothing for 184 bytes.
 */
internal object ManifestVerifier {

    sealed class Result {
        data class Ok(val manifest: OtaManifest) : Result()

        /** Something is wrong with the payload. The string is for the log, not the driver. */
        data class Rejected(val why: String) : Result()
    }

    /**
     * @param minSerial the highest serial this device has already accepted. A
     *        manifest BELOW it is refused, which is what makes replaying an
     *        older signed manifest useless to someone who controls the
     *        transport. Equal is accepted — see the check itself.
     */
    fun verify(
        context: Context,
        manifestBytes: ByteArray,
        signatureBase64: String,
        minSerial: Int,
    ): Result {
        val publicKeyDer = try {
            context.resources.openRawResource(R.raw.ota_public_key).use { it.readBytes() }
        } catch (e: Exception) {
            // The key is checked into the APK and GATE-3 in tools/release.sh
            // refuses to publish a build without it, so this is close to
            // impossible — which is exactly why it must be loud rather than
            // silently disabling updates.
            Log.e(Ota.TAG, "the OTA public key could not be loaded", e)
            return Result.Rejected("KEY")
        }

        val signature = try {
            Base64.decode(signatureBase64.trim(), Base64.DEFAULT)
        } catch (e: IllegalArgumentException) {
            return Result.Rejected("SIG_ENCODING")
        }

        return verify(publicKeyDer, manifestBytes, signature, minSerial)
    }

    /**
     * The whole check, with no Android in it.
     *
     * Split out so it can be proven off-vehicle. This is the security boundary
     * of the OTA system: if it is wrong, a car installs whatever the transport
     * hands it. `ManifestVerifierTest` generates a real RSA-2048 pair and feeds
     * this valid, tampered, wrongly-keyed and stale inputs — which is not
     * something anybody should be finding out by driving somewhere.
     *
     * Takes raw signature bytes rather than base64 because base64 is a transport
     * detail of how the signature is published, not part of verifying it.
     */
    fun verify(
        publicKeyDer: ByteArray,
        manifestBytes: ByteArray,
        signature: ByteArray,
        minSerial: Int,
    ): Result {
        val publicKey = try {
            KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(publicKeyDer))
        } catch (e: Exception) {
            Log.e(Ota.TAG, "the OTA public key could not be parsed", e)
            return Result.Rejected("KEY")
        }

        val good = try {
            Signature.getInstance("SHA256withRSA").run {
                initVerify(publicKey)
                update(manifestBytes)
                verify(signature)
            }
        } catch (e: Exception) {
            Log.w(Ota.TAG, "signature check threw", e)
            return Result.Rejected("SIG_ERROR")
        }
        if (!good) return Result.Rejected("SIG_INVALID")

        // Only now.
        val manifest = parse(manifestBytes) ?: return Result.Rejected("MALFORMED")

        if (manifest.schema != Ota.SCHEMA) {
            // A newer schema is not a failure of this build, it is a build that
            // is too old to read it. Said separately so the log distinguishes
            // "someone is attacking us" from "go and update over ADB".
            return Result.Rejected("SCHEMA_${manifest.schema}")
        }
        // Lower is refused; EQUAL is not. Every later check re-fetches the same
        // manifest, and rejecting it for being the one we already accepted would
        // make a pending update vanish on the second look. What replay
        // protection actually needs is that a serial can never go backwards.
        if (manifest.serial < minSerial) {
            return Result.Rejected("STALE_SERIAL_${manifest.serial}_SEEN_$minSerial")
        }
        return Result.Ok(manifest)
    }
}

/**
 * Private to this file. See the class docs: there must be no way to reach a
 * parsed manifest without having verified the bytes it came from.
 */
private fun parse(bytes: ByteArray): OtaManifest? = try {
    val root = JSONObject(String(bytes, Charsets.UTF_8))
    val array = root.getJSONArray("packages")
    val packages = ArrayList<OtaPackage>(array.length())
    for (i in 0 until array.length()) {
        val o = array.getJSONObject(i)
        // An unknown kind is skipped rather than failing the document, so that
        // adding a new kind later does not stop older builds from updating.
        if (o.optString("kind") != Ota.KIND_APK) continue
        val notes = o.optJSONArray("releaseNotes")
        packages += OtaPackage(
            kind = o.getString("kind"),
            packageName = o.getString("packageName"),
            versionCode = o.getInt("versionCode"),
            versionName = o.getString("versionName"),
            url = o.getString("url"),
            size = o.getLong("size"),
            sha256 = o.getString("sha256").lowercase(),
            signerSha256 = o.getString("signerSha256"),
            minSdk = o.optInt("minSdk", 1),
            maxSdk = if (o.isNull("maxSdk")) null else o.optInt("maxSdk"),
            isHome = o.optBoolean("isHome", false),
            mandatory = o.optBoolean("mandatory", false),
            releaseNotes = buildList {
                if (notes != null) for (n in 0 until notes.length()) add(notes.getString(n))
            },
        )
    }
    OtaManifest(
        schema = root.getInt("schema"),
        serial = root.getInt("serial"),
        packages = packages,
    )
} catch (e: Exception) {
    Log.w(Ota.TAG, "manifest is signed but malformed", e)
    null
}
