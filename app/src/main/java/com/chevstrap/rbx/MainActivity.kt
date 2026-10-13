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

            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
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
        }.start()
    }
}
