package app.tsunagi.e46m3.launcher.ota

import android.util.Log
import java.security.KeyStore
import java.security.cert.CertPathValidator
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.PKIXParameters
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * TLS for the OTA fetches, with the certificate chain checked against the
 * system's CAs but **not against this unit's clock**.
 *
 * ## Why
 *
 * The head unit's clock is never right (operator, 2026-10-29: the unit is
 * broken and its time never matches). TLS checks every certificate's validity
 * dates against that clock, so whether a fetch worked depended on which host
 * it hit: on the car, v0.3.0 read the manifest from github.com and then failed
 * to download the APK from GitHub's asset host, whose certificate did not
 * cover the date the unit believed it was. Until then the subsystem waited for
 * a correct clock (the removed OtaClock); on this unit that wait never ends.
 *
 * ## What is still checked
 *
 * The platform's own check runs first and decides in every normal case. Only
 * when it refuses is the chain checked again: same system trust anchors, same
 * signatures, same constraints, but at an instant taken from the chain itself —
 * the latest notBefore in it, which is a moment every certificate in the chain
 * claims to cover. A chain that does not lead to a trusted root still fails;
 * one whose certificates never overlap still fails; the hostname is still
 * verified by HttpsURLConnection. What no longer fails is "this unit thinks it
 * is 2006".
 *
 * That is affordable here, and only here, because TLS is not what makes an
 * update trustworthy. The manifest carries our own RSA signature, checked
 * against the key compiled into this APK; it names the APK's SHA-256; and the
 * APK's signing certificate must match the one this process runs under
 * (docs/07 §2.1). An expired certificate in somebody else's hands gets them a
 * connection, not an install.
 */
internal object OtaTls {

    val socketFactory: SSLSocketFactory by lazy {
        SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(ClockFreeTrustManager(platformTrustManager())), null)
        }.socketFactory
    }

    private fun platformTrustManager(): X509TrustManager =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(null as KeyStore?) }
            .trustManagers
            .filterIsInstance<X509TrustManager>()
            .first()

    private class ClockFreeTrustManager(
        private val platform: X509TrustManager,
    ) : X509TrustManager {

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            try {
                platform.checkServerTrusted(chain, authType)
            } catch (refused: CertificateException) {
                // Any refusal, not only a date one: the platform does not reliably
                // say which it was, and a refusal for a reason other than the
                // date fails the check below as well.
                checkAtChainTime(chain, refused)
            }
        }

        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
            platform.checkClientTrusted(chain, authType)

        override fun getAcceptedIssuers(): Array<X509Certificate> = platform.acceptedIssuers

        private fun checkAtChainTime(chain: Array<X509Certificate>, refused: CertificateException) {
            if (chain.isEmpty()) throw refused
            val at = Date(chain.maxOf { it.notBefore.time } + 1_000L)
            try {
                val anchors = KeyStore.getInstance("AndroidCAStore").apply { load(null) }
                val params = PKIXParameters(anchors).apply {
                    date = at
                    // Revocation needs the network and a clock; neither is the
                    // safety net here (see the class docs).
                    isRevocationEnabled = false
                }
                // A self-signed certificate at the end is the root itself. PKIX
                // wants the path to stop below its anchor.
                val path = chain.toList().let { certs ->
                    if (certs.size > 1 && certs.last().subjectX500Principal == certs.last().issuerX500Principal)
                        certs.dropLast(1) else certs
                }
                CertPathValidator.getInstance("PKIX").validate(
                    CertificateFactory.getInstance("X.509").generateCertPath(path),
                    params,
                )
                Log.i(
                    Ota.TAG,
                    "TLS chain for ${chain[0].subjectX500Principal} accepted at its own time $at " +
                        "(device clock reads ${Date()})",
                )
            } catch (e: Exception) {
                refused.addSuppressed(e)
                throw refused
            }
        }
    }
}
