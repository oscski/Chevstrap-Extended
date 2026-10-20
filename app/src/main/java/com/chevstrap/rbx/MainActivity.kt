package com.chevstrap.rbx

import android.content.res.Configuration
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import chevstrap.preference.PrefsManager
import com.chevstrap.rbx.appDirectories.RobloxAccessMode
import com.chevstrap.rbx.appDirectories.RobloxClientData
import com.chevstrap.rbx.ui.Frontend
import com.chevstrap.rbx.ui.views.main.MainMenuScreen
import com.chevstrap.rbx.ui.viewModels.main.MainMenuScreenViewModel

class MainActivity : AppCompatActivity() {

    private val viewModel =
        MainMenuScreenViewModel()

    private lateinit var ui: MainMenuScreen
    private var lastAccessCheckAt = 0L

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

    override fun onResume() {
        super.onResume()

        val now = System.currentTimeMillis()
        if (now - lastAccessCheckAt < 1500L) return

        checkFirst()
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
        lastAccessCheckAt = System.currentTimeMillis()

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

        // Slow path: the mode probes spawn `su`/Shizuku calls, so keep
        // them off the main thread. Whenever nothing is reachable, show
        // the access setup screen so the user can opt in (or skip it
        // and revisit it later from Launcher settings).
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
                showAccessSetup()
            }
        }.start()
    }

    private fun showAccessSetup() {
        if (isFinishing || isDestroyed) return

        // The dialog is restored across configuration changes, so don't
        // stack a second copy when checkFirst() runs again.
        if (supportFragmentManager.findFragmentByTag(Frontend.ACCESS_SETUP_TAG) != null) {
            return
        }

        Frontend.showAccessSetup(this)
    }
}
