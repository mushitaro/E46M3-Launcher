package app.tsunagi.e46m3.launcher.ota

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature

/**
 * The OTA security boundary, proven against a real RSA-2048 key pair.
 *
 * ## Why this is worth a test file
 *
 * Everything else in the OTA subsystem is a convenience. This is the part that
 * decides whether a head unit installs what we published or whatever the
 * transport handed it — and the transport is a garage WiFi on a device whose
 * clock reads 2006, so TLS is not a second line of defence here, it is an
 * optional one. Every failure mode below is one that would otherwise first be
 * observed by driving somewhere.
 *
 * Keys are generated per run rather than checked in. A committed key pair would
 * eventually be mistaken for the real one, and the real one is deliberately not
 * in this repository.
 */
class ManifestVerifierTest {

    private val pair: KeyPair = KeyPairGenerator.getInstance("RSA")
        .apply { initialize(2048) }
        .generateKeyPair()

    private val otherPair: KeyPair = KeyPairGenerator.getInstance("RSA")
        .apply { initialize(2048) }
        .generateKeyPair()

    private fun sign(bytes: ByteArray, key: KeyPair = pair): ByteArray =
        Signature.getInstance("SHA256withRSA").run {
            initSign(key.private)
            update(bytes)
            sign()
        }

    /** Shaped exactly like what `tools/otarel.py emit` writes. */
    private fun manifestJson(
        schema: Int = 1,
        serial: Int = 7,
        versionCode: Int = 3,
        kind: String = "apk",
        extraPackage: String = "",
    ) = """
        {
          "schema": $schema,
          "serial": $serial,
          "packages": [
            {
              "kind": "$kind",
              "packageName": "app.tsunagi.e46m3.launcher",
              "versionCode": $versionCode,
              "versionName": "0.3.0",
              "url": "https://example.invalid/app.apk",
              "size": 3114250,
              "sha256": "BD92A7E7D1277D8C5F6E2D1A724455332E688CBBD8C35A1B0F3B60A1B1F96219",
              "signerSha256": "8E529141EF09ABDB50D95930A25263153834AC3B385C9F7603B15FE8BCFDB580",
              "minSdk": 21,
              "maxSdk": null,
              "isHome": true,
              "mandatory": false,
              "releaseNotes": ["first", "second"]
            }$extraPackage
          ]
        }
    """.trimIndent().toByteArray(Charsets.UTF_8)

    private fun verify(
        bytes: ByteArray,
        signature: ByteArray = sign(bytes),
        minSerial: Int = 0,
        key: KeyPair = pair,
    ) = ManifestVerifier.verify(key.public.encoded, bytes, signature, minSerial)

    private fun rejection(result: ManifestVerifier.Result): String {
        assertTrue("expected a rejection, got $result", result is ManifestVerifier.Result.Rejected)
        return (result as ManifestVerifier.Result.Rejected).why
    }

    // ── The happy path ──────────────────────────────────────────────────────

    @Test
    fun `a correctly signed manifest parses`() {
        val result = verify(manifestJson())
        assertTrue("$result", result is ManifestVerifier.Result.Ok)
        val manifest = (result as ManifestVerifier.Result.Ok).manifest
        assertEquals(7, manifest.serial)
        assertEquals(1, manifest.packages.size)

        val entry = manifest.apkFor("app.tsunagi.e46m3.launcher")!!
        assertEquals(3, entry.versionCode)
        assertEquals("0.3.0", entry.versionName)
        assertEquals(3114250L, entry.size)
        assertTrue(entry.isHome)
        assertEquals(listOf("first", "second"), entry.releaseNotes)
        // Lowercased on the way in, because it is compared against a locally
        // computed digest and MessageDigest output is lowercase hex.
        assertEquals("bd92a7e7d1277d8c5f6e2d1a724455332e688cbbd8c35a1b0f3b60a1b1f96219", entry.sha256)
    }

    // ── Tampering ───────────────────────────────────────────────────────────

    @Test
    fun `one flipped byte anywhere in the document is refused`() {
        val bytes = manifestJson()
        val signature = sign(bytes)
        // Not a semantic edit — a single bit. The signature covers bytes, and
        // that is the property being pinned down.
        val tampered = bytes.copyOf().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 0x01).toByte() }
        assertEquals("SIG_INVALID", rejection(verify(tampered, signature)))
    }

    @Test
    fun `swapping the download URL is refused, which is the attack that matters`() {
        val bytes = manifestJson()
        val signature = sign(bytes)
        val swapped = String(bytes, Charsets.UTF_8)
            .replace("https://example.invalid/app.apk", "https://evil.invalid/app.apk")
            .toByteArray(Charsets.UTF_8)
        assertEquals("SIG_INVALID", rejection(verify(swapped, signature)))
    }

    @Test
    fun `a signature from a different key is refused`() {
        val bytes = manifestJson()
        assertEquals("SIG_INVALID", rejection(verify(bytes, sign(bytes, otherPair))))
    }

    @Test
    fun `a manifest verified against the wrong public key is refused`() {
        val bytes = manifestJson()
        assertEquals("SIG_INVALID", rejection(verify(bytes, sign(bytes), key = otherPair)))
    }

    @Test
    fun `garbage in place of a signature is refused rather than thrown`() {
        val bytes = manifestJson()
        assertTrue(rejection(verify(bytes, ByteArray(256))).startsWith("SIG_"))
    }

    @Test
    fun `an unparseable public key is refused rather than thrown`() {
        val bytes = manifestJson()
        val result = ManifestVerifier.verify(ByteArray(8), bytes, sign(bytes), 0)
        assertEquals("KEY", rejection(result))
    }

    // ── Replay and staleness ────────────────────────────────────────────────

    @Test
    fun `a serial below the highest already seen is refused`() {
        // The replay: an attacker who controls the transport serves yesterday's
        // genuinely signed manifest, pointing at a version with a known defect.
        val result = verify(manifestJson(serial = 5), minSerial = 9)
        assertTrue(rejection(result).startsWith("STALE_SERIAL_5_SEEN_9"))
    }

    @Test
    fun `the same serial is accepted, so a pending update survives a re-check`() {
        // Regression guard. With a strict > here, the second check of a boot
        // would reject the very manifest the first one accepted, and an update
        // the owner had been told about would silently disappear.
        val result = verify(manifestJson(serial = 9), minSerial = 9)
        assertTrue("$result", result is ManifestVerifier.Result.Ok)
    }

    // ── Shape ───────────────────────────────────────────────────────────────

    @Test
    fun `a signed but malformed document is refused`() {
        val bytes = "{ not json at all".toByteArray(Charsets.UTF_8)
        assertEquals("MALFORMED", rejection(verify(bytes)))
    }

    @Test
    fun `a signed document missing a required field is refused`() {
        val bytes = """{"schema":1,"serial":3,"packages":[{"kind":"apk"}]}"""
            .toByteArray(Charsets.UTF_8)
        assertEquals("MALFORMED", rejection(verify(bytes)))
    }

    @Test
    fun `a future schema is refused, and says so distinctly`() {
        // Not the same failure as an attack: this build is simply too old. The
        // log has to be able to tell those apart.
        assertEquals("SCHEMA_2", rejection(verify(manifestJson(schema = 2))))
    }

    @Test
    fun `an unknown package kind is skipped, not fatal`() {
        // Forward compatibility: adding a new kind later must not stop already
        // deployed builds from updating themselves.
        val bytes = manifestJson(
            extraPackage = """,
            {
              "kind": "web",
              "id": "tuner",
              "origin": "https://example.invalid",
              "buildId": "deadbeef"
            }""",
        )
        val result = verify(bytes)
        assertTrue("$result", result is ManifestVerifier.Result.Ok)
        val manifest = (result as ManifestVerifier.Result.Ok).manifest
        assertEquals(1, manifest.packages.size)
        assertEquals("apk", manifest.packages[0].kind)
    }

    @Test
    fun `a manifest with no entry for us is valid and simply says nothing`() {
        val result = verify(manifestJson(kind = "web"))
        assertTrue("$result", result is ManifestVerifier.Result.Ok)
        val manifest = (result as ManifestVerifier.Result.Ok).manifest
        assertEquals(null, manifest.apkFor("app.tsunagi.e46m3.launcher"))
    }

    // ── SDK gating ──────────────────────────────────────────────────────────

    @Test
    fun `runsOn respects both ends of the range`() {
        val entry = (verify(manifestJson()) as ManifestVerifier.Result.Ok)
            .manifest.apkFor("app.tsunagi.e46m3.launcher")!!
        assertTrue(entry.runsOn(27))
        assertTrue(entry.runsOn(21))
        assertTrue("minSdk 21 must exclude 19", !entry.runsOn(19))
        assertTrue("a null maxSdk must not cap anything", entry.runsOn(35))
    }
}
