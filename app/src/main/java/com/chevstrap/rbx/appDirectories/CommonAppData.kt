package com.chevstrap.rbx.appDirectories

import android.content.pm.PackageManager
import com.chevstrap.rbx.App
import com.chevstrap.rbx.utility.RootShell
import java.io.File

enum class RobloxAccessMode {
    DIRECT,
    ROOT,
    NONE
}

abstract class CommonAppData {
    abstract val executablePackage: String?
    val executablePath: String?
        get() {
            try {
                val robloxDir = this.robloxDirectory
                if (robloxDir != null) {
                    return File(robloxDir, "appData").absolutePath
                }
            } catch (e: Exception) {
                App.logger.writeException("CommonAppData::getExecutablePath", e)
            }
            return null
        }

    val robloxDirectory: String?
        get() = getRobloxDirectory(includeFiles = true)

    fun getRobloxDirectory(includeFiles: Boolean): String? {
        val packageName = this.executablePackage ?: return null

        try {
            val dataDir = App.appContext!!
                .packageManager
                .getApplicationInfo(packageName, 0)
                .dataDir

            if (dataDir != null) {
                return if (includeFiles) {
                    File(dataDir, "files").absolutePath
                } else {
                    File(dataDir).absolutePath
                }
            } else {
                App.logger.writeLine(
                    "CommonAppData::getRobloxDirectory",
                    "dataDir is null"
                )
            }
        } catch (e: PackageManager.NameNotFoundException) {
            App.logger.writeLine(
                "CommonAppData::getRobloxDirectory",
                "failed getting package $packageName " +
                        "(missing <queries> entry or not installed)"
            )
            App.logger.writeException(
                "CommonAppData::getRobloxDirectory",
                e
            )
        }

        val fallback = App.appContext!!.filesDir.absolutePath
            .replace(
                "/" + App.appContext!!.packageName + "/",
                "/$packageName/"
            )

        val result = if (includeFiles) {
            "$fallback/"
        } else {
            fallback.removeSuffix("/files")
        }
        App.logger.writeLine(
            "CommonAppData::getRobloxDirectory",
            "using fallback path for $packageName: $result"
        )
        return result
    }

    /**
     * True when we can list/read the Roblox directory directly
     * (e.g. Chevstrap + Roblox running inside the same Virtual Space).
     */
    fun isDirectAccessAvailable(): Boolean {
        return try {
            val path = getRobloxDirectory(false) ?: run {
                App.logger.writeLine(
                    "CommonAppData::isDirectAccessAvailable",
                    "null path"
                )
                return false
            }
            val dir = File(path)
            val exists = dir.exists()
            val isDir = dir.isDirectory
            val listOk = runCatching {
                // listFiles() returns null when permission is denied.
                dir.listFiles() != null
            }.getOrDefault(false)
            App.logger.writeLine(
                "CommonAppData::isDirectAccessAvailable",
                "path=$path exists=$exists isDir=$isDir listOk=$listOk"
            )
            exists && isDir && listOk
        } catch (e: Exception) {
            App.logger.writeException(
                "CommonAppData::isDirectAccessAvailable",
                e
            )
            false
        }
    }

    /**
     * True when the Roblox directory is reachable via root `su`
     * (read-only: exists check through `su`).
     */
    fun isRootAccessAvailable(): Boolean {
        return try {
            if (!isRootAccessEnabledByConfig()) {
                App.logger.writeLine(
                    "CommonAppData::isRootAccessAvailable",
                    "disabled by config"
                )
                return false
            }
            val path = getRobloxDirectory(false) ?: run {
                App.logger.writeLine(
                    "CommonAppData::isRootAccessAvailable",
                    "null path"
                )
                return false
            }
            val granted = RootShell.isRootGranted()
            val exists = if (granted) RootShell.exists(path) else false
            App.logger.writeLine(
                "CommonAppData::isRootAccessAvailable",
                "path=$path granted=$granted existsViaSu=$exists"
            )
            granted && exists
        } catch (e: Exception) {
            App.logger.writeException(
                "CommonAppData::isRootAccessAvailable",
                e
            )
            false
        }
    }

    fun getAccessMode(): RobloxAccessMode {
        // Check every known Roblox package, not just the preferred one:
        // the preference may be wrong, and PackageManager may be blind
        // to packages outside this profile. First DIRECT win wins,
        // otherwise first ROOT win, otherwise NONE.
        var rootCandidate: String? = null
        for (pkg in KNOWN_ROBLOX_PACKAGES) {
            val dir = getDataDirForPackage(pkg, includeFiles = false) ?: continue
            if (isDirectDirReadable(dir)) {
                App.logger.writeLine(
                    "CommonAppData::getAccessMode",
                    "DIRECT via $pkg ($dir)"
                )
                return RobloxAccessMode.DIRECT
            }
            if (rootCandidate == null && isRootDirReadable(dir)) {
                rootCandidate = pkg
            }
        }
        if (rootCandidate != null) {
            App.logger.writeLine(
                "CommonAppData::getAccessMode",
                "ROOT via $rootCandidate"
            )
            return RobloxAccessMode.ROOT
        }

        // Fall back to single-package checks so existing callers keep
        // working and the detailed per-mode logs are still emitted.
        if (isDirectAccessAvailable()) return RobloxAccessMode.DIRECT
        if (isRootAccessAvailable()) return RobloxAccessMode.ROOT
        App.logger.writeLine(
            "CommonAppData::getAccessMode",
            "NONE; roblox packages visible to root: " +
                    RootShell.installedRobloxPackages()
        )
        RootShell.diagnoseAccess(
            KNOWN_ROBLOX_PACKAGES.map { "/data/data/$it" }
        )
        return RobloxAccessMode.NONE
    }

    /** `/data/.../<pkg>/files/appData` for any package. Null if unresolvable. */
    fun executablePathForPackage(packageName: String): String? {
        return try {
            val robloxDir = getDataDirForPackage(packageName, includeFiles = true)
                ?: return null
            File(robloxDir, "appData").absolutePath
        } catch (e: Exception) {
            App.logger.writeException("CommonAppData::executablePathForPackage", e)
            null
        }
    }

    /** Package actually reachable, preferring DIRECT over ROOT. Null if none. */
    fun findWorkingPackage(): String? {
        for (pkg in KNOWN_ROBLOX_PACKAGES) {
            val dir = getDataDirForPackage(pkg, includeFiles = false) ?: continue
            if (isDirectDirReadable(dir)) return pkg
        }
        for (pkg in KNOWN_ROBLOX_PACKAGES) {
            val dir = getDataDirForPackage(pkg, includeFiles = false) ?: continue
            if (isRootDirReadable(dir)) return pkg
        }
        return try {
            executablePackage
        } catch (_: Exception) {
            null
        }
    }

    fun isAccessible(): Boolean = getAccessMode() != RobloxAccessMode.NONE

    private fun isRootAccessEnabledByConfig(): Boolean {
        return try {
            App.config.data.useRootAccess
        } catch (_: Exception) {
            true
        }
    }

    companion object {
        @JvmStatic
        val KNOWN_ROBLOX_PACKAGES: List<String> = listOf(
            "com.roblox.client",
            "com.roblox.client.vnggames",
            "com.roblox.client.samsunggalaxy"
        )
    }

    /**
     * Resolve `/data/.../<pkg>` for any package, via PackageManager when
     * visible, else via the same filesDir-substitution guess used by
     * [getRobloxDirectory], normalized to the canonical `/data/data/<pkg>`
     * form (`/data/user/0/<pkg>` is just a symlink to it).
     */
    fun getDataDirForPackage(packageName: String, includeFiles: Boolean): String? {
        try {
            val dataDir = App.appContext!!
                .packageManager
                .getApplicationInfo(packageName, 0)
                .dataDir
            if (dataDir != null) {
                return if (includeFiles) {
                    File(dataDir, "files").absolutePath
                } else {
                    File(dataDir).absolutePath
                }
            }
        } catch (_: PackageManager.NameNotFoundException) {
            // Fall through to path guessing below.
        } catch (e: Exception) {
            App.logger.writeException("CommonAppData::getDataDirForPackage", e)
        }

        val guess = try {
            val fb = App.appContext!!.filesDir.absolutePath
                .replace(
                    "/" + App.appContext!!.packageName + "/",
                    "/$packageName/"
                )
            if (includeFiles) "$fb/" else fb.removeSuffix("/files")
        } catch (_: Exception) {
            null
        }

        // Prefer the canonical /data/data/<pkg> form for root checks, since
        // /data/user/0/<pkg> is a symlink that `test -e` follows anyway but
        // is noisier in logs when the profile differs.
        val canonical = "/data/data/$packageName"
        return when {
            guess == null -> canonical
            guess.startsWith("/data/user/") -> canonical
            else -> guess
        }
    }

    private fun isDirectDirReadable(dataDir: String): Boolean {
        return try {
            val dir = File(dataDir)
            dir.exists() && dir.isDirectory && runCatching {
                dir.listFiles() != null
            }.getOrDefault(false)
        } catch (_: Exception) {
            false
        }
    }

    private fun isRootDirReadable(dataDir: String): Boolean {
        return try {
            if (!isRootAccessEnabledByConfig()) return false
            RootShell.isRootGranted() && RootShell.exists(dataDir)
        } catch (_: Exception) {
            false
        }
    }
}
