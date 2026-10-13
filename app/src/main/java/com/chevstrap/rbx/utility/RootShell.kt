package com.chevstrap.rbx.utility

import com.chevstrap.rbx.App
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Minimal helper for running commands as root via `su`.
 *
 * Used so Chevstrap can read the Roblox private directory
 * (`/data/data/<robloxPkg>/files/...`) on rooted devices without
 * requiring Chevstrap + Roblox to run inside the same Virtual Space.
 *
 * All I/O here is read-only against the Roblox directory. Nothing is
 * written, chmod'ed or chown'ed inside another app's private data.
 */
object RootShell {
    private const val TAG = "RootShell"
    private const val DEFAULT_TIMEOUT_MS = 10_000L
    private const val ROOT_CACHE_MS = 30_000L

    data class CommandResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String
    )

    @Volatile
    private var cachedRootGranted: Boolean? = null

    @Volatile
    private var cachedAt: Long = 0L

    /** Quote a single shell argument so paths with spaces/quotes are safe. */
    @JvmStatic
    fun esc(arg: String): String {
        return "'" + arg.replace("'", "'\"'\"'") + "'"
    }

    @JvmStatic
    fun isSuBinaryPresent(): Boolean {
        val knownPaths = listOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/su/bin/su",
            "/magisk/.core/bin/su",
            "/data/adb/magisk/busybox/su"
        )
        if (knownPaths.any { File(it).exists() }) return true

        return try {
            val p = Runtime.getRuntime().exec(arrayOf("which", "su"))
            val finished = p.waitFor(3, TimeUnit.SECONDS)
            if (!finished) {
                p.destroyForcibly()
                return false
            }
            p.exitValue() == 0
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Returns true if `su -c id` succeeds and reports uid=0.
     * The first call triggers the system root permission prompt.
     */
    @JvmStatic
    @Synchronized
    fun isRootGranted(force: Boolean = false): Boolean {
        val now = System.currentTimeMillis()
        if (!force && cachedRootGranted != null && now - cachedAt < ROOT_CACHE_MS) {
            return cachedRootGranted == true
        }

        var granted = false
        try {
            val res = execAsRoot("id", timeoutMs = 8_000L)
            granted = res.exitCode == 0 && res.stdout.contains("uid=0")
        } catch (e: Exception) {
            App.logger.writeException("$TAG::isRootGranted", e)
            granted = false
        }

        cachedRootGranted = granted
        cachedAt = now
        App.logger.writeLine("$TAG::isRootGranted", "root granted=$granted")
        return granted
    }

    /** Explicitly trigger the root permission prompt (same as [isRootGranted]). */
    @JvmStatic
    fun requestRootPermission(): Boolean = isRootGranted(force = true)

    @JvmStatic
    fun clearCache() {
        cachedRootGranted = null
        cachedAt = 0L
        cachedSuMode = null
    }

    @JvmStatic
    fun execAsRoot(command: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): CommandResult {
        val bytes = execAsRootBytes(command, timeoutMs)
        return CommandResult(
            bytes.exitCode,
            String(bytes.stdout, Charsets.UTF_8),
            String(bytes.stderr, Charsets.UTF_8)
        )
    }

    data class RawResult(
        val exitCode: Int,
        val stdout: ByteArray,
        val stderr: ByteArray
    )

    /**
     * How `su` commands are executed. Some setups (Storage Isolation and
     * similar sandboxes) mask `/data/data` inside the app's mount namespace
     * with a tmpfs, so plain `su -c` (which inherits that namespace) cannot
     * see other apps even as root. MM/NSENTER escape to the global namespace.
     */
    enum class SuMode {
        PLAIN,
        MM,
        NSENTER
    }

    @Volatile
    private var cachedSuMode: SuMode? = null

    /**
     * Detect the best working `su` strategy. Cached; pass force=true to
     * re-probe (e.g. after the user changes root settings).
     */
    @JvmStatic
    @Synchronized
    fun detectSuMode(force: Boolean = false): SuMode {
        if (!force) cachedSuMode?.let { return it }

        val mode = when {
            rawSu(arrayOf("su", "-mm", "-c", "id")).isUidZero() -> SuMode.MM
            rawSu(arrayOf("su", "-c", "nsenter -t 1 -m id")).isUidZero() -> SuMode.NSENTER
            else -> SuMode.PLAIN
        }
        cachedSuMode = mode
        App.logger.writeLine("$TAG::detectSuMode", "su mode=$mode")
        return mode
    }

    private data class RawSuResult(
        val exitCode: Int,
        val stdout: String
    ) {
        fun isUidZero(): Boolean = exitCode == 0 && stdout.contains("uid=0")
    }

    /** Execute an argv directly (no mode wrapping), never throws. */
    private fun rawSu(argv: Array<String>, timeoutMs: Long = 8_000L): RawSuResult {
        var process: Process? = null
        return try {
            process = Runtime.getRuntime().exec(argv)
            val p: Process = process!!
            val out = StringBuilder()
            val t = Thread {
                try {
                    p.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { out.append(it).append('\n') }
                    }
                } catch (_: Exception) {
                }
            }
            t.isDaemon = true
            t.start()
            val finished = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            t.join(3000)
            runCatching { p.outputStream.close() }
            if (!finished) {
                p.destroyForcibly()
                RawSuResult(-1, "")
            } else {
                RawSuResult(p.exitValue(), out.toString())
            }
        } catch (_: Exception) {
            RawSuResult(-1, "")
        } finally {
            runCatching { process?.inputStream?.close() }
            runCatching { process?.errorStream?.close() }
            runCatching { process?.outputStream?.close() }
            process?.destroy()
        }
    }

    /**
     * Run `su [-mm] -c <command>` and capture raw stdout bytes.
     * Needed for log tailing so no charset mangling happens.
     */
    @JvmStatic
    fun execAsRootBytes(command: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): RawResult {
        var process: Process? = null
        return try {
            val (argv, finalCommand) = when (detectSuMode()) {
                SuMode.MM -> arrayOf("su", "-mm", "-c") to command
                SuMode.NSENTER -> arrayOf("su", "-c") to
                        "nsenter -t 1 -m sh -c ${esc(command)}"
                SuMode.PLAIN -> arrayOf("su", "-c") to command
            }
            val proc = Runtime.getRuntime().exec(argv + finalCommand)
            process = proc
            val p: Process = proc
            var stdout = ByteArray(0)
            var stderr = ByteArray(0)
            var outErr: Exception? = null

            val tOut = Thread {
                try {
                    stdout = p.inputStream.readBytes()
                } catch (e: Exception) {
                    outErr = e
                }
            }
            val tErr = Thread {
                try {
                    stderr = p.errorStream.readBytes()
                } catch (_: Exception) {
                }
            }
            tOut.start()
            tErr.start()

            val finished = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                p.destroyForcibly()
                tOut.join(1000)
                tErr.join(1000)
                throw RuntimeException("su command timed out after ${timeoutMs}ms: $command")
            }
            tOut.join(5000)
            tErr.join(5000)
            outErr?.let { throw it }

            // Drain stdin (we never write to it) and close.
            runCatching { p.outputStream.close() }

            RawResult(p.exitValue(), stdout, stderr)
        } catch (e: Exception) {
            throw e
        } finally {
            runCatching { process?.inputStream?.close() }
            runCatching { process?.errorStream?.close() }
            runCatching { process?.outputStream?.close() }
            process?.destroy()
        }
    }

    // ---- Convenience read-only helpers for Roblox private dirs ----

    @JvmStatic
    fun exists(path: String): Boolean {
        return try {
            // Primary: `ls -ld` exit code (proven to work under KSU).
            val ls = execAsRoot("ls -ld ${esc(path)} 2>/dev/null", 8_000L)
            if (ls.exitCode == 0) return true
            // Fallback: `test -e`.
            val res = execAsRoot("test -e ${esc(path)} && echo EXISTS || echo MISSING", 8_000L)
            res.stdout.contains("EXISTS")
        } catch (e: Exception) {
            App.logger.writeException("$TAG::exists", e)
            false
        }
    }

    @JvmStatic
    fun isDirectory(path: String): Boolean {
        return try {
            val res = execAsRoot("test -d ${esc(path)} && echo ISDIR || echo NOTDIR", 8_000L)
            res.stdout.contains("ISDIR")
        } catch (e: Exception) {
            App.logger.writeException("$TAG::isDirectory", e)
            false
        }
    }

    /**
     * True while at least one process for [packageName] exists.
     * Fail-open (returns true on error) so a broken probe can never
     * kill a healthy watcher session.
     */
    @JvmStatic
    fun isProcessRunning(packageName: String): Boolean {
        return try {
            val res = execAsRoot("pidof $packageName 2>/dev/null", 8_000L)
            res.exitCode == 0 && res.stdout.trim().isNotEmpty()
        } catch (e: Exception) {
            App.logger.writeException("$TAG::isProcessRunning", e)
            true
        }
    }

    /** Roblox packages visible to root (`pm list packages`), empty on error. */
    @JvmStatic
    fun installedRobloxPackages(): List<String> {
        return try {
            val res = execAsRoot("pm list packages 2>/dev/null", 15_000L)
            if (res.exitCode != 0) return emptyList()
            res.stdout.lineSequence()
                .map { it.trim().removePrefix("package:").trim() }
                .filter { it.contains("roblox", ignoreCase = true) }
                .toList()
        } catch (e: Exception) {
            App.logger.writeException("$TAG::installedRobloxPackages", e)
            emptyList()
        }
    }

    /**
     * One-shot root diagnostic, logged when no Roblox directory is reachable.
     * Reports identity, SELinux context and raw per-path probe results so a
     * failing `su` environment (e.g. no SELinux domain transition, or a
     * sandboxed mount namespace) can be told apart from a genuinely
     * missing install.
     */
    @JvmStatic
    fun diagnoseAccess(paths: List<String>): String {
        val sb = StringBuilder()
        fun probe(label: String, cmd: String) {
            try {
                val r = execAsRoot(cmd, 10_000L)
                sb.append("[$label] exit=${r.exitCode} " +
                        "out=${r.stdout.trim().take(1200)} " +
                        "err=${r.stderr.trim().take(300)}\n")
            } catch (e: Exception) {
                sb.append("[$label] FAILED: ${e.message}\n")
            }
        }
        probe("id", "id")
        probe("id-Z", "id -Z 2>&1 || cat /proc/self/attr/current 2>&1")
        probe("getenforce", "getenforce 2>&1")
        sb.append("[su-mode] ${detectSuMode()}\n")
        // Explicit per-strategy probes against the real Roblox dir, so the
        // log shows which namespace escape (if any) reaches it.
        for (p in paths) {
            val mm = rawSu(
                arrayOf("su", "-mm", "-c", "ls -ld ${esc(p)} 2>&1"),
                10_000L
            )
            sb.append("[su-mm ls-ld $p] exit=${mm.exitCode} " +
                    "out=${mm.stdout.trim().take(300)}\n")
            val ns = rawSu(
                arrayOf("su", "-c", "nsenter -t 1 -m ls -ld ${esc(p)} 2>&1"),
                10_000L
            )
            sb.append("[nsenter ls-ld $p] exit=${ns.exitCode} " +
                    "out=${ns.stdout.trim().take(300)}\n")
        }
        // Control: own data dir must always be visible. If even this fails,
        // the su session itself is broken. Package resolves dynamically
        // so this survives rebrands (applicationId changes).
        val ownPkg = try {
            App.appContext?.packageName ?: "com.chevstrap.extended"
        } catch (_: Exception) {
            "com.chevstrap.extended"
        }
        probe("ls-ld-own", "ls -ld /data/data/$ownPkg 2>&1")
        // Blanket vs targeted masking: can root see ANY other app dirs?
        probe("ls-datadata", "ls /data/data 2>&1 | head -40")
        // Bind mounts over /data/data reveal isolators (e.g. Storage Isolation).
        probe(
            "mountinfo-datadata",
            "cat /proc/self/mountinfo 2>/dev/null | grep '/data/data' | head -20"
        )
        for (p in paths) {
            probe("ls-ld $p", "ls -ld ${esc(p)} 2>&1")
            probe("test-e $p", "test -e ${esc(p)} && echo EXISTS || echo MISSING-$?")
        }
        probe("ls-datadata-roblox", "ls -ld /data/data/*roblox* /data/user/0/*roblox* 2>&1")
        val report = sb.toString()
        App.logger.writeLine("$TAG::diagnoseAccess", "\n$report")
        return report
    }

    /** Plain file names (no paths) inside [dir], empty list on error. */
    @JvmStatic
    fun listFileNames(dir: String): List<String> {
        return try {
            val res = execAsRoot("ls -1 ${esc(dir)} 2>/dev/null", 8_000L)
            if (res.exitCode != 0) return emptyList()
            res.stdout.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toList()
        } catch (e: Exception) {
            App.logger.writeException("$TAG::listFileNames", e)
            emptyList()
        }
    }

    /** Newest file name (by mtime) inside [dir], or null. */
    @JvmStatic
    fun latestFileName(dir: String): String? {
        return try {
            val res = execAsRoot("ls -1t ${esc(dir)} 2>/dev/null | head -n 1", 8_000L)
            val name = res.stdout.trim()
            if (name.isEmpty()) null else name.substringAfterLast('\n').trim().takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            App.logger.writeException("$TAG::latestFileName", e)
            null
        }
    }

    /** File length in bytes, or -1 on error. */
    @JvmStatic
    fun fileLength(path: String): Long {
        try {
            var res = execAsRoot("stat -c %s ${esc(path)} 2>/dev/null", 8_000L)
            res.stdout.trim().toLongOrNull()?.let { return it }

            res = execAsRoot("wc -c < ${esc(path)} 2>/dev/null", 8_000L)
            res.stdout.trim().split(Regex("\\s+")).firstOrNull()?.toLongOrNull()?.let { return it }
        } catch (e: Exception) {
            App.logger.writeException("$TAG::fileLength", e)
        }
        return -1L
    }

    /**
     * Read bytes of [path] starting at [offset] (0-based).
     * Returns empty array when offset is at/past EOF or on error.
     */
    @JvmStatic
    fun readFromOffset(path: String, offset: Long): ByteArray {
        return try {
            if (offset <= 0L) {
                val res = execAsRootBytes("cat ${esc(path)} 2>/dev/null", 15_000L)
                if (res.exitCode != 0) ByteArray(0) else res.stdout
            } else {
                // tail -c +N is 1-based: byte offset+1 == 0-based offset.
                val res = execAsRootBytes("tail -c +${offset + 1} ${esc(path)} 2>/dev/null", 15_000L)
                if (res.exitCode != 0) ByteArray(0) else res.stdout
            }
        } catch (e: Exception) {
            App.logger.writeException("$TAG::readFromOffset", e)
            ByteArray(0)
        }
    }
}
