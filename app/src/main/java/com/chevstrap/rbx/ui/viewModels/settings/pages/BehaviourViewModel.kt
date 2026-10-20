package com.chevstrap.rbx.ui.viewModels.settings.pages

import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import com.chevstrap.rbx.App
import com.chevstrap.rbx.ConfigManager
import com.chevstrap.rbx.enums.LaunchMode
import com.chevstrap.rbx.utility.RootShell
import com.chevstrap.rbx.utility.ShizukuShell
import rikka.shizuku.Shizuku

class BehaviourViewModel {
    private val accessStateListeners = mutableListOf<() -> Unit>()

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var rootAvailable: Boolean = true

    @Volatile
    private var shizukuAvailable: Boolean = true

    fun addAccessStateListener(listener: () -> Unit) {
        accessStateListeners += listener
    }

    fun clearAccessStateListeners() {
        accessStateListeners.clear()
    }

    private fun notifyAccessStateChanged() {
        val notify = { accessStateListeners.toList().forEach { it() } }

        if (Looper.myLooper() == Looper.getMainLooper()) {
            notify()
        } else {
            mainHandler.post(notify)
        }
    }

    var bringToLatestUpdate: Boolean
        get() = App.config.data.manualUpdateBootstrapper
        set(value) {
            App.config.data.manualUpdateBootstrapper = value
        }

    var useRootAccess: Boolean
        get() = App.config.data.useRootAccess && rootAvailable
        set(value) {
            if (!value) {
                App.config.data.useRootAccess = false
                return
            }

            App.config.data.useRootAccess = true

            Thread {
                rootAvailable = try {
                    RootShell.isRootGranted()
                } catch (_: Exception) {
                    false
                }

                notifyAccessStateChanged()
            }.start()
        }

    var useShizukuAccess: Boolean
        get() = App.config.data.useShizukuAccess && shizukuAvailable
        set(value) {
            if (!value) {
                App.config.data.useShizukuAccess = false
                return
            }

            App.config.data.useShizukuAccess = true

            if (ShizukuShell.isPermissionGranted()) {
                shizukuAvailable = true
                notifyAccessStateChanged()
                return
            }

            if (!ShizukuShell.isBinderAlive()) {
                shizukuAvailable = false
                notifyAccessStateChanged()
                return
            }

            val listener = object : Shizuku.OnRequestPermissionResultListener {
                override fun onRequestPermissionResult(
                    requestCode: Int,
                    grantResult: Int
                ) {
                    ShizukuShell.removePermissionListener(this)

                    shizukuAvailable =
                        grantResult == PackageManager.PERMISSION_GRANTED

                    notifyAccessStateChanged()
                }
            }

            ShizukuShell.addPermissionListener(listener)
            ShizukuShell.requestPermission(ShizukuShell.PERMISSION_REQUEST_CODE)
        }

    /**
     * Refreshes the cached availability so the switches reflect whether
     * an opted-in mode is currently usable. The config is never cleared:
     * an unavailable mode just shows as off until it comes back. Probes
     * run off the main thread.
     */
    fun refreshAccessAvailability() {
        Thread {
            rootAvailable = if (App.config.data.useRootAccess) {
                try {
                    RootShell.isRootGranted()
                } catch (_: Exception) {
                    false
                }
            } else {
                false
            }

            shizukuAvailable = if (App.config.data.useShizukuAccess) {
                try {
                    ShizukuShell.isBinderAlive() &&
                            ShizukuShell.isPermissionGranted()
                } catch (_: Exception) {
                    false
                }
            } else {
                false
            }

            notifyAccessStateChanged()
        }.start()
    }

    var preferredRobloxApp: String
        get() {
            val value = App.config.data.preferredRobloxApp

            for (entry in ConfigManager.RobloxAppTypes.entries) {
                if (entry.value == value) {
                    return entry.key.name
                }
            }

            return ConfigManager.RobloxAppTypes.keys
                .firstOrNull()
                ?.name
                ?: LaunchMode.Global.name
        }

        set(modeName) {
            try {
                val mode = LaunchMode.valueOf(modeName)
                App.config.data.preferredRobloxApp =
                    ConfigManager.RobloxAppTypes[mode] ?: "global"
            } catch (_: IllegalArgumentException) {
                App.config.data.preferredRobloxApp = "global"
            }
        }
}
