package app.tsunagi.e46m3.launcher.ota

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

/**
 * Downloads an APK and refuses to hand back anything it has not proven.
 *
 * ## A `.part` file is never installable
 *
 * The staging file carries a `.part` suffix until its length and its SHA-256
 * both match the signed manifest, and only then is it renamed. The rename is
 * the commitment. Nothing else in the subsystem looks at `.part` files, so
 * "power went off mid-download" and "this APK is ready" cannot be confused —
 * which matters on a unit whose power is the ignition key.
 *
 * ## Resuming
 *
 * A key-off kills the download wherever it was. The next attempt sends
 * `Range: bytes=<what we have>-` and appends. Two details make that safe:
 *
 *  - the existing prefix is re-read through the same [MessageDigest] before
 *    anything new is appended, so the running hash describes the whole file
 *    rather than only today's half;
 *  - **a 200 in reply to a ranged request means the server ignored it**, and the
 *    body is the whole file from byte zero. Appending that to a prefix produces
 *    a corrupt APK that only the final hash would catch, one wasted download
 *    later. So a 200 truncates and starts again, deliberately.
 */
internal class OtaStore(private val context: Context) {

    sealed class Result {
        /** Verified, renamed, installable. */
        data class Ready(val apk: File) : Result()

        data class Failed(val reason: String, val clockFault: Boolean = false) : Result()
    }

    /**
     * @param onProgress bytes-so-far and total, on the calling (worker) thread.
     *        Throttled by the caller if it wants to touch the UI.
     */
    fun fetch(
        entry: OtaPackage,
        onProgress: (Long, Long) -> Unit,
    ): Result {
        val target = Ota.apkFile(context, entry.packageName, entry.versionCode)
        val part = Ota.partFile(context, entry.packageName, entry.versionCode)

        // Already here and still correct? Then there is nothing to fetch. This
        // is the ordinary path on the second boot after a download completed but
        // before the owner pressed anything.
        if (target.isFile && target.length() == entry.size) {
            val have = sha256(target)
            if (have == entry.sha256) {
                Log.i(Ota.TAG, "${target.name} already staged and verified")
                return Result.Ready(target)
            }
            Log.w(Ota.TAG, "${target.name} is stale or damaged ($have) — refetching")
            target.delete()
        }

        prune(entry)

        val digest = MessageDigest.getInstance("SHA-256")
        var have = part.length()
        if (have > entry.size) {
            // Longer than the published file: whatever this is, it is not a
            // prefix of what we want.
            Log.w(Ota.TAG, "${part.name} is $have bytes, manifest says ${entry.size} — restarting")
            part.delete()
            have = 0
        }
        if (have > 0 && !feedExisting(part, digest)) {
            part.delete()
            have = 0
            digest.reset()
        }

        val open = OtaHttp.open(entry.url, rangeFrom = have)
        if (open is OtaHttp.Open.Failed) {
            return Result.Failed(open.reason, open.clockFault)
        }
        val body = (open as OtaHttp.Open.Ok).body

        var written = have
        try {
            body.use {
                if (have > 0 && !body.honouredRange) {
                    // See the class docs. The server sent the whole file; the
                    // only safe reading of that is "start over".
                    Log.w(Ota.TAG, "server ignored Range (HTTP 200) — restarting from zero")
                    digest.reset()
                    part.delete()
                    written = 0
                }

                FileOutputStream(part, /* append = */ written > 0).use { out ->
                    val buffer = ByteArray(BUFFER)
                    while (true) {
                        val read = body.stream.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        written += read
                        if (written > entry.size) {
                            // More bytes than were published. Stop immediately
                            // rather than filling /data because a URL was wrong.
                            Log.w(Ota.TAG, "server sent more than ${entry.size} bytes")
                            return Result.Failed("OVERSIZE")
                        }
                        onProgress(written, entry.size)
                    }
                    out.fd.sync()
                }
            }
        } catch (e: IOException) {
            // The part file stays. A key-off here is the expected case, not an
            // error, and next time it resumes from where it stopped.
            val clock = OtaClock.looksLikeClockFault(e)
            Log.i(Ota.TAG, "download interrupted at $written/${entry.size}", e)
            return Result.Failed(if (clock) "CLOCK" else "INTERRUPTED", clock)
        }

        if (written != entry.size) {
            Log.w(Ota.TAG, "download ended at $written, manifest says ${entry.size}")
            return Result.Failed("SHORT")
        }

        val hex = digest.digest().toHex()
        if (hex != entry.sha256) {
            // Not recoverable by retrying the same bytes: something between the
            // publisher and here changed them. Start clean next time.
            Log.e(Ota.TAG, "sha256 is $hex, manifest says ${entry.sha256}")
            part.delete()
            return Result.Failed("HASH_MISMATCH")
        }

        if (!part.renameTo(target)) {
            Log.e(Ota.TAG, "could not rename ${part.name} -> ${target.name}")
            return Result.Failed("RENAME")
        }
        Log.i(Ota.TAG, "staged ${target.name}, $written bytes, sha256 ok")
        return Result.Ready(target)
    }

    /**
     * Copies the APK this process is running from into `ota/prev/`, so there is
     * something to go back to.
     *
     * `applicationInfo.sourceDir` is our own installed APK and is readable
     * without any permission. Taken **before** the replacement is committed,
     * because afterwards `sourceDir` points at the new one — and because after
     * a self-update there is no "afterwards" in this process.
     *
     * Exactly one is kept. A second would be a version nobody has run for two
     * updates, which is not a rollback target, it is three megabytes.
     *
     * @return the file, or null. A failure here does not stop an update: losing
     *         the local rollback copy is a smaller problem than refusing to
     *         apply a fix, and the release-side rollback (publish the old source
     *         as a higher versionCode) is unaffected by it.
     */
    fun keepCurrentAsPrevious(packageName: String, versionCode: Int): File? = try {
        val source = File(context.applicationInfo.sourceDir)
        val target = File(Ota.prevDir(context), "$packageName-$versionCode.apk")
        if (target.isFile && target.length() == source.length()) {
            target
        } else {
            Ota.prevDir(context).listFiles()?.forEach { if (it != target) it.delete() }
            val tmp = File(target.parentFile, target.name + ".part")
            source.inputStream().use { input ->
                FileOutputStream(tmp).use { out ->
                    input.copyTo(out, BUFFER)
                    out.fd.sync()
                }
            }
            if (tmp.renameTo(target)) {
                Log.i(Ota.TAG, "kept ${target.name} (${target.length()} bytes) for rollback")
                target
            } else {
                tmp.delete()
                null
            }
        }
    } catch (e: Exception) {
        Log.w(Ota.TAG, "could not keep the current APK for rollback", e)
        null
    }

    /**
     * The kept previous APK, if there is one and it is not what is running now.
     */
    fun previous(packageName: String, currentVersionCode: Int): Pair<File, Int>? {
        val files = Ota.prevDir(context).listFiles() ?: return null
        for (file in files) {
            if (!file.name.endsWith(".apk")) continue
            val code = file.name
                .removePrefix("$packageName-")
                .removeSuffix(".apk")
                .toIntOrNull() ?: continue
            if (code != currentVersionCode) return file to code
        }
        return null
    }

    /** Everything staged for some other version. Keeps /data from collecting APKs. */
    fun prune(keep: OtaPackage?) {
        val keepNames = setOfNotNull(
            keep?.let { Ota.apkFile(context, it.packageName, it.versionCode).name },
            keep?.let { Ota.partFile(context, it.packageName, it.versionCode).name },
        )
        Ota.dir(context).listFiles()?.forEach { file ->
            if (file.isDirectory) return@forEach
            if (file.name in keepNames) return@forEach
            if (file.name.endsWith(".apk") || file.name.endsWith(".apk.part")) {
                if (file.delete()) Log.i(Ota.TAG, "pruned ${file.name}")
            }
        }
    }

    /**
     * Re-reads an existing prefix into [digest].
     *
     * @return false if it could not be read, in which case the caller starts
     *         over. Silently continuing with a digest that describes only part
     *         of the file would produce a mismatch at the very end of a
     *         download, which is the most expensive place to find one.
     */
    private fun feedExisting(part: File, digest: MessageDigest): Boolean = try {
        part.inputStream().use { input ->
            val buffer = ByteArray(BUFFER)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        Log.i(Ota.TAG, "resuming ${part.name} from ${part.length()} bytes")
        true
    } catch (e: IOException) {
        Log.w(Ota.TAG, "could not re-read ${part.name}", e)
        false
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHex()
    }

    private companion object {
        /**
         * 8 KB. The APK is never held in memory — a 192 MB heap with ~1.1 GB
         * free on the device is not somewhere to put three megabytes that only
         * need to pass through.
         */
        const val BUFFER = 8 * 1024
    }
}

/** Lowercase hex, to compare against the manifest and against `sha256sum`. */
internal fun ByteArray.toHex(): String {
    val out = StringBuilder(size * 2)
    for (b in this) {
        val v = b.toInt() and 0xFF
        out.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
    }
    return out.toString()
}

private val HEX = "0123456789abcdef".toCharArray()
