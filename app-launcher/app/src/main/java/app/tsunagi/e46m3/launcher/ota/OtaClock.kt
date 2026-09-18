package app.tsunagi.e46m3.launcher.ota

import app.tsunagi.e46m3.launcher.BuildConfig
import java.security.cert.CertificateExpiredException
import java.security.cert.CertificateNotYetValidException

/**
 * Decides whether the device clock can be trusted enough to open a TLS
 * connection, and tells a clock fault apart from a network fault afterwards.
 *
 * ## Why this exists
 *
 * This head unit's RTC is frequently wrong. The evidence is in the repository:
 * `device-extract/bugreport.zip` is named
 * `bugreport-FF-5000-O11019-2006-12-18-16-42-48.txt`, and `sdcard/TsCrash/`
 * contains `crash-2006-02-03-…` and `crash-2006-06-22-…` alongside the real
 * 2021–2023 ones. With the clock reading 2006, every certificate in the chain
 * is "not yet valid" and the handshake fails before any payload exists.
 *
 * The response is deliberately not clever: **do not connect**. Wait for the
 * clock to become plausible and try again. `HomeActivity` already receives
 * `ACTION_TIME_CHANGED`, which is what `AlarmManagerService.setTime()` broadcasts
 * after an NTP sync, so the retry has a real trigger and needs no new receiver.
 *
 * ## Why the floor is the build's own commit time
 *
 * A fixed floor ages: a constant that says "2025" is a weaker and weaker check
 * every year. The commit time of the build that is running self-maintains, and
 * it states something that cannot be argued with — **the device cannot
 * legitimately read a date before the build it is executing**.
 *
 * `BuildConfig.BUILD_EPOCH` is 0 when the tree had no git (see
 * `app/build.gradle.kts`), so a fixed floor stays underneath it as a backstop.
 */
internal object OtaClock {

    /** 2025-01-01T00:00:00Z. A backstop for builds made without git metadata. */
    private const val ABSOLUTE_FLOOR_MS = 1_735_689_600_000L

    /** Cause chains are trees in principle; this bounds a pathological one. */
    private const val MAX_CAUSE_DEPTH = 16

    fun floorMillis(): Long =
        maxOf(BuildConfig.BUILD_EPOCH * 1000L, ABSOLUTE_FLOOR_MS)

    fun isPlausible(nowMillis: Long = System.currentTimeMillis()): Boolean =
        nowMillis >= floorMillis()

    /**
     * True when [t] is, anywhere in its cause chain, a certificate validity
     * failure.
     *
     * The distinction earns its keep in the pane: "CLOCK WRONG — WAITING FOR
     * TIME" tells the owner what is happening and that it will fix itself.
     * "UPDATE CHECK FAILED" tells them nothing and invites a trip to the garage
     * with a laptop.
     */
    fun isDateFault(t: Throwable?): Boolean {
        var cause: Throwable? = t
        var depth = 0
        while (cause != null && depth++ < MAX_CAUSE_DEPTH) {
            if (cause is CertificateNotYetValidException || cause is CertificateExpiredException) {
                return true
            }
            // Conscrypt does not always chain the certificate exception; on some
            // paths the reason survives only in the message. Cheap to also ask.
            val message = cause.message
            if (message != null &&
                (message.contains("NotYetValid", ignoreCase = true) ||
                    message.contains("current time", ignoreCase = true) ||
                    message.contains("certificate expired", ignoreCase = true))
            ) {
                return true
            }
            val next = cause.cause
            if (next === cause) break
            cause = next
        }
        return false
    }

    /**
     * A handshake failure while the clock is already implausible is a clock
     * fault whether or not the exception says so — we should not have connected
     * at all, and reporting it as a network problem would send somebody looking
     * at the router.
     */
    fun looksLikeClockFault(t: Throwable?, nowMillis: Long = System.currentTimeMillis()): Boolean =
        isDateFault(t) || !isPlausible(nowMillis)
}
