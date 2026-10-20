package com.chevstrap.rbx.ui.views.customDialogs

import android.app.Dialog
import android.content.DialogInterface
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.graphics.drawable.toDrawable
import androidx.core.graphics.toColorInt
import androidx.fragment.app.DialogFragment
import com.chevstrap.rbx.App
import com.chevstrap.rbx.R
import com.chevstrap.rbx.appDirectories.RobloxAccessMode
import com.chevstrap.rbx.appDirectories.RobloxClientData
import com.chevstrap.rbx.extensions.ResourceManagerEx
import com.chevstrap.rbx.ui.components.ComponentUtils
import com.chevstrap.rbx.ui.components.holders.SmallButtonResult
import com.chevstrap.rbx.ui.viewModels.GlobalViewModel
import com.chevstrap.rbx.utility.RootShell
import com.chevstrap.rbx.utility.ShizukuShell
import kotlin.math.min
import rikka.shizuku.Shizuku

/**
 * Roblox access setup.
 *
 * Shown automatically whenever the app starts with no usable access, and
 * on demand from Launcher settings. It explains the three ways Chevstrap
 * can reach Roblox's data — direct, root and Shizuku — and lets the 
 * user grant whichever is available.
 */
class AccessSetupFragment : DialogFragment() {

    interface AccessSetupListener {
        fun onDismissed()
    }

    private var listener: AccessSetupListener? = null

    private var sectionsContainer: LinearLayout? = null
    private var actionsContainer: LinearLayout? = null
    private var statusView: TextView? = null
    private var continueButton: SmallButtonResult? = null

    private val robloxData = RobloxClientData()

    private var rechecking = false

    fun setAccessSetupListener(listener: AccessSetupListener) {
        this.listener = listener
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)

        dialog.window?.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
        dialog.setCanceledOnTouchOutside(false)
        dialog.setCancelable(true)

        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(
            R.layout.access_setup_fragment,
            container,
            false
        )
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initialize(view)
    }

    override fun onStart() {
        super.onStart()

        val window = dialog?.window ?: return

        val density = resources.displayMetrics.density
        val screenWidth = resources.displayMetrics.widthPixels

        val width = min(screenWidth, (560f * density).toInt())

        val params = window.attributes
        params.gravity = Gravity.CENTER
        params.width = width
        params.height = WindowManager.LayoutParams.WRAP_CONTENT
        window.attributes = params
        window.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT)
    }

    override fun onResume() {
        super.onResume()
        runRecheck()
    }

    override fun onDestroyView() {
        sectionsContainer = null
        actionsContainer = null
        statusView = null
        continueButton = null

        super.onDestroyView()
    }

    override fun onDismiss(dialog: DialogInterface) {
        listener?.onDismissed()

        super.onDismiss(dialog)
    }

    private fun initialize(view: View) {
        val container = view.findViewById<LinearLayout>(R.id.linear_access_setup)

        sectionsContainer = view.findViewById(R.id.linear_access_sections)
        actionsContainer = view.findViewById(R.id.linear_access_actions)
        statusView = view.findViewById(R.id.textview_access_setup_status)

        val titleView = view.findViewById<TextView>(R.id.textview_access_setup_title)
        val messageView = view.findViewById<TextView>(R.id.textview_access_setup_message)

        val primaryColor = if (isDark()) "#FFFFFF".toColorInt() else "#000000".toColorInt()
        val secondaryColor = if (isDark()) "#ABABAB".toColorInt() else "#545454".toColorInt()

        titleView.text = ResourceManagerEx.getStringOrEmpty(
            requireContext(),
            R.string.access_setup_title
        )
        titleView.setTextColor(primaryColor)

        messageView.text = ResourceManagerEx.getStringOrEmpty(
            requireContext(),
            R.string.access_setup_message
        )
        messageView.setTextColor(secondaryColor)

        statusView?.setTextColor(secondaryColor)

        styleBackground(container)

        addModeRow(
            R.string.access_setup_direct_title,
            R.string.access_setup_direct_description,
            R.string.access_setup_direct_open_wiki
        ) {
            GlobalViewModel.openWebpage(
                App.appContext,
                App.PROJECT_WIKI +
                        "/Access-to-Roblox-directories-is-blocked"
            )
        }

        addModeRow(
            R.string.access_setup_root_title,
            R.string.access_setup_root_description,
            R.string.access_setup_root_grant
        ) {
            grantRoot()
        }

        addModeRow(
            R.string.access_setup_shizuku_title,
            R.string.access_setup_shizuku_description,
            R.string.access_setup_shizuku_grant
        ) {
            grantShizuku()
        }

        actionsContainer?.let { actions ->
            addActionButton(
                actions,
                ResourceManagerEx.getStringOrEmpty(
                    requireContext(),
                    R.string.access_setup_recheck
                )
            ) {
                runRecheck()
            }

            continueButton = addActionButton(
                actions,
                ResourceManagerEx.getStringOrEmpty(
                    requireContext(),
                    R.string.access_setup_skip
                )
            ) {
                finishSetup()
            }
        }
    }

    private fun addModeRow(
        titleRes: Int,
        descriptionRes: Int,
        buttonRes: Int,
        onAction: () -> Unit
    ) {
        val context = requireContext()

        val row = LinearLayout(context)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, dp(12), 0, dp(12))

        val info = LinearLayout(context)
        info.orientation = LinearLayout.VERTICAL
        info.layoutParams = LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            1f
        )

        val title = TextView(context)
        title.text = ResourceManagerEx.getStringOrEmpty(context, titleRes)
        title.textSize = 14f
        title.setTextColor(
            if (isDark()) "#FFFFFF".toColorInt() else "#000000".toColorInt()
        )
        info.addView(title)

        val description = TextView(context)
        description.text = ResourceManagerEx.getStringOrEmpty(
            context,
            descriptionRes
        )
        description.textSize = 12f
        description.setTextColor(
            if (isDark()) "#ABABAB".toColorInt() else "#545454".toColorInt()
        )
        info.addView(description)

        row.addView(info)

        val button = ComponentUtils.addSmallButton(
            context,
            ResourceManagerEx.getStringOrEmpty(context, buttonRes),
            null
        )
        button.buttonOne?.setOnClickListener { onAction() }

        button.buttonView?.let { buttonView ->
            if (buttonView.parent == null) {
                row.addView(buttonView)
            }
        }

        sectionsContainer?.addView(row)
    }

    private fun addActionButton(
        parent: LinearLayout,
        name: String,
        onClick: () -> Unit
    ): SmallButtonResult {
        val button = ComponentUtils.addSmallButton(
            requireContext(),
            name,
            parent
        )

        button.buttonOne?.setOnClickListener { onClick() }

        button.buttonView?.let { buttonView ->
            if (buttonView.parent == null) {
                parent.addView(buttonView)
            }
        }

        return button
    }

    private fun runRecheck() {
        if (rechecking) return
        rechecking = true

        statusView?.text = ResourceManagerEx.getStringOrEmpty(
            requireContext(),
            R.string.access_setup_status_checking
        )

        Thread {
            val mode = try {
                robloxData.getAccessMode()
            } catch (e: Exception) {
                App.logger.writeException(
                    "AccessSetupFragment::runRecheck",
                    e
                )
                RobloxAccessMode.NONE
            }

            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread

                rechecking = false
                showStatus(mode)
            }
        }.start()
    }

    private fun showStatus(mode: RobloxAccessMode) {
        statusView?.text = ResourceManagerEx.getStringOrEmpty(
            requireContext(),
            when (mode) {
                RobloxAccessMode.DIRECT ->
                    R.string.access_setup_status_direct
                RobloxAccessMode.ROOT ->
                    R.string.access_setup_status_root
                RobloxAccessMode.SHIZUKU ->
                    R.string.access_setup_status_shizuku
                RobloxAccessMode.NONE ->
                    R.string.access_setup_status_none
            }
        )

        val available = mode != RobloxAccessMode.NONE

        continueButton?.nameTextView?.text =
            ResourceManagerEx.getStringOrEmpty(
                requireContext(),
                if (available) {
                    R.string.access_setup_continue
                } else {
                    R.string.access_setup_skip
                }
            )
    }

    private fun grantRoot() {
        statusView?.text = ResourceManagerEx.getStringOrEmpty(
            requireContext(),
            R.string.access_setup_status_checking
        )

        Thread {
            val granted = try {
                RootShell.requestRootPermission()
            } catch (_: Exception) {
                false
            }

            if (granted) {
                App.config.data.useRootAccess = true
                App.config.save()
            }

            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread

                rechecking = false
                runRecheck()
            }
        }.start()
    }

    private fun grantShizuku() {
        if (ShizukuShell.isPermissionGranted()) {
            enableShizuku()
            return
        }

        if (!ShizukuShell.isBinderAlive()) {
            Toast.makeText(
                requireContext(),
                ResourceManagerEx.getStringOrEmpty(
                    requireContext(),
                    R.string.access_setup_shizuku_unavailable
                ),
                Toast.LENGTH_LONG
            ).show()
            return
        }

        statusView?.text = ResourceManagerEx.getStringOrEmpty(
            requireContext(),
            R.string.access_setup_status_checking
        )

        val permissionListener = object : Shizuku.OnRequestPermissionResultListener {
            override fun onRequestPermissionResult(
                requestCode: Int,
                grantResult: Int
            ) {
                ShizukuShell.removePermissionListener(this)

                val granted =
                    grantResult == PackageManager.PERMISSION_GRANTED

                if (granted) {
                    App.config.data.useShizukuAccess = true
                    App.config.save()
                }

                activity?.runOnUiThread {
                    if (!isAdded) return@runOnUiThread

                    rechecking = false
                    runRecheck()
                }
            }
        }

        ShizukuShell.addPermissionListener(permissionListener)
        ShizukuShell.requestPermission(ShizukuShell.PERMISSION_REQUEST_CODE)
    }

    /** Shizuku is already permitted: just opt in and save. */
    private fun enableShizuku() {
        App.config.data.useShizukuAccess = true
        App.config.save()

        rechecking = false
        runRecheck()
    }

    private fun finishSetup() {
        dismissAllowingStateLoss()
    }

    private fun styleBackground(container: LinearLayout?) {
        container ?: return

        val drawable = GradientDrawable()
        drawable.cornerRadius = 30f

        if (isDark()) {
            drawable.setColor("#060606".toColorInt())
            drawable.setStroke(5, "#151515".toColorInt())
        } else {
            drawable.setColor("#EFEFEF".toColorInt())
            drawable.setStroke(5, "#EAEAEA".toColorInt())
        }

        container.background = drawable
    }

    private fun isDark(): Boolean {
        return App.config.data.appThemeInApp != "light"
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }
}
