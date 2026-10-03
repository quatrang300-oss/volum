package com.quatrang.volumemixer.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import com.quatrang.volumemixer.BuildConfig
import com.quatrang.volumemixer.R
import com.quatrang.volumemixer.engine.AppAudio
import com.quatrang.volumemixer.engine.AppSettings
import com.quatrang.volumemixer.engine.MixerEngine
import com.quatrang.volumemixer.engine.ShizukuState
import com.quatrang.volumemixer.service.MixerService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity() {

    private enum class StatusAction { NONE, GET_SHIZUKU, OPEN_SHIZUKU, GRANT }

    private lateinit var settings: AppSettings
    private lateinit var adapter: AppVolumeAdapter

    private lateinit var statusCard: MaterialCardView
    private lateinit var statusTitle: TextView
    private lateinit var statusDesc: TextView
    private lateinit var statusAction: MaterialButton
    private lateinit var switchEnable: MaterialSwitch
    private lateinit var switchOverlay: MaterialSwitch
    private lateinit var listEmpty: TextView
    private lateinit var resetAll: MaterialButton
    private lateinit var adContainer: FrameLayout

    private var currentAction = StatusAction.NONE
    private var updatingUi = false
    private var pendingOverlayEnable = false

    private var adView: AdView? = null
    private var consentInformation: ConsentInformation? = null
    private val adsStarted = AtomicBoolean(false)

    private val binderReceived = Shizuku.OnBinderReceivedListener { runOnUiThread { onShizukuChanged() } }
    private val binderDead = Shizuku.OnBinderDeadListener { runOnUiThread { onShizukuChanged() } }
    private val permissionResult = Shizuku.OnRequestPermissionResultListener { requestCode, _ ->
        if (requestCode == ShizukuState.REQUEST_CODE) runOnUiThread { onShizukuChanged() }
    }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        settings = AppSettings(this)

        val root = findViewById<View>(R.id.root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }
        setSupportActionBar(findViewById<MaterialToolbar>(R.id.toolbar))

        statusCard = findViewById(R.id.status_card)
        statusTitle = findViewById(R.id.status_title)
        statusDesc = findViewById(R.id.status_desc)
        statusAction = findViewById(R.id.status_action)
        switchEnable = findViewById(R.id.switch_enable)
        switchOverlay = findViewById(R.id.switch_overlay)
        listEmpty = findViewById(R.id.list_empty)
        resetAll = findViewById(R.id.btn_reset_all)
        adContainer = findViewById(R.id.ad_container)

        adapter = AppVolumeAdapter(compact = false, listener = object : AppVolumeAdapter.Listener {
            override fun onVolumeChanged(app: AppAudio, volume: Int) =
                MixerEngine.setVolume(app.packageName, volume)

            override fun onToggleMute(app: AppAudio) = MixerEngine.toggleMute(app.packageName)
        })
        findViewById<RecyclerView>(R.id.app_list).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
            itemAnimator = null
            isNestedScrollingEnabled = false
        }

        statusAction.setOnClickListener { onStatusAction() }
        findViewById<MaterialButton>(R.id.status_help).setOnClickListener { showHelp() }
        switchEnable.setOnCheckedChangeListener { _, checked -> if (!updatingUi) onEnableChanged(checked) }
        switchOverlay.setOnCheckedChangeListener { _, checked -> if (!updatingUi) onOverlayChanged(checked) }
        resetAll.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.reset_all_title)
                .setMessage(R.string.reset_all_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.reset_all_confirm) { _, _ -> MixerEngine.resetAll() }
                .show()
        }

        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)
        Shizuku.addRequestPermissionResultListener(permissionResult)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                MixerEngine.state.collect { renderState(it) }
            }
        }

        maybeAskNotificationPermission()
        setupConsentAndAds()
    }

    override fun onResume() {
        super.onResume()
        adView?.resume()
        if (pendingOverlayEnable) {
            pendingOverlayEnable = false
            if (Settings.canDrawOverlays(this)) enableOverlay()
        }
        autoStart()
        refreshUi()
    }

    override fun onPause() {
        adView?.pause()
        super.onPause()
    }

    override fun onDestroy() {
        Shizuku.removeBinderReceivedListener(binderReceived)
        Shizuku.removeBinderDeadListener(binderDead)
        Shizuku.removeRequestPermissionResultListener(permissionResult)
        adView?.destroy()
        adView = null
        super.onDestroy()
    }

    // ------------------------------------------------------------------ menu

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.action_privacy)?.isVisible =
            consentInformation?.privacyOptionsRequirementStatus ==
                ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_help -> {
            showHelp(); true
        }
        R.id.action_privacy -> {
            UserMessagingPlatform.showPrivacyOptionsForm(this) { }
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    // ------------------------------------------------------------------ state

    private fun onShizukuChanged() {
        autoStart()
        MixerEngine.refreshNow()
        refreshUi()
    }

    private fun autoStart() {
        if (settings.enabled && ShizukuState.isReady() && !MixerService.isRunning) {
            MixerService.start(this)
        }
    }

    private fun onEnableChanged(checked: Boolean) {
        settings.enabled = checked
        if (checked) {
            if (ShizukuState.isReady()) MixerService.start(this)
            else Toast.makeText(this, R.string.toast_need_shizuku, Toast.LENGTH_LONG).show()
        } else {
            MixerService.stop(this)
        }
        refreshUi()
    }

    private fun onOverlayChanged(checked: Boolean) {
        if (!checked) {
            settings.overlayEnabled = false
            MixerService.command(this, MixerService.ACTION_HIDE_OVERLAY)
            return
        }
        if (Settings.canDrawOverlays(this)) {
            enableOverlay()
        } else {
            setSwitch(switchOverlay, false)
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.overlay_perm_title)
                .setMessage(R.string.overlay_perm_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.overlay_perm_open) { _, _ ->
                    pendingOverlayEnable = true
                    try {
                        startActivity(
                            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                        )
                    } catch (_: ActivityNotFoundException) {
                        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
                    }
                }
                .show()
        }
    }

    private fun enableOverlay() {
        settings.overlayEnabled = true
        if (MixerService.isRunning) {
            MixerService.command(this, MixerService.ACTION_SHOW_OVERLAY)
        } else if (settings.enabled && ShizukuState.isReady()) {
            MixerService.start(this, MixerService.ACTION_SHOW_OVERLAY)
        }
        refreshUi()
    }

    private fun setSwitch(sw: MaterialSwitch, value: Boolean) {
        updatingUi = true
        sw.isChecked = value
        updatingUi = false
    }

    private fun refreshUi() {
        setSwitch(switchEnable, settings.enabled)
        setSwitch(switchOverlay, settings.overlayEnabled && Settings.canDrawOverlays(this))
        renderState(MixerEngine.state.value)
    }

    private fun renderState(st: MixerEngine.State) {
        renderStatus(st)

        val apps = if (MixerService.isRunning || st.status != MixerEngine.Status.STOPPED) st.apps else emptyList()
        adapter.submitList(apps)

        val emptyMsg: Int? = when {
            apps.isNotEmpty() -> null
            !settings.enabled -> R.string.list_empty_disabled
            !ShizukuState.isReady() -> R.string.list_empty_shizuku
            else -> R.string.list_empty_running
        }
        listEmpty.visibility = if (emptyMsg == null) View.GONE else View.VISIBLE
        if (emptyMsg != null) listEmpty.setText(emptyMsg)

        resetAll.visibility =
            if (apps.any { it.muted || it.volume < 100 }) View.VISIBLE else View.GONE
    }

    private fun renderStatus(st: MixerEngine.State) {
        when (ShizukuState.status(this)) {
            ShizukuState.Status.NOT_INSTALLED -> setStatus(
                R.string.status_not_installed_title, getString(R.string.status_not_installed_desc),
                StatusAction.GET_SHIZUKU, warn = true
            )
            ShizukuState.Status.NOT_RUNNING -> setStatus(
                R.string.status_not_running_title, getString(R.string.status_not_running_desc),
                StatusAction.OPEN_SHIZUKU, warn = true
            )
            ShizukuState.Status.TOO_OLD -> setStatus(
                R.string.status_too_old_title, getString(R.string.status_too_old_desc),
                StatusAction.GET_SHIZUKU, warn = true
            )
            ShizukuState.Status.NO_PERMISSION -> setStatus(
                R.string.status_no_permission_title, getString(R.string.status_no_permission_desc),
                StatusAction.GRANT, warn = true
            )
            ShizukuState.Status.READY -> when {
                st.status == MixerEngine.Status.NOT_PRIVILEGED -> setStatus(
                    R.string.status_not_privileged_title, getString(R.string.status_not_privileged_desc),
                    StatusAction.NONE, warn = true
                )
                st.status == MixerEngine.Status.ERROR -> setStatus(
                    R.string.status_error_title, getString(R.string.status_error_desc, st.message ?: ""),
                    StatusAction.NONE, warn = true
                )
                !settings.enabled -> setStatus(
                    R.string.status_off_title, getString(R.string.status_off_desc),
                    StatusAction.NONE, warn = false
                )
                else -> setStatus(
                    R.string.status_ready_title, getString(R.string.status_ready_desc),
                    StatusAction.NONE, warn = false
                )
            }
        }
    }

    private fun setStatus(title: Int, desc: String, action: StatusAction, warn: Boolean) {
        statusTitle.setText(title)
        statusDesc.text = desc
        currentAction = action
        statusCard.setCardBackgroundColor(
            ContextCompat.getColor(this, if (warn) R.color.status_warn_bg else R.color.status_ok_bg)
        )
        val label = when (action) {
            StatusAction.GET_SHIZUKU -> R.string.action_get_shizuku
            StatusAction.OPEN_SHIZUKU -> R.string.action_open_shizuku
            StatusAction.GRANT -> R.string.action_grant
            StatusAction.NONE -> 0
        }
        if (label == 0) {
            statusAction.visibility = View.GONE
        } else {
            statusAction.visibility = View.VISIBLE
            statusAction.setText(label)
        }
    }

    private fun onStatusAction() {
        when (currentAction) {
            StatusAction.GET_SHIZUKU -> openStore(ShizukuState.SHIZUKU_PACKAGE)
            StatusAction.OPEN_SHIZUKU -> {
                val launch = packageManager.getLaunchIntentForPackage(ShizukuState.SHIZUKU_PACKAGE)
                if (launch != null) startActivity(launch) else openStore(ShizukuState.SHIZUKU_PACKAGE)
            }
            StatusAction.GRANT -> ShizukuState.requestPermission()
            StatusAction.NONE -> Unit
        }
    }

    private fun openStore(pkg: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")))
        } catch (_: ActivityNotFoundException) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$pkg")))
            } catch (_: ActivityNotFoundException) {
            }
        }
    }

    private fun showHelp() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.help_title)
            .setMessage(R.string.help_message)
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(R.string.action_get_shizuku) { _, _ -> openStore(ShizukuState.SHIZUKU_PACKAGE) }
            .show()
    }

    private fun maybeAskNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // ------------------------------------------------------------------ ads (banner only, main screen only)

    private fun setupConsentAndAds() {
        val info = UserMessagingPlatform.getConsentInformation(this)
        consentInformation = info
        val params = ConsentRequestParameters.Builder().build()
        info.requestConsentInfoUpdate(
            this,
            params,
            {
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(this) { _ ->
                    if (info.canRequestAds()) startAds()
                    invalidateOptionsMenu()
                }
            },
            { _ ->
                if (info.canRequestAds()) startAds()
            }
        )
        if (info.canRequestAds()) startAds()
    }

    private fun startAds() {
        if (!adsStarted.compareAndSet(false, true)) return
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                MobileAds.initialize(this@MainActivity) { }
            } catch (_: Throwable) {
            }
            withContext(Dispatchers.Main) { loadBanner() }
        }
    }

    private fun loadBanner() {
        if (isFinishing || isDestroyed) return
        adContainer.post {
            if (isFinishing || isDestroyed) return@post
            val widthPx = if (adContainer.width > 0) adContainer.width else resources.displayMetrics.widthPixels
            val adWidthDp = (widthPx / resources.displayMetrics.density).toInt()
            val view = AdView(this)
            view.adUnitId = BuildConfig.BANNER_AD_UNIT_ID
            view.setAdSize(AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(this, adWidthDp))
            view.adListener = object : AdListener() {
                override fun onAdLoaded() {
                    adContainer.visibility = View.VISIBLE
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    adContainer.visibility = View.GONE
                }
            }
            adContainer.removeAllViews()
            adContainer.addView(view)
            adView = view
            view.loadAd(AdRequest.Builder().build())
        }
    }
}
