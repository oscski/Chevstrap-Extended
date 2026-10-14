package com.chevstrap.rbx.utility

import android.content.pm.PackageManager
import com.chevstrap.rbx.App
import rikka.shizuku.Shizuku
import java.util.concurrent.TimeUnit

/**
 * Shell access through Shizuku (no root needed).
 *
 * Unlike [RootShell] (which reads `/data/data/<robloxPkg>/files/...`
 * as uid=0), this only tails `logcat -s Roblox` as shell (uid=2000).
 * Release Roblox duplicates its `FLog` lines (`Joining game`,
 * `Connection accepted from`, `game_join_loadtime`, `[BloxstrapRPC]`)
 * to logcat with tag `Roblox`, so the watcher can parse the same
 * markers without ever touching the private directory.
 */
object ShizukuShell {
    private const val TAG = "ShizukuShell"

    const val PERMISSION_REQUEST_CODE = 10001

    /** True when the Shizuku manager binder is reachable (app installed + running). */
    @JvmStatic
    fun isBinderAlive(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (_: Exception) {
            false
        }
    }

    /** True when Shizuku granted *us* permission (via [ShizukuProvider] flow). */
    @JvmStatic
    fun isPermissionGranted(): Boolean {
        return try {
            isBinderAlive() &&
                    Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) {
            false
        }
    }

    /** Fire the Shizuku permission dialog. Result arrives on the result listener. */
    @JvmStatic
    fun requestPermission(requestCode: Int = PERMISSION_REQUEST_CODE) {
        try {
            Shizuku.requestPermission(requestCode)
        } catch (e: Exception) {
            App.logger.writeException("$TAG::requestPermission", e)
        }
    }

    @JvmStatic
    fun addPermissionListener(listener: Shizuku.OnRequestPermissionResultListener) {
        try {
            Shizuku.addRequestPermissionResultListener(listener)
        } catch (e: Exception) {
            App.logger.writeException("$TAG::addPermissionListener", e)
        }
    }

    @JvmStatic
    fun removePermissionListener(listener: Shizuku.OnRequestPermissionResultListener) {
        try {
            Shizuku.removeRequestPermissionResultListener(listener)
        } catch (_: Exception) {
        }
    }

    /**
     * Run [cmd] as shell and return trimmed stdout, or null on
     * timeout/error. For tiny probes only (`pidof`); logcat streaming
     * uses [openRobloxLogcat] instead.
     *
     * Note: `ShizukuRemoteProcess.waitFor(timeout, unit)` is unreliable
     * (may report finished while still running), so readiness is driven
     * by stdout EOF via `Thread.join(timeout)` instead — pure Java,
     * no Shizuku involvement.
     */
    @JvmStatic
    fun execToString(cmd: Array<String>, timeoutMs: Long = 8_000L): String? {
        var process: Process? = null
        return try {
            process = Shizuku.newProcess(cmd, null, null)
            val p: Process = process!!
            var stdout: ByteArray? = null
            val tOut = Thread {
                try {
                    stdout = p.inputStream.readBytes()
                } catch (_: Exception) {
                }
            }
            val tErr = Thread {
                try {
                    p.errorStream.readBytes()
                } catch (_: Exception) {
                }
            }
            tOut.isDaemon = true
            tErr.isDaemon = true
            tOut.start()
            tErr.start()

            tOut.join(timeoutMs)
            if (tOut.isAlive) {
                p.destroyForcibly()
                tOut.join(1000)
                tErr.join(1000)
                return null
            }
            tErr.join(2000)

            // Reap; unbounded wait is safe now that stdout hit EOF.
            // Guarded: never trust the timed waitFor/exitValue combo here.
            try {
                p.waitFor(5_000L, TimeUnit.MILLISECONDS)
            } catch (_: Exception) {
            }
            val code = try {
                p.exitValue()
            } catch (_: IllegalArgumentException) {
                -1
            }
            if (code != 0) return null
            stdout?.toString(Charsets.UTF_8)?.trim().takeIf { !it.isNullOrEmpty() }
        } catch (e: Exception) {
            App.logger.writeException("$TAG::execToString", e)
            null
        } finally {
            runCatching { process?.inputStream?.close() }
            runCatching { process?.errorStream?.close() }
            runCatching { process?.outputStream?.close() }
            process?.destroy()
        }
    }

    /** PIDs currently running for [packageName], empty when none/error. */
    @JvmStatic
    fun pidsFor(packageName: String): List<Int> {
        val out = execToString(arrayOf("pidof", packageName)) ?: return emptyList()
        return out.split(Regex("\\s+"))
            .mapNotNull { it.trim().toIntOrNull() }
    }

    @JvmStatic
    fun isProcessRunning(packageName: String): Boolean {
        return try {
            pidsFor(packageName).isNotEmpty()
        } catch (_: Exception) {
            // Fail-open so a broken probe never kills a healthy session.
            true
        }
    }

    /**
     * Open a following `logcat -s Roblox` stream as shell.
     * `-T 1` starts near-EOF (like the file tail starting from EOF);
     * callers should discard the first line, which may be historic.
     * Returns null when Shizuku is unavailable. Caller owns the process
     * and must destroy it when done.
     */
    @JvmStatic
    fun openRobloxLogcat(): Process? {
        return try {
            if (!isPermissionGranted()) {
                App.logger.writeLine(TAG, "openRobloxLogcat: permission not granted")
                return null
            }
            Shizuku.newProcess(
                arrayOf("logcat", "-v", "brief", "-T", "1", "-s", "Roblox"),
                null,
                null
            )
        } catch (e: Exception) {
            App.logger.writeException("$TAG::openRobloxLogcat", e)
            null
        }
    }

    /** One-line status for diagnostics when no directory/mode is reachable. */
    @JvmStatic
    fun describeStatus(): String {
        return try {
            val alive = isBinderAlive()
            val granted = isPermissionGranted()
            val version = runCatching { Shizuku.getVersion() }.getOrDefault(-1)
            "binderAlive=$alive permissionGranted=$granted version=$version"
        } catch (e: Exception) {
            "status probe failed: ${e.message}"
        }
    }
}
