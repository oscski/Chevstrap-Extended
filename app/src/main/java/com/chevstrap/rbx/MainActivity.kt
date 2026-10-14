package com.chevstrap.rbx

import android.content.res.Configuration
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import chevstrap.preference.PrefsManager
import com.chevstrap.rbx.appDirectories.RobloxAccessMode
import com.chevstrap.rbx.appDirectories.RobloxClientData
import com.chevstrap.rbx.extensions.ResourceManagerEx
import com.chevstrap.rbx.ui.Frontend
import com.chevstrap.rbx.ui.views.main.MainMenuScreen
import com.chevstrap.rbx.ui.viewModels.main.MainMenuScreenViewModel
import com.chevstrap.rbx.utility.ShizukuShell
import rikka.shizuku.Shizuku

class MainActivity : AppCompatActivity() {

    private val viewModel =
        MainMenuScreenViewModel()

    private lateinit var ui: MainMenuScreen

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {
        super.onCreate(savedInstanceState)

        try {
            setContentView(R.layout.main)
            App.savedMainActivity = this

            ui = MainMenuScreen(
                this,
                viewModel
            )

            initialize()
            initializeLogic()

        } catch (e: Exception) {
            Toast.makeText(
                this,
                e.message,
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onDestroy() {
        removeSmallModeListener()

        super.onDestroy()
    }

    private fun initialize() {
        ui.initialize()
    }

    override fun onConfigurationChanged(
        newConfig: Configuration
    ) {
        super.onConfigurationChanged(newConfig)

        App.isSmallMode =
            newConfig.screenWidthDp < 600
    }

    private val smallModeListener: (Boolean) -> Unit =
        { isSmallMode ->
            ui.updateLayoutOrientation(
                isSmallMode
            )
        }

    private fun setupSmallModeListener() {
        App.addSmallModeListener(
            smallModeListener
        )
    }

    private fun removeSmallModeListener() {
        App.removeSmallModeListener(
            smallModeListener
        )
    }

    private fun initializeLogic() {
        App.isSmallMode =
            ui.isSmallMode()

        ui.updateLayoutOrientation(
            App.isSmallMode
        )

        PrefsManager.initialize(applicationContext)
        setupSmallModeListener()
        checkFirst()
    }

    private fun checkFirst() {
        val robloxData =
            RobloxClientData()

        // Fast path: direct access (Virtual Space) is synchronous and cheap.
        val directSuccess = try {
            robloxData.isDirectAccessAvailable()
        } catch (_: Exception) {
            false
        }

        if (directSuccess) {
            App.logger.writeLine(
                "MainActivity::checkFirst",
                "Roblox directory accessible directly (${RobloxAccessMode.DIRECT})"
            )
            return
        }

        // Slow path: root check spawns `su`, so keep it off the main thread
        // (it also triggers the root permission prompt on first use).
        // Shizuku is probed here too (binder ping + permission state).
        Thread {
            val mode = try {
                robloxData.getAccessMode()
            } catch (e: Exception) {
                App.logger.writeException("MainActivity::checkFirst", e)
                RobloxAccessMode.NONE
            }

            App.logger.writeLine(
                "MainActivity::checkFirst",
                "Roblox access mode: $mode"
            )

            if (mode != RobloxAccessMode.NONE) return@Thread

            // Shizuku escalation: binder alive but permission not yet
            // granted (triggers the Shizuku permission prompt on first
            // use, mirroring the root flow above).
            val shouldAskShizuku = try {
                App.config.data.useShizukuAccess &&
                        ShizukuShell.isBinderAlive() &&
                        !ShizukuShell.isPermissionGranted()
            } catch (_: Exception) {
                false
            }

            if (shouldAskShizuku) {
                runOnUiThread {
                    requestShizukuPermissionAndRecheck(robloxData)
                }
                return@Thread
            }

            runOnUiThread {
                showAccessBlockedDialog()
            }
        }.start()
    }

    private fun requestShizukuPermissionAndRecheck(
        robloxData: RobloxClientData
    ) {
        if (isFinishing || isDestroyed) return

        val listener = object : Shizuku.OnRequestPermissionResultListener {
            override fun onRequestPermissionResult(
                requestCode: Int,
                grantResult: Int
            ) {
                ShizukuShell.removePermissionListener(this)

                App.logger.writeLine(
                    "MainActivity::shizukuPermission",
                    "requestCode=$requestCode grantResult=$grantResult"
                )

                Thread {
                    val mode = try {
                        robloxData.getAccessMode()
                    } catch (e: Exception) {
                        App.logger.writeException(
                            "MainActivity::shizukuPermission",
                            e
                        )
                        RobloxAccessMode.NONE
                    }

                    App.logger.writeLine(
                        "MainActivity::shizukuPermission",
                        "Roblox access mode after prompt: $mode"
                    )

                    if (mode != RobloxAccessMode.NONE) return@Thread

                    runOnUiThread {
                        showAccessBlockedDialog()
                    }
                }.start()
            }
        }

        ShizukuShell.addPermissionListener(listener)
        ShizukuShell.requestPermission(ShizukuShell.PERMISSION_REQUEST_CODE)
    }

    private fun showAccessBlockedDialog() {
        if (isFinishing || isDestroyed) return
        Frontend.showMessageBoxWithRunnable(
            this,
            ResourceManagerEx.getStringOrEmpty(
                App.appContext,
                R.string.dialog_access_to_roblox_directories_is_blocked
            ) +
                    " " + "https://github.com/oscski/Chevstrap-Extended/wiki/Access-to-Roblox-directories-is-blocked",
            false,
            { finish() },
            null
        )
    }
}
