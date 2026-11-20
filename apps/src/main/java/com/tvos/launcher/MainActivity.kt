package com.tvos.launcher

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.*
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.text.TextUtils
import android.view.*
import android.widget.*
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private val PREFS = "tvos_launcher_prefs"
    private val io = Executors.newFixedThreadPool(3)
    private val ui = Handler(Looper.getMainLooper())
    private val REQ_PICK_WALLPAPER = 101
    private val REQ_PERMISSIONS = 102
    private val REQ_ENABLE_BT = 103

    // Fixed 6-tile Apple TV dock
    private val dockColumns = 6

    // User preferences (persisted)
    private var themeMode = "LIQUID"         // "DARK", "LIGHT", "LIQUID"
    private var use24HourClock = false

    // Cached date formatters & state (event-driven via ACTION_TIME_TICK — zero polling!)
    private val fmt12h = SimpleDateFormat("h:mm a", Locale.getDefault())
    private val fmt24h = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val fmtStandby12 = SimpleDateFormat("h:mm", Locale.getDefault())
    private val fmtStandbyDate = SimpleDateFormat("EEEE, MMM d", Locale.getDefault())
    private val reusableDate = Date()
    private var lastTimeText = ""
    private var lastStandbyClock = ""
    private var lastStandbyDate = ""

    // StandBy OLED/QLED pixel-shift drift coordinates (in dp)
    private val standbyDriftOffsetsDp = arrayOf(
        0 to 0, 10 to -6, 14 to 4, 8 to 11, -2 to 13, -11 to 8,
        -14 to -2, -9 to -11, 2 to -13, 12 to -9, 6 to 8, -8 to 5
    )
    private var standbyDriftStep = 0

    // Focus tracking for clean TV remote return
    private var lastFocusedHomePkg: String? = null
    private var hasSetInitialFocus = false

    // Broadcast receiver registration flags
    private var systemReceiversRegistered = false
    private var btReceiverRegistered = false

    // Root & Home Layers
    private lateinit var rootFrame: FrameLayout
    private lateinit var wallpaperView: ImageView
    private lateinit var scrollDimOverlay: View
    private lateinit var homeRecycler: RecyclerView
    private lateinit var homeLayoutManager: GridLayoutManager
    private lateinit var homeAdapter: AppleTvHomeAdapter
    private lateinit var topBar: RelativeLayout
    private lateinit var weatherPillBtn: LinearLayout
    private lateinit var weatherPillIcon: TextView
    private lateinit var weatherPillText: TextView
    private lateinit var wpCircleBtn: FrameLayout
    private lateinit var rightCapsule: LinearLayout
    private lateinit var clockText: TextView

    // Weather Panel Overlay
    private lateinit var weatherOverlay: FrameLayout
    private lateinit var weatherBackdrop: ImageView
    private lateinit var weatherContentHost: LinearLayout

    // Control Center Overlay
    private lateinit var controlOverlay: FrameLayout
    private lateinit var ccBackdrop: ImageView
    private lateinit var ccClockText: TextView
    private lateinit var ccSettingsHeaderBtn: FrameLayout
    private lateinit var ccCloseHeaderBtn: FrameLayout
    private lateinit var ccMainPage: LinearLayout
    private lateinit var ccWifiPage: LinearLayout
    private lateinit var ccBtPage: LinearLayout
    private lateinit var ccWifiBackBtn: TextView
    private lateinit var ccBtBackBtn: TextView
    private lateinit var standbyTile: LinearLayout
    private lateinit var wifiTileBox: LinearLayout
    private lateinit var wifiSubText: TextView
    private lateinit var btTileBox: LinearLayout
    private lateinit var btSubText: TextView
    private lateinit var volumeSlider: LiquidVolumeSeekBar
    private lateinit var volumePctText: TextView

    // In-App Wi-Fi & Bluetooth Manager containers (shared in Control Center & Settings)
    private lateinit var wifiMenuToggleBtn: TextView
    private lateinit var wifiMenuStatusText: TextView
    private lateinit var wifiListContainer: LinearLayout
    private lateinit var btMenuToggleBtn: TextView
    private lateinit var btMenuScanBtn: TextView
    private lateinit var btListContainer: LinearLayout
    private val discoveredBtDevices = LinkedHashMap<String, BtDev>()

    // Split-Screen Native tvOS Settings Overlay
    private lateinit var settingsOverlay: FrameLayout
    private lateinit var settingsCard: LinearLayout
    private lateinit var settingsSidebar: LinearLayout
    private lateinit var settingsDetailHost: LinearLayout
    private var activeSettingsTab = 0

    // Modals: Wi-Fi Password, App Long-Press Menu, StandBy Clock
    private lateinit var wifiPassOverlay: FrameLayout
    private lateinit var wifiPassTitle: TextView
    private lateinit var wifiPassInput: EditText
    private var pendingWifiNet: WifiNet? = null

    private lateinit var appMenuOverlay: FrameLayout
    private lateinit var appMenuCard: LinearLayout
    private lateinit var standbyOverlay: FrameLayout
    private lateinit var standbyBox: LinearLayout
    private lateinit var standbyClockText: TextView
    private lateinit var standbyDateText: TextView

    // Liquid Glass HUD Pill (replaces all stock Android Toast notifications)
    private lateinit var hudPill: LinearLayout
    private lateinit var hudIcon: VectorIconView
    private lateinit var hudText: TextView
    private val hideHudRunnable = Runnable { hideGlassHud() }

    private var wallpaperServer: WallpaperServer? = null
    private var currentWallpaper: Bitmap? = null
    private var currentBlurred: Bitmap? = null
    private var installedApps: List<AppItem> = emptyList()

    // Event-driven clock, volume & StandBy drift receiver (zero background polling)
    private val systemEventReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_TIME_TICK,
                Intent.ACTION_TIME_CHANGED,
                Intent.ACTION_TIMEZONE_CHANGED -> {
                    updateClockDisplay()
                    if (standbyOverlay.visibility == View.VISIBLE) {
                        advanceStandbyBurnInDrift()
                    }
                }
                "android.media.VOLUME_CHANGED_ACTION" -> {
                    if (controlOverlay.visibility == View.VISIBLE) {
                        val vol = SystemMonitor.getVolumePercent(this@MainActivity)
                        volumeSlider.progress = vol
                        volumePctText.text = "$vol%"
                    }
                }
            }
        }
    }

    // Automatic app install/uninstall sync receiver
    private val packageChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            TvIconComposer.clearCache()
            loadAppsAsync()
        }
    }

    private val btDiscoveryReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        @Suppress("DEPRECATION")
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val dev = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
                    val addr = dev.address ?: return
                    val name = try { dev.name?.trim().orEmpty() } catch (_: Exception) { "" }
                    if (name.isEmpty()) return
                    discoveredBtDevices[addr] = BtDev(
                        name = name,
                        address = addr,
                        connected = SystemMonitor.isDeviceConnected(dev),
                        bonded = dev.bondState == BluetoothDevice.BOND_BONDED,
                        device = dev
                    )
                    if (ccBtPage.visibility == View.VISIBLE) renderBluetoothList(btListContainer, btMenuToggleBtn)
                    if (settingsOverlay.visibility == View.VISIBLE && activeSettingsTab == 4) renderSettingsDetail()
                }
                BluetoothDevice.ACTION_BOND_STATE_CHANGED,
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    if (ccBtPage.visibility == View.VISIBLE) renderBluetoothList(btListContainer, btMenuToggleBtn)
                    if (settingsOverlay.visibility == View.VISIBLE && activeSettingsTab == 4) renderSettingsDetail()
                    refreshLiveConnectivity()
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    if (::btMenuScanBtn.isInitialized) btMenuScanBtn.text = "Scan"
                }
            }
        }
    }

    // Safe unsigned 32-bit ARGB helper
    private fun argb(hex: Long): Int = hex.toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Full edge-to-edge display on notched Android phones & 4K TVs
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        loadUserPrefs()
        requestRuntimePermissions()

        rootFrame = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#080C15"))
        }
        setContentView(rootFrame)
        hideSystemBars()

        // Layer 0: 4K Wallpaper + Atmospheric Scrim
        buildWallpaperLayer()
        // Layer 1 & 2: Scrollable Apple TV Dock/Grid + Elevated Liquid Glass Top Bar
        buildHomeUi()
        // Layer 3: Full Frosted Hourly & 5-Day Weather Panel
        buildWeatherOverlay()
        // Layer 4: Authentic tvOS Liquid Glass Control Center + Wi-Fi & BT Managers
        buildControlCenterOverlay()
        // Layer 5: Full Native Split-Screen tvOS Settings App
        buildSettingsOverlay()
        // Layer 6: Modals (Wi-Fi Password, App Long-Press Actions, StandBy Clock)
        buildWifiPasswordModal()
        buildAppMenuModal()
        buildStandbyOverlay()
        // Layer 7: Top-Center Liquid Glass HUD Pill (replaces Android Toasts)
        buildGlassHud()

        syncLayerFocusability()
        updateClockDisplay()
        loadWallpaper()
        loadAppsAsync()
        refreshWeatherAsync()
    }

    override fun onStart() {
        super.onStart()
        registerSystemReceivers()
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        updateClockDisplay()
        refreshLiveConnectivity()
    }

    override fun onPause() {
        super.onPause()
        wallpaperServer?.stop()
        stopBluetoothReceiver()
    }

    override fun onStop() {
        super.onStop()
        unregisterSystemReceivers()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_UI_HIDDEN) {
            wallpaperServer?.stop()
        }
        TvMemoryManager.handleTrimMemory(level)
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        unregisterSystemReceivers()
        stopBluetoothReceiver()
        wallpaperServer?.stop()
        io.shutdownNow()
        super.onDestroy()
    }

    private fun registerSystemReceivers() {
        if (systemReceiversRegistered) return
        try {
            val timeFilter = IntentFilter().apply {
                addAction(Intent.ACTION_TIME_TICK)
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
                addAction("android.media.VOLUME_CHANGED_ACTION")
            }
            ContextCompat.registerReceiver(this, systemEventReceiver, timeFilter, ContextCompat.RECEIVER_EXPORTED)

            val pkgFilter = IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addDataScheme("package")
            }
            ContextCompat.registerReceiver(this, packageChangeReceiver, pkgFilter, ContextCompat.RECEIVER_EXPORTED)
            systemReceiversRegistered = true
        } catch (_: Exception) {}
    }

    private fun unregisterSystemReceivers() {
        if (!systemReceiversRegistered) return
        try { unregisterReceiver(systemEventReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(packageChangeReceiver) } catch (_: Exception) {}
        systemReceiversRegistered = false
    }

    private fun loadUserPrefs() {
        val p = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        themeMode = p.getString("theme_mode", "LIQUID") ?: "LIQUID"
        use24HourClock = p.getBoolean("use_24h_clock", false)
    }

    private fun savePref(block: android.content.SharedPreferences.Editor.() -> Unit) {
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply(block).apply()
    }

    private fun hideSystemBars() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        )
    }

    private fun requestRuntimePermissions() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.BLUETOOTH_SCAN)
            }
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), REQ_PERMISSIONS)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshLiveConnectivity()
    }

    // =========================================================================
    // LIQUID GLASS HUD PILL (ZERO STOCK ANDROID TOASTS)
    // =========================================================================
    private fun buildGlassHud() {
        hudPill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(11), dp(20), dp(11))
            background = liquidGlass(tintColor = argb(0xCC101828), isPill = true)
            elevation = dp(40).toFloat()
            visibility = View.GONE
            alpha = 0f
            translationY = -dp(18).toFloat()
            isFocusable = false
            isClickable = false
        }
        hudIcon = VectorIconView(this, Glyph.SUN, Color.WHITE)
        hudPill.addView(hudIcon, LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(10) })

        hudText = TextView(this).apply {
            text = ""
            setTextColor(Color.WHITE)
            textSize = 14.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        hudPill.addView(hudText)

        rootFrame.addView(
            hudPill,
            FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
                topMargin = dp(28)
            }
        )
    }

    private fun showGlassHud(message: String, glyph: Glyph? = null) {
        if (isFinishing || isDestroyed) return
        ui.removeCallbacks(hideHudRunnable)
        hudText.text = message
        if (glyph != null) {
            hudIcon.setGlyph(glyph)
            hudIcon.visibility = View.VISIBLE
        } else {
            hudIcon.visibility = View.GONE
        }
        hudPill.visibility = View.VISIBLE
        hudPill.animate().cancel()
        hudPill.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(180L)
            .start()
        ui.postDelayed(hideHudRunnable, 2100L)
    }

    private fun hideGlassHud() {
        if (isFinishing || isDestroyed) return
        hudPill.animate().cancel()
        hudPill.animate()
            .alpha(0f)
            .translationY(-dp(18).toFloat())
            .setDuration(160L)
            .withEndAction { hudPill.visibility = View.GONE }
            .start()
    }

    // =========================================================================
    // LAYER FOCUS ISOLATION & FOCUS PRESERVATION ACROSS DYNAMIC REBUILDS
    // =========================================================================
    private fun collectFocusableDescendants(root: ViewGroup, out: MutableList<View>) {
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i)
            if (child.visibility != View.VISIBLE) continue
            if (child.isFocusable) {
                out.add(child)
            } else if (child is ViewGroup) {
                collectFocusableDescendants(child, out)
            }
        }
    }

    private fun captureFocusableIndex(container: ViewGroup): Int {
        val focused = currentFocus ?: return -1
        if (!container.hasFocus()) return -1
        val list = mutableListOf<View>()
        collectFocusableDescendants(container, list)
        return list.indexOf(focused)
    }

    private fun restoreFocusableIndex(container: ViewGroup, savedIndex: Int, fallback: View? = null) {
        if (savedIndex < 0) return
        container.post {
            if (isFinishing || isDestroyed) return@post
            val list = mutableListOf<View>()
            collectFocusableDescendants(container, list)
            if (list.isNotEmpty()) {
                list[savedIndex.coerceIn(0, list.lastIndex)].requestFocus()
            } else {
                fallback?.requestFocus()
            }
        }
    }

    private fun syncLayerFocusability() {
        val passVisible = wifiPassOverlay.visibility == View.VISIBLE
        val appMenuVisible = appMenuOverlay.visibility == View.VISIBLE
        val standbyVisible = standbyOverlay.visibility == View.VISIBLE
        val settingsVisible = settingsOverlay.visibility == View.VISIBLE
        val controlVisible = controlOverlay.visibility == View.VISIBLE
        val weatherVisible = weatherOverlay.visibility == View.VISIBLE

        val anyOverlay = passVisible || appMenuVisible || standbyVisible || settingsVisible || controlVisible || weatherVisible

        val homeMode = if (anyOverlay) ViewGroup.FOCUS_BLOCK_DESCENDANTS else ViewGroup.FOCUS_AFTER_DESCENDANTS
        homeRecycler.descendantFocusability = homeMode
        topBar.descendantFocusability = homeMode

        weatherOverlay.descendantFocusability = if (weatherVisible && !passVisible && !appMenuVisible && !standbyVisible) {
            ViewGroup.FOCUS_AFTER_DESCENDANTS
        } else {
            ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }

        controlOverlay.descendantFocusability = if (controlVisible && !passVisible && !appMenuVisible && !standbyVisible && !settingsVisible) {
            ViewGroup.FOCUS_AFTER_DESCENDANTS
        } else {
            ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }

        settingsOverlay.descendantFocusability = if (settingsVisible && !passVisible && !appMenuVisible && !standbyVisible) {
            ViewGroup.FOCUS_AFTER_DESCENDANTS
        } else {
            ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }

        appMenuOverlay.descendantFocusability = if (appMenuVisible) {
            ViewGroup.FOCUS_AFTER_DESCENDANTS
        } else {
            ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }

        wifiPassOverlay.descendantFocusability = if (passVisible) {
            ViewGroup.FOCUS_AFTER_DESCENDANTS
        } else {
            ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }
    }

    private fun restoreHomeFocus() {
        val pkg = lastFocusedHomePkg
        if (pkg != null) {
            val dockIndex = installedApps.take(dockColumns).indexOfFirst { it.packageName == pkg }
            if (dockIndex >= 0) {
                focusDockTile(dockIndex)
                return
            }
            val gridPos = installedApps.indexOfFirst { it.packageName == pkg }
            if (gridPos >= dockColumns) {
                val adapterPos = gridPos - dockColumns + 2
                homeRecycler.post {
                    val vh = homeRecycler.findViewHolderForAdapterPosition(adapterPos)
                    if (vh != null) {
                        vh.itemView.requestFocus()
                    } else {
                        focusDockTile(0)
                    }
                }
                return
            }
        }
        focusDockTile(0)
    }

    private fun focusDockTile(preferredIndex: Int) {
        homeRecycler.post {
            if (isFinishing || isDestroyed) return@post
            val holder = homeRecycler.findViewHolderForAdapterPosition(1) as? AppleTvHomeAdapter.TopShelfVH
            if (holder != null && holder.row.childCount > 0) {
                val clamped = preferredIndex.coerceIn(0, holder.row.childCount - 1)
                for (i in clamped downTo 0) {
                    val child = holder.row.getChildAt(i)
                    if (child != null && child.isFocusable) {
                        child.requestFocus()
                        return@post
                    }
                }
                for (i in 0 until holder.row.childCount) {
                    val child = holder.row.getChildAt(i)
                    if (child != null && child.isFocusable) {
                        child.requestFocus()
                        return@post
                    }
                }
            }
            homeRecycler.scrollToPosition(0)
            homeRecycler.post {
                val retry = homeRecycler.findViewHolderForAdapterPosition(1) as? AppleTvHomeAdapter.TopShelfVH
                val count = retry?.row?.childCount ?: 0
                if (retry != null && count > 0) {
                    val idx = preferredIndex.coerceIn(0, count - 1)
                    for (i in idx downTo 0) {
                        val c = retry.row.getChildAt(i)
                        if (c != null && c.isFocusable) {
                            c.requestFocus()
                            return@post
                        }
                    }
                } else {
                    homeRecycler.requestFocus()
                }
            }
        }
    }

    private fun focusTopBarForDockIndex(dockIndex: Int) {
        homeRecycler.scrollToPosition(0)
        topBar.visibility = View.VISIBLE
        topBar.alpha = 1f
        val target: View = when (dockIndex) {
            0 -> weatherPillBtn
            1, 2 -> wpCircleBtn
            else -> rightCapsule
        }
        target.post { target.requestFocus() }
    }

    private fun wireTopBarFocusOrder() {
        val visibleItems = listOf(weatherPillBtn, wpCircleBtn, rightCapsule)
        visibleItems.forEachIndexed { i, v ->
            v.nextFocusUpId = v.id
            v.nextFocusLeftId = if (i > 0) visibleItems[i - 1].id else v.id
            v.nextFocusRightId = if (i < visibleItems.lastIndex) visibleItems[i + 1].id else v.id
        }
    }

    private fun attachTopBarDpadDown(view: View, targetDockIndex: Int) {
        view.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                focusDockTile(targetDockIndex)
                true
            } else {
                false
            }
        }
    }

    // =========================================================================
    // LAYER 0: FULL-BLEED WALLPAPER + ATMOSPHERIC SCRIM
    // =========================================================================
    private fun buildWallpaperLayer() {
        wallpaperView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.parseColor("#111B2E"), Color.parseColor("#1F3854"))
            )
        }
        rootFrame.addView(wallpaperView, FrameLayout.LayoutParams(-1, -1))

        val bottomVignette = View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.TRANSPARENT, argb(0x70060A14))
            )
        }
        rootFrame.addView(bottomVignette, FrameLayout.LayoutParams(-1, dp(165), Gravity.BOTTOM))

        scrollDimOverlay = View(this).apply {
            setBackgroundColor(Color.BLACK)
            alpha = 0f
        }
        rootFrame.addView(scrollDimOverlay, FrameLayout.LayoutParams(-1, -1))
    }

    private fun loadWallpaper() {
        io.execute {
            val dm = resources.displayMetrics
            val bmp = WallpaperServer.loadActiveWallpaperBitmap(this, dm.widthPixels, dm.heightPixels)
            val blurred = WallpaperServer.loadActiveBlurredBackdrop(this)
            ui.post {
                if (isFinishing || isDestroyed) return@post
                if (bmp != null) {
                    currentWallpaper = bmp
                    wallpaperView.setImageBitmap(bmp)
                }
                currentBlurred = blurred
                ccBackdrop.setImageBitmap(blurred)
                weatherBackdrop.setImageBitmap(blurred)
            }
        }
    }

    // =========================================================================
    // LAYER 1 & 2: APPLE TV HOME SCREEN (5% TV SAFE-AREA MARGINS + 10-FT TYPE)
    // =========================================================================
    private fun buildHomeUi() {
        homeRecycler = RecyclerView(this).apply {
            clipToPadding = false
            clipChildren = false
            overScrollMode = View.OVER_SCROLL_NEVER
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            // 5% overscan-safe TV horizontal padding (48dp) & bottom clearance (36dp)
            setPadding(dp(48), 0, dp(48), dp(36))
        }
        homeLayoutManager = GridLayoutManager(this, dockColumns)
        homeAdapter = AppleTvHomeAdapter(emptyList())
        homeLayoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                return if (position == 0 || position == 1) dockColumns else 1
            }
        }
        homeRecycler.layoutManager = homeLayoutManager
        homeRecycler.adapter = homeAdapter
        TvMemoryManager.optimizeRecyclerView(homeRecycler, gridViewType = 2, maxRecycledCards = 12, viewCacheSize = 4)

        homeRecycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                val scrollY = rv.computeVerticalScrollOffset()
                scrollDimOverlay.alpha = (scrollY / dp(160).toFloat()).coerceIn(0f, 1f) * 0.50f
                val barAlpha = (1f - (scrollY / dp(85).toFloat())).coerceIn(0f, 1f)
                if (topBar.hasFocus()) {
                    topBar.alpha = 1f
                    topBar.visibility = View.VISIBLE
                } else {
                    topBar.alpha = barAlpha
                    topBar.visibility = if (barAlpha < 0.06f) View.GONE else View.VISIBLE
                }
            }
        })
        rootFrame.addView(homeRecycler, FrameLayout.LayoutParams(-1, -1))

        // Elevated Floating Liquid Glass Top Bar (5% overscan safe-area padding)
        topBar = RelativeLayout(this).apply {
            setPadding(dp(48), dp(28), dp(48), dp(10))
            elevation = dp(16).toFloat()
            clipChildren = false
            clipToPadding = false
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }

        val leftGroup = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
        }

        // 1. Liquid Glass Weather Pill: Location + Temperature + Condition (10-ft legibility)
        weatherPillBtn = LinearLayout(this).apply {
            id = View.generateViewId()
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(8), dp(16), dp(8))
            background = liquidGlass(tintColor = argb(0x460E1624), isPill = true)
            isFocusable = true
            isClickable = true
            setOnClickListener { openWeatherPanel() }
            attachGlassInteractive(this, 1.07f)
            attachTopBarDpadDown(this, 0)

            weatherPillIcon = TextView(context).apply {
                text = "☀"
                textSize = 16f
                setPadding(0, 0, dp(7), 0)
            }
            addView(weatherPillIcon)

            weatherPillText = TextView(context).apply {
                text = "${SystemMonitor.getSavedCityName(context)} · --°"
                setTextColor(Color.WHITE)
                textSize = 14.5f
                fontFeatureSettings = "tnum"
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setShadowLayer(3f, 0f, 1f, argb(0x80000000))
            }
            addView(weatherPillText)
        }
        leftGroup.addView(weatherPillBtn)

        // 2. Liquid Glass Wallpaper Circle -> Opens Wallpapers Tab in Settings
        wpCircleBtn = createLiquidGlassCircleBtn(Glyph.WALLPAPER, dp(42), argb(0x460E1624), Color.WHITE) {
            openSettingsOverlay(tab = 2)
        }.apply {
            id = View.generateViewId()
            attachTopBarDpadDown(this, 1)
        }
        leftGroup.addView(wpCircleBtn, LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginStart = dp(10) })

        topBar.addView(leftGroup, RelativeLayout.LayoutParams(-2, -2).apply {
            addRule(RelativeLayout.ALIGN_PARENT_START)
            addRule(RelativeLayout.CENTER_VERTICAL)
        })

        // 3. Top-Right: Liquid Glass Clock + Avatar Capsule -> Opens Control Center
        rightCapsule = LinearLayout(this).apply {
            id = View.generateViewId()
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(4), dp(4), dp(4))
            background = liquidGlass(tintColor = argb(0x480E1624), isPill = true)
            isFocusable = true
            isClickable = true
            setOnClickListener { openControlCenter() }
            attachGlassInteractive(this, 1.07f)
            attachTopBarDpadDown(this, dockColumns - 1)
        }

        clockText = TextView(this).apply {
            text = "12:00 PM"
            setTextColor(Color.WHITE)
            textSize = 15.5f
            fontFeatureSettings = "tnum"
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setShadowLayer(3f, 0f, 1f, argb(0x80000000))
            setPadding(0, 0, dp(10), 0)
        }
        rightCapsule.addView(clockText)

        val avatarBadge = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(argb(0xFFF08A1A))
                setStroke(dp(1), argb(0xB0FFFFFF))
            }
            addView(VectorIconView(context, Glyph.USER, Color.WHITE), FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER))
        }
        rightCapsule.addView(avatarBadge, LinearLayout.LayoutParams(dp(34), dp(34)))

        topBar.addView(rightCapsule, RelativeLayout.LayoutParams(-2, -2).apply {
            addRule(RelativeLayout.ALIGN_PARENT_END)
            addRule(RelativeLayout.CENTER_VERTICAL)
        })

        wireTopBarFocusOrder()
        rootFrame.addView(topBar, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
    }

    // =========================================================================
    // LAYER 3: FULL FROSTED HOURLY & 5-DAY WEATHER PANEL (D-PAD SCROLLABLE)
    // =========================================================================
    private fun buildWeatherOverlay() {
        weatherOverlay = FrameLayout(this).apply {
            visibility = View.GONE
            isClickable = true
            isFocusable = false
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            elevation = dp(24).toFloat()
        }
        weatherBackdrop = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setColorFilter(argb(0x4A060A14), PorterDuff.Mode.SRC_OVER)
            isClickable = true
            isFocusable = false
            setOnClickListener { closeWeatherPanel() }
        }
        weatherOverlay.addView(weatherBackdrop, FrameLayout.LayoutParams(-1, -1))

        val panelCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(20))
            background = liquidGlass(tintColor = argb(0x78101828), cornerRadiusPx = dp(28).toFloat())
            isClickable = true
            isFocusable = false
        }
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            isSmoothScrollingEnabled = true
            clipChildren = false
            clipToPadding = false
        }
        weatherContentHost = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            setPadding(dp(2), dp(2), dp(2), dp(6))
        }
        scroll.addView(weatherContentHost, FrameLayout.LayoutParams(-1, -2))
        panelCard.addView(scroll, LinearLayout.LayoutParams(-1, -2))

        val maxW = (resources.displayMetrics.widthPixels * 0.82f).toInt().coerceAtMost(dp(720))
        weatherOverlay.addView(panelCard, FrameLayout.LayoutParams(maxW, -2, Gravity.CENTER).apply {
            setMargins(dp(48), dp(28), dp(48), dp(28))
        })
        rootFrame.addView(weatherOverlay, FrameLayout.LayoutParams(-1, -1))
    }

    private fun openWeatherPanel() {
        applyWallpaperBlur(true)
        weatherOverlay.visibility = View.VISIBLE
        syncLayerFocusability()
        renderWeatherPanel(SystemMonitor.cachedWeather, requestButtonFocus = true)
        refreshWeatherAsync(updatePanel = true)
    }

    private fun closeWeatherPanel() {
        applyWallpaperBlur(false)
        weatherOverlay.visibility = View.GONE
        syncLayerFocusability()
        weatherPillBtn.post { weatherPillBtn.requestFocus() }
    }

    private fun formatWeatherConditionLabel(report: WeatherReport): String {
        if (report.code == 0) {
            val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            return if (hour in 6..18) "Sunny" else "Clear"
        }
        return report.condition
    }

    private fun renderWeatherPanel(report: WeatherReport?, requestButtonFocus: Boolean = false) {
        val savedFocusIdx = if (!requestButtonFocus) captureFocusableIndex(weatherContentHost) else -1
        weatherContentHost.removeAllViews()

        val header = RelativeLayout(this)
        val title = TextView(this).apply {
            text = report?.city ?: SystemMonitor.getSavedCityName(this@MainActivity)
            setTextColor(Color.WHITE)
            textSize = 22f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        header.addView(title, RelativeLayout.LayoutParams(-2, -2).apply {
            addRule(RelativeLayout.ALIGN_PARENT_START)
            addRule(RelativeLayout.CENTER_VERTICAL)
        })

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val changeCityBtn = makePillAction("Change City", argb(0x38FFFFFF), Color.WHITE) {
            weatherOverlay.visibility = View.GONE
            applyWallpaperBlur(false)
            openSettingsOverlay(tab = 5)
        }.apply { id = View.generateViewId() }

        val closeBtn = makePillAction("Close", argb(0xFFF08A1A), Color.WHITE) {
            closeWeatherPanel()
        }.apply { id = View.generateViewId() }

        changeCityBtn.nextFocusRightId = closeBtn.id
        changeCityBtn.nextFocusLeftId = changeCityBtn.id
        changeCityBtn.nextFocusUpId = changeCityBtn.id
        closeBtn.nextFocusLeftId = changeCityBtn.id
        closeBtn.nextFocusRightId = closeBtn.id
        closeBtn.nextFocusUpId = closeBtn.id

        actions.addView(changeCityBtn)
        actions.addView(closeBtn, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(10) })
        header.addView(actions, RelativeLayout.LayoutParams(-2, -2).apply {
            addRule(RelativeLayout.ALIGN_PARENT_END)
            addRule(RelativeLayout.CENTER_VERTICAL)
        })
        weatherContentHost.addView(header, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })

        if (report == null) {
            weatherContentHost.addView(TextView(this).apply {
                text = "Loading live forecast from Open-Meteo..."
                setTextColor(argb(0xCCFFFFFF))
                textSize = 15f
                setPadding(0, dp(24), 0, dp(24))
                gravity = Gravity.CENTER
            })
            if (requestButtonFocus || savedFocusIdx < 0) {
                closeBtn.post { closeBtn.requestFocus() }
            }
            return
        }

        val conditionLabel = formatWeatherConditionLabel(report)
        val split = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            clipToPadding = false
        }

        val leftCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        leftCol.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(context).apply {
                text = "${report.tempC}°"
                setTextColor(Color.WHITE)
                textSize = 48f
                fontFeatureSettings = "tnum"
                typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), 0, 0, 0)
                addView(TextView(context).apply {
                    text = "${SystemMonitor.weatherCodeToEmoji(report.code)}  $conditionLabel"
                    setTextColor(Color.WHITE)
                    textSize = 16f
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                })
                addView(TextView(context).apply {
                    text = "H:${report.highC}°  L:${report.lowC}°"
                    setTextColor(argb(0xD8E2E8F0))
                    textSize = 14f
                    fontFeatureSettings = "tnum"
                })
            })
        })

        leftCol.addView(makeSectionHeader("HOURLY FORECAST"))
        val hourlyRow = LinearLayout(this).apply {
            id = View.generateViewId()
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = liquidGlass(tintColor = argb(0x28FFFFFF), cornerRadiusPx = dp(18).toFloat())
            isFocusable = true
            attachGlassInteractive(this, 1.02f)
        }
        for (h in report.hourly) {
            hourlyRow.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                addView(TextView(context).apply {
                    text = h.label; setTextColor(argb(0xDCE2E8F0)); textSize = 12.5f; gravity = Gravity.CENTER
                    fontFeatureSettings = "tnum"
                })
                addView(TextView(context).apply {
                    text = SystemMonitor.weatherCodeToEmoji(h.code); textSize = 17f; gravity = Gravity.CENTER
                    setPadding(0, dp(4), 0, dp(4))
                })
                addView(TextView(context).apply {
                    text = "${h.tempC}°"; setTextColor(Color.WHITE); textSize = 14f; gravity = Gravity.CENTER
                    fontFeatureSettings = "tnum"
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                })
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        leftCol.addView(hourlyRow, LinearLayout.LayoutParams(-1, -2))

        leftCol.addView(makeSectionHeader("5-DAY FORECAST"))
        val dailyCard = LinearLayout(this).apply {
            id = View.generateViewId()
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = liquidGlass(tintColor = argb(0x28FFFFFF), cornerRadiusPx = dp(18).toFloat())
            isFocusable = true
            attachGlassInteractive(this, 1.02f)
        }
        val overallMin = report.daily.minOfOrNull { it.minC } ?: 0
        val overallMax = (report.daily.maxOfOrNull { it.maxC } ?: 40).coerceAtLeast(overallMin + 1)

        for (d in report.daily) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(5), 0, dp(5))
            }
            row.addView(TextView(this).apply {
                text = d.day; setTextColor(Color.WHITE); textSize = 14f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            }, LinearLayout.LayoutParams(dp(56), -2))

            row.addView(TextView(this).apply {
                text = SystemMonitor.weatherCodeToEmoji(d.code); textSize = 16f; gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(dp(30), -2))

            row.addView(TextView(this).apply {
                text = "${d.minC}°"; setTextColor(argb(0xCCE2E8F0)); textSize = 13.5f; gravity = Gravity.END
                fontFeatureSettings = "tnum"
            }, LinearLayout.LayoutParams(dp(34), -2))

            val barTrack = FrameLayout(this).apply {
                background = glassPill(argb(0x38000000), 0, dp(4))
            }
            val startFrac = ((d.minC - overallMin).toFloat() / (overallMax - overallMin)).coerceIn(0f, 0.85f)
            val endFrac = ((d.maxC - overallMin).toFloat() / (overallMax - overallMin)).coerceIn(startFrac + 0.15f, 1f)
            val barWidthPx = dp(96)
            val fillView = View(this).apply {
                background = GradientDrawable(
                    GradientDrawable.Orientation.LEFT_RIGHT,
                    intArrayOf(Color.parseColor("#4ADE80"), Color.parseColor("#FACC15"), Color.parseColor("#FB923C"))
                ).apply { cornerRadius = dp(4).toFloat() }
            }
            val lpFill = FrameLayout.LayoutParams(((endFrac - startFrac) * barWidthPx).toInt().coerceAtLeast(dp(12)), dp(6)).apply {
                marginStart = (startFrac * barWidthPx).toInt()
            }
            barTrack.addView(fillView, lpFill)
            row.addView(barTrack, LinearLayout.LayoutParams(barWidthPx, dp(6)).apply {
                marginStart = dp(10); marginEnd = dp(10)
            })

            row.addView(TextView(this).apply {
                text = "${d.maxC}°"; setTextColor(Color.WHITE); textSize = 13.5f
                fontFeatureSettings = "tnum"
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            }, LinearLayout.LayoutParams(dp(34), -2))

            dailyCard.addView(row)
        }
        leftCol.addView(dailyCard, LinearLayout.LayoutParams(-1, -2))

        val rightCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(6), 0, 0)
            clipChildren = false
            clipToPadding = false
        }
        val m1 = buildWeatherMetricPair("FEELS LIKE", "${report.feelsLikeC}°", "UV INDEX", "${report.uvIndex}")
        val m2 = buildWeatherMetricPair("SUNRISE", report.sunrise, "SUNSET", report.sunset)
        val m3 = buildWeatherMetricPair("HUMIDITY", "${report.humidity}%", "WIND", "${report.windKmh} km/h")
        rightCol.addView(m1)
        rightCol.addView(m2)
        rightCol.addView(m3)
        rightCol.addView(TextView(this).apply {
            text = "Rain Chance (6h): ${report.rainChance}%\nOpen-Meteo • Updated ${report.updatedAt}"
            setTextColor(argb(0xB0E2E8F0))
            textSize = 12.5f
            setPadding(dp(4), dp(8), 0, 0)
        })

        // Wire clean 2D D-pad focus across Weather modal so ScrollView scrolls smoothly
        changeCityBtn.nextFocusDownId = hourlyRow.id
        closeBtn.nextFocusDownId = m1.id
        hourlyRow.nextFocusUpId = changeCityBtn.id
        hourlyRow.nextFocusDownId = dailyCard.id
        hourlyRow.nextFocusRightId = m1.id
        dailyCard.nextFocusUpId = hourlyRow.id
        dailyCard.nextFocusDownId = dailyCard.id
        dailyCard.nextFocusRightId = m3.id
        m1.nextFocusUpId = closeBtn.id
        m1.nextFocusLeftId = hourlyRow.id
        m1.nextFocusDownId = m2.id
        m2.nextFocusUpId = m1.id
        m2.nextFocusLeftId = hourlyRow.id
        m2.nextFocusDownId = m3.id
        m3.nextFocusUpId = m2.id
        m3.nextFocusLeftId = dailyCard.id
        m3.nextFocusDownId = m3.id

        split.addView(leftCol, LinearLayout.LayoutParams(0, -2, 1.3f))
        split.addView(rightCol, LinearLayout.LayoutParams(0, -2, 1f))
        weatherContentHost.addView(split)

        if (requestButtonFocus) {
            closeBtn.post { closeBtn.requestFocus() }
        } else if (savedFocusIdx >= 0) {
            restoreFocusableIndex(weatherContentHost, savedFocusIdx, closeBtn)
        }
    }

    private fun buildWeatherMetricPair(t1: String, v1: String, t2: String, v2: String): LinearLayout {
        return LinearLayout(this).apply {
            id = View.generateViewId()
            orientation = LinearLayout.HORIZONTAL
            isFocusable = true
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = liquidGlass(tintColor = argb(0x18FFFFFF), cornerRadiusPx = dp(18).toFloat())
            attachGlassInteractive(this, 1.02f)
            addView(buildWeatherMetricBox(t1, v1), LinearLayout.LayoutParams(0, -2, 1f))
            addView(buildWeatherMetricBox(t2, v2), LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) })
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }
        }
    }

    private fun buildWeatherMetricBox(label: String, value: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(9), dp(10), dp(9))
            background = glassPill(argb(0x20FFFFFF), 0, dp(14))
            addView(TextView(context).apply {
                text = label; setTextColor(argb(0xC0E2E8F0)); textSize = 11.5f
                letterSpacing = 0.06f
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            })
            addView(TextView(context).apply {
                text = value; setTextColor(Color.WHITE); textSize = 17f
                fontFeatureSettings = "tnum"
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setPadding(0, dp(4), 0, 0)
            })
        }
    }

    // =========================================================================
    // LAYER 4: AUTHENTIC tvOS LIQUID GLASS CONTROL CENTER (CLEAN LIVING-ROOM UI)
    // =========================================================================
    private fun buildControlCenterOverlay() {
        controlOverlay = FrameLayout(this).apply {
            visibility = View.GONE
            isClickable = true
            isFocusable = false
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            elevation = dp(26).toFloat()
        }

        ccBackdrop = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setColorFilter(argb(0x36080C16), PorterDuff.Mode.SRC_OVER)
            isClickable = true
            isFocusable = false
            setOnClickListener { closeControlCenter() }
        }
        controlOverlay.addView(ccBackdrop, FrameLayout.LayoutParams(-1, -1))

        val panelW = dp(356)
        val rightWrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // Respect 5% overscan safe area on right, top & bottom
            setPadding(dp(14), dp(28), dp(48), dp(28))
            gravity = Gravity.END
            clipChildren = false
            clipToPadding = false
        }

        // Header Row: Tabular Clock on left | White Settings circle + Orange Close circle on right
        val headerRow = RelativeLayout(this).apply {
            clipChildren = false
            clipToPadding = false
        }
        ccClockText = TextView(this).apply {
            text = "12:00 PM"
            setTextColor(Color.WHITE)
            textSize = 18.5f
            fontFeatureSettings = "tnum"
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setShadowLayer(4f, 0f, 1.5f, argb(0x88000000))
        }
        headerRow.addView(ccClockText, RelativeLayout.LayoutParams(-2, -2).apply {
            addRule(RelativeLayout.ALIGN_PARENT_START)
            addRule(RelativeLayout.CENTER_VERTICAL)
        })

        val headerBtns = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
        }

        ccSettingsHeaderBtn = FrameLayout(this).apply {
            id = View.generateViewId()
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(argb(0xF5FFFFFF))
                setStroke(dp(1), Color.WHITE)
            }
            addView(
                VectorIconView(context, Glyph.CONTROL_SETTINGS, Color.parseColor("#141822")),
                FrameLayout.LayoutParams(dp(21), dp(21), Gravity.CENTER)
            )
            isFocusable = true
            isClickable = true
            setOnClickListener {
                controlOverlay.visibility = View.GONE
                applyWallpaperBlur(false)
                openSettingsOverlay(tab = 0)
            }
            attachInteractiveScale(this, 1.12f)
        }
        headerBtns.addView(ccSettingsHeaderBtn, LinearLayout.LayoutParams(dp(42), dp(42)))

        ccCloseHeaderBtn = FrameLayout(this).apply {
            id = View.generateViewId()
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(argb(0xFFF08A1A))
                setStroke(dp(1), argb(0xA0FFFFFF))
            }
            addView(
                VectorIconView(context, Glyph.CLOSE, Color.WHITE),
                FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER)
            )
            isFocusable = true
            isClickable = true
            setOnClickListener { closeControlCenter() }
            attachInteractiveScale(this, 1.12f)
        }
        headerBtns.addView(ccCloseHeaderBtn, LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginStart = dp(10) })

        headerRow.addView(headerBtns, RelativeLayout.LayoutParams(-2, -2).apply {
            addRule(RelativeLayout.ALIGN_PARENT_END)
            addRule(RelativeLayout.CENTER_VERTICAL)
        })
        rightWrapper.addView(headerRow, LinearLayout.LayoutParams(panelW, -2).apply { bottomMargin = dp(16) })

        val pageHost = FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
        }
        ccMainPage = buildControlCenterMainPage()
        ccWifiPage = buildInAppWifiMenu()
        ccBtPage = buildInAppBluetoothMenu()

        ccSettingsHeaderBtn.nextFocusUpId = ccSettingsHeaderBtn.id
        ccSettingsHeaderBtn.nextFocusLeftId = ccSettingsHeaderBtn.id
        ccSettingsHeaderBtn.nextFocusRightId = ccCloseHeaderBtn.id

        ccCloseHeaderBtn.nextFocusUpId = ccCloseHeaderBtn.id
        ccCloseHeaderBtn.nextFocusLeftId = ccSettingsHeaderBtn.id
        ccCloseHeaderBtn.nextFocusRightId = ccCloseHeaderBtn.id
        updateControlCenterHeaderFocusDown()

        pageHost.addView(ccMainPage, FrameLayout.LayoutParams(panelW, -2))
        pageHost.addView(ccWifiPage, FrameLayout.LayoutParams(panelW, -1))
        pageHost.addView(ccBtPage, FrameLayout.LayoutParams(panelW, -1))

        rightWrapper.addView(pageHost, LinearLayout.LayoutParams(panelW, 0, 1f))
        controlOverlay.addView(rightWrapper, FrameLayout.LayoutParams(-2, -1, Gravity.END))
        rootFrame.addView(controlOverlay, FrameLayout.LayoutParams(-1, -1))
    }

    private fun updateControlCenterHeaderFocusDown() {
        when {
            ccWifiPage.visibility == View.VISIBLE -> {
                ccSettingsHeaderBtn.nextFocusDownId = ccWifiBackBtn.id
                ccCloseHeaderBtn.nextFocusDownId = ccWifiBackBtn.id
            }
            ccBtPage.visibility == View.VISIBLE -> {
                ccSettingsHeaderBtn.nextFocusDownId = ccBtBackBtn.id
                ccCloseHeaderBtn.nextFocusDownId = ccBtBackBtn.id
            }
            else -> {
                ccSettingsHeaderBtn.nextFocusDownId = standbyTile.id
                ccCloseHeaderBtn.nextFocusDownId = wifiTileBox.id
            }
        }
    }

    private fun buildControlCenterMainPage(): LinearLayout {
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            clipChildren = false
            clipToPadding = false
        }
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            setPadding(dp(4), dp(4), dp(4), dp(8))
        }

        // Row 1: Translucent Liquid Glass StandBy square (left) + Wi-Fi & Bluetooth pills (right)
        val topGrid = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
        }

        standbyTile = LinearLayout(this).apply {
            id = View.generateViewId()
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = liquidGlass(tintColor = argb(0x2EFFFFFF), cornerRadiusPx = dp(26).toFloat())
            isFocusable = true
            isClickable = true
            setOnClickListener { openStandbyMode() }
            attachGlassInteractive(this, 1.05f)

            addView(
                VectorIconView(context, Glyph.CLOCK, Color.WHITE),
                LinearLayout.LayoutParams(dp(40), dp(40)).apply { gravity = Gravity.CENTER_HORIZONTAL }
            )
            addView(TextView(context).apply {
                text = "StandBy"
                setTextColor(Color.WHITE)
                textSize = 15f
                gravity = Gravity.CENTER
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setShadowLayer(4f, 0f, 1.5f, argb(0x88000000))
                setPadding(0, dp(10), 0, 0)
            }, LinearLayout.LayoutParams(-1, -2))
        }
        topGrid.addView(standbyTile, LinearLayout.LayoutParams(dp(152), dp(136)))

        val rightStack = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
        }

        val wifiPair = buildToggleableTile(
            Glyph.WIFI, "Wi-Fi", "Checking...",
            onShortClick = { handleWifiShortToggle() },
            onLongPress = { openWifiManagerPage() }
        )
        wifiTileBox = wifiPair.first.apply { id = View.generateViewId() }
        wifiSubText = wifiPair.second
        rightStack.addView(wifiTileBox, LinearLayout.LayoutParams(-1, dp(63)))

        val btPair = buildToggleableTile(
            Glyph.BLUETOOTH, "Bluetooth", "Checking...",
            onShortClick = { handleBluetoothShortToggle() },
            onLongPress = { openBluetoothManagerPage() }
        )
        btTileBox = btPair.first.apply { id = View.generateViewId() }
        btSubText = btPair.second
        rightStack.addView(btTileBox, LinearLayout.LayoutParams(-1, dp(63)).apply { topMargin = dp(10) })

        topGrid.addView(rightStack, LinearLayout.LayoutParams(0, dp(136), 1f).apply { marginStart = dp(12) })
        inner.addView(topGrid, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })

        // Row 2: 3 Clean Living-Room Quick Actions (Wallpapers, Mute, Appearance)
        val quickRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            clipChildren = false
            clipToPadding = false
        }
        val wallsAction = buildRoundAction(Glyph.WALLPAPER, "Walls") {
            controlOverlay.visibility = View.GONE
            applyWallpaperBlur(false)
            openSettingsOverlay(tab = 2)
        }
        val muteAction = buildRoundAction(Glyph.VOLUME, "Mute") {
            val muted = SystemMonitor.toggleMute(this)
            refreshLiveConnectivity()
            showGlassHud(if (muted) "Sound Muted" else "Sound On", Glyph.VOLUME)
        }
        val themeAction = buildRoundAction(Glyph.SUN, "Style") {
            themeMode = when (themeMode) {
                "LIQUID" -> "DARK"
                "DARK" -> "LIGHT"
                else -> "LIQUID"
            }
            savePref { putString("theme_mode", themeMode) }
            val pretty = when (themeMode) {
                "LIQUID" -> "Liquid Glass"
                "DARK" -> "Dark Mode"
                else -> "Light Mode"
            }
            showGlassHud("Appearance: $pretty", Glyph.SUN)
        }
        quickRow.addView(wallsAction.first, LinearLayout.LayoutParams(0, -2, 1f))
        quickRow.addView(muteAction.first, LinearLayout.LayoutParams(0, -2, 1f))
        quickRow.addView(themeAction.first, LinearLayout.LayoutParams(0, -2, 1f))
        inner.addView(quickRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })

        // Row 3: Sleek Apple tvOS Liquid Glass Volume Pill
        val volRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(6), dp(18), dp(6))
            background = liquidGlass(tintColor = argb(0x3C0A101C), isPill = true)
            clipChildren = false
            clipToPadding = false
        }
        volRow.addView(VectorIconView(this, Glyph.VOLUME, Color.WHITE), LinearLayout.LayoutParams(dp(20), dp(20)))

        volumeSlider = LiquidVolumeSeekBar(this).apply {
            id = View.generateViewId()
            max = 100
            keyProgressIncrement = 5
            isFocusable = true
            progress = SystemMonitor.getVolumePercent(this@MainActivity)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    volumePctText.text = "$p%"
                    if (fromUser) SystemMonitor.setVolumePercent(this@MainActivity, p)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
            setOnFocusChangeListener { _, hasFocus ->
                (volRow.background as? LiquidGlassDrawable)?.setFocusedState(hasFocus)
                TvMemoryManager.animateTvFocus(volRow, hasFocus, focusedScale = 1.03f, durationMs = 110L)
            }
        }
        volRow.addView(volumeSlider, LinearLayout.LayoutParams(0, dp(34), 1f).apply {
            marginStart = dp(12)
            marginEnd = dp(12)
        })

        volumePctText = TextView(this).apply {
            text = "${SystemMonitor.getVolumePercent(this@MainActivity)}%"
            setTextColor(Color.WHITE)
            textSize = 14.5f
            fontFeatureSettings = "tnum"
            gravity = Gravity.END
            minWidth = dp(40)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setShadowLayer(3f, 0f, 1f, argb(0x80000000))
        }
        volRow.addView(volumePctText)
        inner.addView(volRow, LinearLayout.LayoutParams(-1, dp(54)))

        // Wire exact 2D D-pad focus graph across Control Center
        val c1 = wallsAction.second.apply { id = View.generateViewId() }
        val c2 = muteAction.second.apply { id = View.generateViewId() }
        val c3 = themeAction.second.apply { id = View.generateViewId() }

        standbyTile.nextFocusUpId = ccSettingsHeaderBtn.id
        standbyTile.nextFocusLeftId = standbyTile.id
        standbyTile.nextFocusRightId = wifiTileBox.id
        standbyTile.nextFocusDownId = c1.id

        wifiTileBox.nextFocusUpId = ccCloseHeaderBtn.id
        wifiTileBox.nextFocusLeftId = standbyTile.id
        wifiTileBox.nextFocusRightId = wifiTileBox.id
        wifiTileBox.nextFocusDownId = btTileBox.id

        btTileBox.nextFocusUpId = wifiTileBox.id
        btTileBox.nextFocusLeftId = standbyTile.id
        btTileBox.nextFocusRightId = btTileBox.id
        btTileBox.nextFocusDownId = c3.id

        c1.nextFocusLeftId = c1.id; c1.nextFocusRightId = c2.id; c1.nextFocusUpId = standbyTile.id; c1.nextFocusDownId = volumeSlider.id
        c2.nextFocusLeftId = c1.id; c2.nextFocusRightId = c3.id; c2.nextFocusUpId = standbyTile.id; c2.nextFocusDownId = volumeSlider.id
        c3.nextFocusLeftId = c2.id; c3.nextFocusRightId = c3.id; c3.nextFocusUpId = btTileBox.id; c3.nextFocusDownId = volumeSlider.id

        volumeSlider.nextFocusUpId = c1.id
        volumeSlider.nextFocusDownId = volumeSlider.id
        volumeSlider.nextFocusLeftId = volumeSlider.id
        volumeSlider.nextFocusRightId = volumeSlider.id

        scroll.addView(inner, FrameLayout.LayoutParams(-1, -2))
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            addView(scroll, LinearLayout.LayoutParams(-1, -2))
        }
    }

    // ---------------- IN-APP WI-FI MANAGER ----------------
    private fun buildInAppWifiMenu(): LinearLayout {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = liquidGlass(tintColor = argb(0x6E0E1624), cornerRadiusPx = dp(26).toFloat())
        }
        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        ccWifiBackBtn = makePillAction("‹ Wi-Fi", argb(0x33FFFFFF), Color.WHITE) { showMainControlPage() }.apply {
            id = View.generateViewId()
            nextFocusUpId = ccSettingsHeaderBtn.id
        }
        nav.addView(ccWifiBackBtn)
        nav.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        wifiMenuToggleBtn = makePillAction("On", argb(0xFF2563EB), Color.WHITE) {
            handleWifiShortToggle()
            ui.postDelayed({ renderWifiList(wifiListContainer, wifiMenuToggleBtn, wifiMenuStatusText) }, 700L)
        }.apply {
            id = View.generateViewId()
            nextFocusUpId = ccCloseHeaderBtn.id
        }
        val scanBtn = makePillAction("Scan", argb(0x40FFFFFF), Color.WHITE) {
            renderWifiList(wifiListContainer, wifiMenuToggleBtn, wifiMenuStatusText)
        }.apply {
            id = View.generateViewId()
            nextFocusUpId = ccCloseHeaderBtn.id
        }
        ccWifiBackBtn.nextFocusRightId = wifiMenuToggleBtn.id
        wifiMenuToggleBtn.nextFocusLeftId = ccWifiBackBtn.id
        wifiMenuToggleBtn.nextFocusRightId = scanBtn.id
        scanBtn.nextFocusLeftId = wifiMenuToggleBtn.id

        nav.addView(wifiMenuToggleBtn)
        nav.addView(scanBtn, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
        page.addView(nav, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })

        wifiMenuStatusText = TextView(this).apply {
            text = "Scanning..."
            setTextColor(argb(0xDCE2E8F0))
            textSize = 13f
            setPadding(dp(4), 0, dp(4), dp(8))
        }
        page.addView(wifiMenuStatusText)

        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        wifiListContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(wifiListContainer, FrameLayout.LayoutParams(-1, -2))
        page.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        return page
    }

    private fun openWifiManagerPage() {
        ccMainPage.visibility = View.GONE
        ccBtPage.visibility = View.GONE
        ccWifiPage.visibility = View.VISIBLE
        updateControlCenterHeaderFocusDown()
        renderWifiList(wifiListContainer, wifiMenuToggleBtn, wifiMenuStatusText)
        ccWifiBackBtn.post { ccWifiBackBtn.requestFocus() }
    }

    private fun renderWifiList(container: LinearLayout, toggleBtn: TextView?, statusTv: TextView?) {
        val savedFocusIdx = captureFocusableIndex(container)
        val fallbackFocus = if (ccWifiPage.visibility == View.VISIBLE) ccWifiBackBtn else toggleBtn

        val enabled = SystemMonitor.isWifiEnabled(this)
        toggleBtn?.text = if (enabled) "On" else "Off"
        toggleBtn?.background = glassPill(if (enabled) argb(0xFF2563EB) else argb(0x45FFFFFF), argb(0x66FFFFFF), dp(14))
        statusTv?.text = if (enabled) {
            "Connected: ${SystemMonitor.getWifiStatus(this)} • IP: ${WallpaperServer.getLocalIpAddress()}"
        } else {
            "Wi-Fi is turned off"
        }

        container.removeAllViews()
        if (!enabled) {
            restoreFocusableIndex(container, savedFocusIdx, fallbackFocus)
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            container.addView(buildAppleListRow(
                glyph = Glyph.WIFI,
                title = "Open Android Wi-Fi Settings",
                subtitle = "Persistent system-wide network switcher",
                badge = "›",
                onClick = {
                    SystemMonitor.openSystemWifiSettings(this)
                    showGlassHud("Opening System Wi-Fi", Glyph.WIFI)
                },
                onLongClick = null
            ))
        }

        container.addView(TextView(this).apply {
            text = "Scanning nearby Wi-Fi networks..."
            setTextColor(argb(0xC8FFFFFF))
            textSize = 13.5f
            setPadding(dp(8), dp(10), dp(8), dp(10))
        })

        io.execute {
            val nets = SystemMonitor.scanWifiNetworks(this)
            ui.post {
                if (isFinishing || isDestroyed) return@post
                val liveIdx = captureFocusableIndex(container).let { if (it >= 0) it else savedFocusIdx }
                container.removeAllViews()

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    container.addView(buildAppleListRow(
                        glyph = Glyph.WIFI,
                        title = "Open Android Wi-Fi Settings",
                        subtitle = "Persistent system-wide network switcher",
                        badge = "›",
                        onClick = {
                            SystemMonitor.openSystemWifiSettings(this)
                            showGlassHud("Opening System Wi-Fi", Glyph.WIFI)
                        },
                        onLongClick = null
                    ))
                }

                if (nets.isEmpty()) {
                    container.addView(TextView(this).apply {
                        text = "No networks found. Grant Location permission and tap Scan."
                        setTextColor(argb(0xC8E2E8F0))
                        textSize = 13f
                        setPadding(dp(8), dp(10), dp(8), dp(10))
                    })
                } else {
                    for (net in nets) {
                        val sub = buildString {
                            if (net.connected) append("Connected • ")
                            append(if (net.secured) "Secured" else "Open")
                            append(" • Signal ${net.level + 1}/5")
                        }
                        container.addView(buildAppleListRow(
                            glyph = Glyph.WIFI,
                            title = net.ssid,
                            subtitle = sub,
                            badge = if (net.connected) "✓" else if (net.secured) "🔒" else "",
                            onClick = {
                                if (net.connected) {
                                    showGlassHud("Connected to ${net.ssid}", Glyph.WIFI)
                                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                    SystemMonitor.openSystemWifiSettings(this)
                                    showGlassHud("Select ${net.ssid} in System Wi-Fi", Glyph.WIFI)
                                } else if (net.secured) {
                                    openWifiPasswordModal(net)
                                } else {
                                    connectWifiInApp(net, null)
                                }
                            },
                            onLongClick = null
                        ))
                    }
                }
                restoreFocusableIndex(container, liveIdx, fallbackFocus)
            }
        }
    }

    // ---------------- IN-APP BLUETOOTH MANAGER ----------------
    private fun buildInAppBluetoothMenu(): LinearLayout {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = liquidGlass(tintColor = argb(0x6E0E1624), cornerRadiusPx = dp(26).toFloat())
        }
        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        ccBtBackBtn = makePillAction("‹ Bluetooth", argb(0x33FFFFFF), Color.WHITE) {
            stopBluetoothReceiver()
            showMainControlPage()
        }.apply {
            id = View.generateViewId()
            nextFocusUpId = ccSettingsHeaderBtn.id
        }
        nav.addView(ccBtBackBtn)
        nav.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        btMenuToggleBtn = makePillAction("On", argb(0xFF2563EB), Color.WHITE) {
            handleBluetoothShortToggle()
            ui.postDelayed({ renderBluetoothList(btListContainer, btMenuToggleBtn) }, 700L)
        }.apply {
            id = View.generateViewId()
            nextFocusUpId = ccCloseHeaderBtn.id
        }
        btMenuScanBtn = makePillAction("Scan", argb(0x40FFFFFF), Color.WHITE) { startBluetoothScanInApp() }.apply {
            id = View.generateViewId()
            nextFocusUpId = ccCloseHeaderBtn.id
        }
        ccBtBackBtn.nextFocusRightId = btMenuToggleBtn.id
        btMenuToggleBtn.nextFocusLeftId = ccBtBackBtn.id
        btMenuToggleBtn.nextFocusRightId = btMenuScanBtn.id
        btMenuScanBtn.nextFocusLeftId = btMenuToggleBtn.id

        nav.addView(btMenuToggleBtn)
        nav.addView(btMenuScanBtn, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
        page.addView(nav, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })

        page.addView(TextView(this).apply {
            text = "Click: Connect/Pair  •  Hold: Forget Device"
            setTextColor(argb(0xC8D2E0FA))
            textSize = 12f
            setPadding(dp(4), 0, dp(4), dp(8))
        })

        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        btListContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(btListContainer, FrameLayout.LayoutParams(-1, -2))
        page.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        return page
    }

    private fun openBluetoothManagerPage() {
        ccMainPage.visibility = View.GONE
        ccWifiPage.visibility = View.GONE
        ccBtPage.visibility = View.VISIBLE
        updateControlCenterHeaderFocusDown()
        discoveredBtDevices.clear()
        startBluetoothReceiver()
        renderBluetoothList(btListContainer, btMenuToggleBtn)
        startBluetoothScanInApp()
        ccBtBackBtn.post { ccBtBackBtn.requestFocus() }
    }

    private fun startBluetoothReceiver() {
        if (btReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        try {
            ContextCompat.registerReceiver(this, btDiscoveryReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
            btReceiverRegistered = true
        } catch (_: Exception) {}
    }

    private fun stopBluetoothReceiver() {
        SystemMonitor.stopBluetoothDiscovery(this)
        if (btReceiverRegistered) {
            try { unregisterReceiver(btDiscoveryReceiver) } catch (_: Exception) {}
            btReceiverRegistered = false
        }
    }

    private fun startBluetoothScanInApp() {
        if (!SystemMonitor.isBluetoothEnabled(this)) return
        val ok = SystemMonitor.startBluetoothDiscovery(this)
        if (::btMenuScanBtn.isInitialized) btMenuScanBtn.text = if (ok) "Scanning..." else "Scan"
    }

    private fun renderBluetoothList(container: LinearLayout, toggleBtn: TextView?) {
        val savedFocusIdx = captureFocusableIndex(container)
        val fallbackFocus = if (ccBtPage.visibility == View.VISIBLE) ccBtBackBtn else toggleBtn

        val enabled = SystemMonitor.isBluetoothEnabled(this)
        toggleBtn?.text = if (enabled) "On" else "Off"
        toggleBtn?.background = glassPill(if (enabled) argb(0xFF2563EB) else argb(0x45FFFFFF), argb(0x66FFFFFF), dp(14))
        container.removeAllViews()

        container.addView(buildAppleListRow(
            glyph = Glyph.BLUETOOTH,
            title = "System Bluetooth Settings",
            subtitle = "Manage speakers, headphones & remotes",
            badge = "›",
            onClick = {
                SystemMonitor.openSystemBluetoothSettings(this)
                showGlassHud("Opening Bluetooth Settings", Glyph.BLUETOOTH)
            },
            onLongClick = null
        ))

        if (!enabled) {
            container.addView(TextView(this).apply {
                text = "Bluetooth is off — open System Bluetooth Settings or toggle On."
                setTextColor(argb(0xC8E2E8F0))
                textSize = 13.5f
                setPadding(dp(8), dp(12), dp(8), dp(12))
            })
            restoreFocusableIndex(container, savedFocusIdx, fallbackFocus)
            return
        }

        val paired = SystemMonitor.getPairedBluetoothDevices(this)
        val pairedAddrs = paired.map { it.address }.toSet()

        if (paired.isNotEmpty()) {
            container.addView(makeSectionHeader("MY DEVICES"))
            for (dev in paired) {
                container.addView(buildAppleListRow(
                    glyph = Glyph.BLUETOOTH,
                    title = dev.name,
                    subtitle = if (dev.connected) "Connected • Click to Disconnect" else "Paired • Click to Connect",
                    badge = if (dev.connected) "●" else "",
                    onClick = {
                        if (dev.connected) {
                            SystemMonitor.disconnectBluetoothDevice(this, dev.device) { ok, msg ->
                                ui.post {
                                    if (isFinishing || isDestroyed) return@post
                                    showGlassHud("${dev.name}: $msg", Glyph.BLUETOOTH)
                                    if (!ok) SystemMonitor.openSystemBluetoothSettings(this)
                                    ui.postDelayed({ renderBluetoothList(container, toggleBtn); refreshLiveConnectivity() }, 900L)
                                }
                            }
                        } else {
                            SystemMonitor.connectBluetoothDevice(this, dev.device) { ok, msg ->
                                ui.post {
                                    if (isFinishing || isDestroyed) return@post
                                    showGlassHud("${dev.name}: $msg", Glyph.BLUETOOTH)
                                    if (!ok) SystemMonitor.openSystemBluetoothSettings(this)
                                    ui.postDelayed({ renderBluetoothList(container, toggleBtn); refreshLiveConnectivity() }, 1200L)
                                }
                            }
                        }
                    },
                    onLongClick = {
                        val ok = SystemMonitor.forgetBluetoothDevice(dev.device)
                        if (ok) {
                            showGlassHud("Forgot ${dev.name}", Glyph.BLUETOOTH)
                        } else {
                            showGlassHud("Opening Bluetooth Settings", Glyph.BLUETOOTH)
                            SystemMonitor.openSystemBluetoothSettings(this)
                        }
                        ui.postDelayed({ renderBluetoothList(container, toggleBtn); refreshLiveConnectivity() }, 700L)
                    }
                ))
            }
        }

        val nearby = discoveredBtDevices.values.filter { !pairedAddrs.contains(it.address) }
        container.addView(makeSectionHeader("NEARBY DEVICES"))
        if (nearby.isEmpty()) {
            container.addView(TextView(this).apply {
                text = "Searching for nearby speakers & remotes..."
                setTextColor(argb(0xB8DCE6F5))
                textSize = 13f
                setPadding(dp(8), dp(8), dp(8), dp(8))
            })
        } else {
            for (dev in nearby) {
                container.addView(buildAppleListRow(
                    glyph = Glyph.BLUETOOTH,
                    title = dev.name,
                    subtitle = "Available • Click to Pair",
                    badge = "+",
                    onClick = {
                        val ok = SystemMonitor.pairBluetoothDevice(dev.device)
                        showGlassHud(if (ok) "Pairing with ${dev.name}..." else "Pairing failed", Glyph.BLUETOOTH)
                    },
                    onLongClick = null
                ))
            }
        }
        restoreFocusableIndex(container, savedFocusIdx, fallbackFocus)
    }

    // =========================================================================
    // LAYER 5: FULL NATIVE SPLIT-SCREEN tvOS SETTINGS APP (DARK / LIGHT / LIQUID)
    // =========================================================================
    private fun buildSettingsOverlay() {
        settingsOverlay = FrameLayout(this).apply {
            setBackgroundColor(argb(0xB8060910))
            visibility = View.GONE
            isClickable = true
            isFocusable = false
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            elevation = dp(28).toFloat()
        }

        settingsCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            isClickable = true
            isFocusable = false
        }

        val leftScroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        settingsSidebar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        leftScroll.addView(settingsSidebar, FrameLayout.LayoutParams(-1, -2))
        settingsCard.addView(leftScroll, LinearLayout.LayoutParams(dp(236), -1))

        settingsCard.addView(View(this).apply { setBackgroundColor(argb(0x28FFFFFF)) }, LinearLayout.LayoutParams(dp(1), -1))

        val rightScroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        settingsDetailHost = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(22))
        }
        rightScroll.addView(settingsDetailHost, FrameLayout.LayoutParams(-1, -2))
        settingsCard.addView(rightScroll, LinearLayout.LayoutParams(0, -1, 1f))

        val cardW = (resources.displayMetrics.widthPixels - dp(96)).coerceAtMost(dp(820))
        val cardH = (resources.displayMetrics.heightPixels - dp(56)).coerceAtMost(dp(490))
        settingsOverlay.addView(settingsCard, FrameLayout.LayoutParams(cardW, cardH, Gravity.CENTER))
        rootFrame.addView(settingsOverlay, FrameLayout.LayoutParams(-1, -1))
    }

    private fun openSettingsOverlay(tab: Int = 0) {
        activeSettingsTab = tab
        applyThemeToSettingsCard()
        settingsOverlay.visibility = View.VISIBLE
        syncLayerFocusability()
        renderSettingsSidebar(focusActiveTab = true)
        renderSettingsDetail(preserveFocus = false)
    }

    private fun closeSettingsOverlay() {
        wallpaperServer?.stop()
        stopBluetoothReceiver()
        settingsOverlay.visibility = View.GONE
        syncLayerFocusability()
        restoreHomeFocus()
    }

    private fun isLightTheme(): Boolean = themeMode == "LIGHT"

    private fun applyThemeToSettingsCard() {
        settingsCard.background = when (themeMode) {
            "LIGHT" -> glassPill(argb(0xF5F2F4F8), argb(0x30000000), dp(26))
            "DARK" -> glassPill(argb(0xFA11141C), argb(0x45FFFFFF), dp(26))
            else -> liquidGlass(tintColor = argb(0xC2101828), cornerRadiusPx = dp(26).toFloat())
        }
    }

    private fun renderSettingsSidebar(focusActiveTab: Boolean = false) {
        val savedSidebarFocus = if (!focusActiveTab) captureFocusableIndex(settingsSidebar) else -1
        settingsSidebar.removeAllViews()
        val fg = if (isLightTheme()) Color.parseColor("#111622") else Color.WHITE
        val subFg = if (isLightTheme()) Color.parseColor("#526075") else argb(0xB8DCE6F5)

        val profileHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(4), dp(6), dp(16))
            addView(FrameLayout(context).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(argb(0xFFF08A1A)) }
                addView(VectorIconView(context, Glyph.USER, Color.WHITE), FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER))
            }, LinearLayout.LayoutParams(dp(38), dp(38)).apply { marginEnd = dp(12) })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(context).apply {
                    text = "TvOS Native"; setTextColor(fg); textSize = 15.5f
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                })
                addView(TextView(context).apply { text = "v1.1 • Settings"; setTextColor(subFg); textSize = 12.5f })
            })
        }
        settingsSidebar.addView(profileHeader)

        val tabs = listOf(
            Triple(Glyph.SUN, "Appearance", argb(0xFF0EA5E9)),
            Triple(Glyph.DOCK, "Home & Apps", argb(0xFF6366F1)),
            Triple(Glyph.WALLPAPER, "Wallpapers", argb(0xFFEC4899)),
            Triple(Glyph.WIFI, "Wi-Fi", argb(0xFF3B82F6)),
            Triple(Glyph.BLUETOOTH, "Bluetooth", argb(0xFF2563EB)),
            Triple(Glyph.CLOCK, "Weather & Clock", argb(0xFFF59E0B)),
            Triple(Glyph.SETTINGS, "System", argb(0xFF10B981))
        )

        var activeTabRow: View? = null
        tabs.forEachIndexed { idx, (glyph, title, badgeColor) ->
            val selected = idx == activeSettingsTab
            val rowBg = when {
                selected && isLightTheme() -> glassPill(argb(0xFF111622), 0, dp(14))
                selected -> glassPill(argb(0xF5FFFFFF), 0, dp(14))
                else -> glassPill(0, 0, dp(14))
            }
            val rowTextColor = when {
                selected && isLightTheme() -> Color.WHITE
                selected -> Color.parseColor("#111622")
                else -> fg
            }
            val titleTv = TextView(this).apply {
                text = title; setTextColor(rowTextColor); textSize = 14.5f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = rowBg
                isFocusable = true
                isClickable = true
                setOnClickListener {
                    activeSettingsTab = idx
                    renderSettingsSidebar(focusActiveTab = true)
                    renderSettingsDetail(preserveFocus = false)
                }
                setOnFocusChangeListener { v, hasFocus ->
                    if (hasFocus) {
                        v.background = glassPill(argb(0xFF2563EB), Color.WHITE, dp(14), dp(2))
                        titleTv.setTextColor(Color.WHITE)
                    } else {
                        v.background = rowBg
                        titleTv.setTextColor(rowTextColor)
                    }
                }
                addView(FrameLayout(context).apply {
                    background = glassPill(badgeColor, 0, dp(8))
                    addView(VectorIconView(context, glyph, Color.WHITE), FrameLayout.LayoutParams(dp(16), dp(16), Gravity.CENTER))
                }, LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginEnd = dp(12) })
                addView(titleTv)
            }
            if (selected) activeTabRow = row
            settingsSidebar.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
        }

        settingsSidebar.addView(
            makePillAction("Done", argb(0xFFF08A1A), Color.WHITE) { closeSettingsOverlay() },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) }
        )

        if (focusActiveTab) {
            activeTabRow?.post { activeTabRow?.requestFocus() }
        } else if (savedSidebarFocus >= 0) {
            restoreFocusableIndex(settingsSidebar, savedSidebarFocus, activeTabRow)
        }
    }

    private fun renderSettingsDetail(preserveFocus: Boolean = true) {
        val savedFocusIdx = if (preserveFocus) captureFocusableIndex(settingsDetailHost) else -1
        if (activeSettingsTab != 2) wallpaperServer?.stop()
        if (activeSettingsTab != 4) stopBluetoothReceiver()
        settingsDetailHost.removeAllViews()

        val fg = if (isLightTheme()) Color.parseColor("#111622") else Color.WHITE
        val subFg = if (isLightTheme()) Color.parseColor("#526075") else argb(0xC0D8E2F2)
        val boxBg = if (isLightTheme()) argb(0x12000000) else argb(0x1EFFFFFF)

        fun heading(text: String) {
            settingsDetailHost.addView(TextView(this).apply {
                this.text = text; setTextColor(fg); textSize = 22f
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setPadding(0, 0, 0, dp(14))
            })
        }

        fun settingButton(title: String, subtitle: String, active: Boolean = false, onClick: () -> Unit) {
            val baseBg = glassPill(if (active) argb(0xFF2563EB) else boxBg, argb(0x30FFFFFF), dp(16))
            val focusBg = glassPill(if (active) argb(0xFF1D4ED8) else argb(0x45FFFFFF), Color.WHITE, dp(16), dp(2))
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(13), dp(16), dp(13))
                background = baseBg
                isFocusable = true
                isClickable = true
                setOnClickListener { onClick() }
                setOnFocusChangeListener { v, hasFocus ->
                    v.background = if (hasFocus) focusBg else baseBg
                    TvMemoryManager.animateTvFocus(v, hasFocus, focusedScale = 1.02f, durationMs = 100L)
                }

                val col = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(TextView(context).apply {
                        text = title
                        setTextColor(if (active) Color.WHITE else fg)
                        textSize = 15.5f
                        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    })
                    addView(TextView(context).apply {
                        text = subtitle
                        setTextColor(if (active) argb(0xE2E2E8F0) else subFg)
                        textSize = 13f
                        setPadding(0, dp(2), 0, 0)
                    })
                }
                addView(col, LinearLayout.LayoutParams(0, -2, 1f))
                if (active) {
                    addView(TextView(context).apply {
                        text = "✓"; setTextColor(Color.WHITE); textSize = 17f
                    })
                }
            }
            settingsDetailHost.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        }

        when (activeSettingsTab) {
            0 -> {
                heading("Appearance")
                settingButton("Liquid Glass (Default)", "Translucent frosted tvOS glass surfaces", themeMode == "LIQUID") {
                    themeMode = "LIQUID"; savePref { putString("theme_mode", themeMode) }
                    applyThemeToSettingsCard(); renderSettingsSidebar(focusActiveTab = false); renderSettingsDetail(preserveFocus = true)
                }
                settingButton("Dark Mode", "Deep OLED dark panels with high contrast", themeMode == "DARK") {
                    themeMode = "DARK"; savePref { putString("theme_mode", themeMode) }
                    applyThemeToSettingsCard(); renderSettingsSidebar(focusActiveTab = false); renderSettingsDetail(preserveFocus = true)
                }
                settingButton("Light Mode", "Bright Apple TV light settings theme", themeMode == "LIGHT") {
                    themeMode = "LIGHT"; savePref { putString("theme_mode", themeMode) }
                    applyThemeToSettingsCard(); renderSettingsSidebar(focusActiveTab = false); renderSettingsDetail(preserveFocus = true)
                }
            }
            1 -> {
                heading("Home Screen & Apps")
                val hiddenCount = TvIconComposer.getHiddenPackages(this).size
                settingButton("Restore Hidden Apps ($hiddenCount)", "Unhide any apps hidden via Hold OK menu") {
                    TvIconComposer.unhideAllApps(this)
                    loadAppsAsync()
                    renderSettingsDetail(preserveFocus = true)
                    showGlassHud("All hidden apps restored", Glyph.DOCK)
                }
                settingButton("Refresh App Icons", "Re-compose cached app tiles after updates") {
                    TvIconComposer.clearCache()
                    loadAppsAsync()
                    showGlassHud("App icons refreshed", Glyph.SYNC)
                }
            }
            2 -> {
                heading("4K Wallpapers")
                if (wallpaperServer == null) {
                    wallpaperServer = WallpaperServer(this) {
                        if (isFinishing || isDestroyed) return@WallpaperServer
                        loadWallpaper()
                        showGlassHud("Wallpaper updated", Glyph.WALLPAPER)
                        if (settingsOverlay.visibility == View.VISIBLE && activeSettingsTab == 2) {
                            renderSettingsDetail(preserveFocus = true)
                        }
                    }
                }
                val url = wallpaperServer!!.start(8080)
                settingsDetailHost.addView(TextView(this).apply {
                    text = "Local Wi-Fi Wallpaper Upload URL (open on your phone browser):\n$url"
                    setTextColor(if (isLightTheme()) Color.parseColor("#0284C7") else Color.parseColor("#7DD3FC"))
                    textSize = 14f
                    setPadding(dp(14), dp(12), dp(14), dp(12))
                    background = glassPill(boxBg, argb(0x30FFFFFF), dp(14))
                }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })

                val hScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(4), 0, dp(12)) }
                val activeId = WallpaperServer.getSelectedWallpaperId(this)
                val builtIns = WallpaperServer.listBuiltInWallpapers(this)

                builtIns.forEachIndexed { i, name ->
                    val id = "builtin:$name"
                    val thumb = createWallpaperThumbCard("Built-in ${i + 1}", activeId == id, fg) {
                        WallpaperServer.setSelectedWallpaperId(this, id)
                        loadWallpaper()
                        renderSettingsDetail(preserveFocus = true)
                    }
                    row.addView(thumb.first)
                    io.execute {
                        val bmp = WallpaperServer.loadAssetThumbnail(this, name)
                        ui.post { if (!isFinishing && !isDestroyed && bmp != null) thumb.second.setImageBitmap(bmp) }
                    }
                }
                if (WallpaperServer.hasCustomWallpaper(this)) {
                    val thumb = createWallpaperThumbCard("Custom", activeId == "custom", fg) {
                        WallpaperServer.setSelectedWallpaperId(this, "custom")
                        loadWallpaper()
                        renderSettingsDetail(preserveFocus = true)
                    }
                    row.addView(thumb.first)
                    io.execute {
                        val bmp = WallpaperServer.loadCustomThumbnail(this)
                        ui.post { if (!isFinishing && !isDestroyed && bmp != null) thumb.second.setImageBitmap(bmp) }
                    }
                }
                hScroll.addView(row)
                settingsDetailHost.addView(hScroll, LinearLayout.LayoutParams(-1, -2))

                settingButton("Pick Image from Device Storage", "Select any WebP/JPG/PNG directly from your TV or phone") {
                    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "image/*"
                    }
                    @Suppress("DEPRECATION")
                    startActivityForResult(intent, REQ_PICK_WALLPAPER)
                }
            }
            3 -> {
                heading("Wi-Fi Networks")
                val topRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                val tBtn = makePillAction(if (SystemMonitor.isWifiEnabled(this)) "On" else "Off", argb(0xFF2563EB), Color.WHITE) {
                    handleWifiShortToggle()
                    ui.postDelayed({ renderSettingsDetail(preserveFocus = true) }, 700L)
                }
                val sBtn = makePillAction("Scan Nearby", argb(0x40FFFFFF), fg) { renderSettingsDetail(preserveFocus = true) }
                topRow.addView(tBtn)
                topRow.addView(sBtn, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(10) })
                settingsDetailHost.addView(topRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })

                val statusTv = TextView(this).apply { setTextColor(subFg); textSize = 13.5f; setPadding(0, 0, 0, dp(8)) }
                settingsDetailHost.addView(statusTv)
                val listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                settingsDetailHost.addView(listBox)
                renderWifiList(listBox, tBtn, statusTv)
            }
            4 -> {
                heading("Bluetooth Devices")
                startBluetoothReceiver()
                if (!preserveFocus) startBluetoothScanInApp()
                val topRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                val tBtn = makePillAction(if (SystemMonitor.isBluetoothEnabled(this)) "On" else "Off", argb(0xFF2563EB), Color.WHITE) {
                    handleBluetoothShortToggle()
                    ui.postDelayed({ renderSettingsDetail(preserveFocus = true) }, 700L)
                }
                val sBtn = makePillAction("Scan Nearby", argb(0x40FFFFFF), fg) {
                    discoveredBtDevices.clear(); startBluetoothScanInApp(); renderSettingsDetail(preserveFocus = true)
                }
                topRow.addView(tBtn)
                topRow.addView(sBtn, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(10) })
                settingsDetailHost.addView(topRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })

                val listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                settingsDetailHost.addView(listBox)
                renderBluetoothList(listBox, tBtn)
            }
            5 -> {
                heading("Weather & Clock")
                val cityInput = EditText(this).apply {
                    hint = "City name (e.g. Raebareli, Lucknow, Mumbai)"
                    setText(SystemMonitor.getSavedCityName(this@MainActivity))
                    setTextColor(fg)
                    setHintTextColor(subFg)
                    textSize = 15f
                    setPadding(dp(14), dp(12), dp(14), dp(12))
                    background = glassPill(boxBg, argb(0x40FFFFFF), dp(14))
                }
                settingsDetailHost.addView(cityInput, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })

                settingButton("Update Weather Location", "Fetches live hourly & 5-day Open-Meteo forecast") {
                    val q = cityInput.text?.toString().orEmpty()
                    showGlassHud("Searching $q...", Glyph.SUN)
                    io.execute {
                        val (ok, msg) = SystemMonitor.searchAndSaveCity(this, q)
                        ui.post {
                            if (isFinishing || isDestroyed) return@post
                            if (ok) {
                                showGlassHud("Weather city: $msg", Glyph.SUN)
                                refreshWeatherAsync()
                            } else {
                                showGlassHud(msg, Glyph.SUN)
                            }
                        }
                    }
                }
                settingButton("24-Hour Clock Format", if (use24HourClock) "Using 24-hour time (15:30)" else "Using 12-hour time (3:30 PM)", use24HourClock) {
                    use24HourClock = !use24HourClock
                    savePref { putBoolean("use_24h_clock", use24HourClock) }
                    lastTimeText = ""
                    updateClockDisplay()
                    renderSettingsDetail(preserveFocus = true)
                }
            }
            6 -> {
                heading("System")
                val ram = SystemMonitor.getRamInfo(this)
                val freeGb = String.format(Locale.US, "%.2f", ram.freeMb / 1024f)
                val totalGb = String.format(Locale.US, "%.2f", ram.totalMb / 1024f)
                settingsDetailHost.addView(TextView(this).apply {
                    text = "Device Memory: $freeGb GB available of $totalGb GB (managed automatically by Android TV)"
                    setTextColor(subFg)
                    textSize = 13.5f
                    setPadding(dp(4), 0, dp(4), dp(14))
                })
                settingButton("Set as Default Home Launcher", "Open Android Home App selector") {
                    try { startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) }
                    catch (_: Exception) { startActivity(Intent(Settings.ACTION_SETTINGS)) }
                }
                settingButton("Open Android System Settings", "Access device display, sound, network & storage") {
                    startActivity(Intent(Settings.ACTION_SETTINGS))
                }
            }
        }

        if (preserveFocus && savedFocusIdx >= 0) {
            restoreFocusableIndex(settingsDetailHost, savedFocusIdx)
        }
    }

    private fun createWallpaperThumbCard(label: String, active: Boolean, textColor: Int, onClick: () -> Unit): Pair<LinearLayout, ImageView> {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(6), dp(4), dp(6), dp(4))
            isFocusable = true
            isClickable = true
            setOnClickListener { onClick() }
        }
        val img = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = glassPill(argb(0xFF1C2434), 0, dp(12))
            clipToOutline = true
            outlineProvider = roundedOutline(dp(12))
            foreground = if (active) glassPill(0, Color.WHITE, dp(12), dp(3)) else null
        }
        val tv = TextView(this).apply {
            text = if (active) "✓ $label" else label
            setTextColor(textColor)
            textSize = 13f
            setPadding(0, dp(6), 0, 0)
        }
        box.setOnFocusChangeListener { v, hasFocus ->
            img.foreground = when {
                hasFocus -> glassPill(0, Color.parseColor("#38BDF8"), dp(12), dp(3))
                active -> glassPill(0, Color.WHITE, dp(12), dp(3))
                else -> null
            }
            TvMemoryManager.animateTvFocus(v, hasFocus, focusedScale = 1.06f, durationMs = 110L)
        }
        box.addView(img, LinearLayout.LayoutParams(dp(132), dp(74)))
        box.addView(tv)
        return Pair(box, img)
    }

    // =========================================================================
    // LAYER 6: MODALS (WI-FI PASSWORD, APP LONG-PRESS MENU, STANDBY CLOCK)
    // =========================================================================
    private fun buildWifiPasswordModal() {
        wifiPassOverlay = FrameLayout(this).apply {
            setBackgroundColor(argb(0xC8060A12))
            visibility = View.GONE
            isClickable = true
            isFocusable = false
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            elevation = dp(32).toFloat()
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(22), dp(24), dp(22))
            background = liquidGlass(tintColor = argb(0xCC101828), cornerRadiusPx = dp(24).toFloat())
            isFocusable = false
        }
        wifiPassTitle = TextView(this).apply {
            text = "Join Wi-Fi"; setTextColor(Color.WHITE); textSize = 18f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        card.addView(wifiPassTitle)
        wifiPassInput = EditText(this).apply {
            id = View.generateViewId()
            hint = "Enter Password"; setHintTextColor(argb(0x99C8D2E6)); setTextColor(Color.WHITE); textSize = 15.5f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = glassPill(argb(0x45FFFFFF), argb(0x70FFFFFF), dp(14))
        }
        card.addView(wifiPassInput, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14); bottomMargin = dp(16) })

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        val cancelBtn = makePillAction("Cancel", argb(0x35FFFFFF), Color.WHITE) {
            closeWifiPasswordModal()
        }.apply { id = View.generateViewId() }
        val connectBtn = makePillAction("Connect", argb(0xFFF08A1A), Color.WHITE) {
            val net = pendingWifiNet ?: return@makePillAction
            val pw = wifiPassInput.text?.toString().orEmpty()
            closeWifiPasswordModal()
            connectWifiInApp(net, pw)
        }.apply { id = View.generateViewId() }

        wifiPassInput.nextFocusDownId = connectBtn.id
        cancelBtn.nextFocusUpId = wifiPassInput.id
        cancelBtn.nextFocusRightId = connectBtn.id
        connectBtn.nextFocusUpId = wifiPassInput.id
        connectBtn.nextFocusLeftId = cancelBtn.id

        row.addView(cancelBtn)
        row.addView(connectBtn, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(10) })
        card.addView(row)
        wifiPassOverlay.addView(card, FrameLayout.LayoutParams(dp(380), -2, Gravity.CENTER))
        rootFrame.addView(wifiPassOverlay, FrameLayout.LayoutParams(-1, -1))
    }

    private fun openWifiPasswordModal(net: WifiNet) {
        pendingWifiNet = net
        wifiPassTitle.text = "Join \"${net.ssid}\""
        wifiPassInput.setText("")
        wifiPassOverlay.visibility = View.VISIBLE
        syncLayerFocusability()
        wifiPassInput.post { wifiPassInput.requestFocus() }
    }

    private fun closeWifiPasswordModal() {
        wifiPassOverlay.visibility = View.GONE
        syncLayerFocusability()
        when {
            ccWifiPage.visibility == View.VISIBLE -> ccWifiBackBtn.post { ccWifiBackBtn.requestFocus() }
            settingsOverlay.visibility == View.VISIBLE -> renderSettingsSidebar(focusActiveTab = true)
            else -> restoreHomeFocus()
        }
    }

    private fun connectWifiInApp(net: WifiNet, password: String?) {
        showGlassHud("Connecting to ${net.ssid}...", Glyph.WIFI)
        SystemMonitor.connectToWifi(this, net.ssid, password, net.secured) { _, msg ->
            ui.post {
                if (isFinishing || isDestroyed) return@post
                showGlassHud(msg, Glyph.WIFI)
                refreshLiveConnectivity()
                if (ccWifiPage.visibility == View.VISIBLE) renderWifiList(wifiListContainer, wifiMenuToggleBtn, wifiMenuStatusText)
            }
        }
    }

    private fun buildAppMenuModal() {
        appMenuOverlay = FrameLayout(this).apply {
            setBackgroundColor(argb(0xBC060A14))
            visibility = View.GONE
            isClickable = true
            isFocusable = false
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            elevation = dp(32).toFloat()
            setOnClickListener { closeAppMenuModal() }
        }
        appMenuCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(20), dp(22), dp(20))
            background = liquidGlass(tintColor = argb(0xCC101828), cornerRadiusPx = dp(24).toFloat())
            isClickable = true
            isFocusable = false
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }
        appMenuOverlay.addView(appMenuCard, FrameLayout.LayoutParams(dp(330), -2, Gravity.CENTER))
        rootFrame.addView(appMenuOverlay, FrameLayout.LayoutParams(-1, -1))
    }

    private fun closeAppMenuModal() {
        appMenuOverlay.visibility = View.GONE
        syncLayerFocusability()
        restoreHomeFocus()
    }

    private fun openAppActionMenu(app: AppItem) {
        lastFocusedHomePkg = app.packageName
        appMenuCard.removeAllViews()
        appMenuCard.addView(TextView(this).apply {
            text = app.label; setTextColor(Color.WHITE); textSize = 18.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setPadding(dp(4), 0, dp(4), dp(12))
        })

        val menuRows = mutableListOf<LinearLayout>()
        fun addMenuItem(label: String, onClick: () -> Unit) {
            val row = buildAppleListRow(Glyph.DOCK, label, "", "", {
                closeAppMenuModal()
                onClick()
            }, null).apply {
                id = View.generateViewId()
            }
            menuRows.add(row)
            appMenuCard.addView(row)
        }

        addMenuItem("Open App") { launchApp(app.packageName) }
        addMenuItem("Pin to Front of Dock") {
            TvIconComposer.pinAppToFront(this, app.packageName)
            loadAppsAsync()
            showGlassHud("Pinned ${app.label}", Glyph.DOCK)
        }
        addMenuItem("Move Left") {
            TvIconComposer.moveAppInOrder(this, installedApps, app.packageName, -1)
            loadAppsAsync()
        }
        addMenuItem("Move Right") {
            TvIconComposer.moveAppInOrder(this, installedApps, app.packageName, +1)
            loadAppsAsync()
        }
        addMenuItem("App Info") {
            try {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.packageName}")))
            } catch (_: Exception) {}
        }
        addMenuItem("Hide App") {
            TvIconComposer.toggleHideApp(this, app.packageName)
            loadAppsAsync()
            showGlassHud("Hidden ${app.label} (restore in Settings)", Glyph.DOCK)
        }

        // Explicitly link every row up/down and lock left/right so TV remote stays cleanly inside the modal
        menuRows.forEachIndexed { idx, row ->
            row.nextFocusLeftId = row.id
            row.nextFocusRightId = row.id
            row.nextFocusUpId = if (idx > 0) menuRows[idx - 1].id else menuRows.last().id
            row.nextFocusDownId = if (idx < menuRows.lastIndex) menuRows[idx + 1].id else menuRows.first().id
        }

        appMenuOverlay.visibility = View.VISIBLE
        syncLayerFocusability()
        menuRows.firstOrNull()?.let { firstRow ->
            firstRow.post { firstRow.requestFocus() }
        }
    }

    private fun buildStandbyOverlay() {
        standbyOverlay = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
            isClickable = true
            isFocusable = true
            elevation = dp(34).toFloat()
            setOnClickListener { closeStandbyMode() }
        }
        standbyBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        standbyClockText = TextView(this).apply {
            text = "12:00"
            setTextColor(argb(0xEEFFFFFF))
            textSize = 96f
            fontFeatureSettings = "tnum"
            typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
            gravity = Gravity.CENTER
        }
        standbyDateText = TextView(this).apply {
            text = "Thursday, Nov 19"
            setTextColor(argb(0x99FFFFFF))
            textSize = 22f
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            gravity = Gravity.CENTER
        }
        standbyBox.addView(standbyClockText)
        standbyBox.addView(standbyDateText)
        standbyOverlay.addView(standbyBox, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        rootFrame.addView(standbyOverlay, FrameLayout.LayoutParams(-1, -1))
    }

    private fun advanceStandbyBurnInDrift() {
        standbyDriftStep = (standbyDriftStep + 1) % standbyDriftOffsetsDp.size
        val (dxDp, dyDp) = standbyDriftOffsetsDp[standbyDriftStep]
        standbyBox.animate().cancel()
        standbyBox.animate()
            .translationX(dp(dxDp).toFloat())
            .translationY(dp(dyDp).toFloat())
            .setDuration(600L)
            .start()
    }

    private fun closeStandbyMode() {
        standbyOverlay.visibility = View.GONE
        syncLayerFocusability()
        restoreHomeFocus()
    }

    // =========================================================================
    // STATE, TELEMETRY & REMOTE NAVIGATION
    // =========================================================================
    private fun applyWallpaperBlur(enable: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val shouldLiveBlur = enable && currentBlurred == null
            wallpaperView.setRenderEffect(
                if (shouldLiveBlur) RenderEffect.createBlurEffect(28f, 28f, Shader.TileMode.CLAMP) else null
            )
        }
    }

    private fun openControlCenter() {
        applyWallpaperBlur(true)
        controlOverlay.visibility = View.VISIBLE
        syncLayerFocusability()
        showMainControlPage()
        updateClockDisplay()
    }

    private fun closeControlCenter() {
        stopBluetoothReceiver()
        applyWallpaperBlur(false)
        controlOverlay.visibility = View.GONE
        syncLayerFocusability()
        rightCapsule.post { rightCapsule.requestFocus() }
    }

    private fun showMainControlPage() {
        ccWifiPage.visibility = View.GONE
        ccBtPage.visibility = View.GONE
        ccMainPage.visibility = View.VISIBLE
        updateControlCenterHeaderFocusDown()
        refreshLiveConnectivity()
        standbyTile.post { standbyTile.requestFocus() }
    }

    private fun openStandbyMode() {
        stopBluetoothReceiver()
        applyWallpaperBlur(false)
        controlOverlay.visibility = View.GONE
        standbyBox.translationX = 0f
        standbyBox.translationY = 0f
        standbyDriftStep = 0
        standbyOverlay.visibility = View.VISIBLE
        syncLayerFocusability()
        updateClockDisplay()
        standbyOverlay.post { standbyOverlay.requestFocus() }
    }

    private fun handleWifiShortToggle() {
        when (SystemMonitor.toggleWifi(this)) {
            ToggleResult.TURNED_ON -> {
                showGlassHud("Wi-Fi Turning On", Glyph.WIFI)
                ui.postDelayed({ refreshLiveConnectivity() }, 800L)
            }
            ToggleResult.TURNED_OFF -> {
                showGlassHud("Wi-Fi Turned Off", Glyph.WIFI)
                ui.postDelayed({ refreshLiveConnectivity() }, 600L)
            }
            ToggleResult.SYSTEM_RESTRICTED -> {
                if (SystemMonitor.openSystemWifiSettings(this)) {
                    showGlassHud("Opening System Wi-Fi", Glyph.WIFI)
                } else {
                    openWifiManagerPage()
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleBluetoothShortToggle() {
        when (SystemMonitor.toggleBluetooth(this)) {
            ToggleResult.TURNED_ON -> {
                showGlassHud("Bluetooth Turning On", Glyph.BLUETOOTH)
                ui.postDelayed({ refreshLiveConnectivity() }, 800L)
            }
            ToggleResult.TURNED_OFF -> {
                showGlassHud("Bluetooth Turned Off", Glyph.BLUETOOTH)
                ui.postDelayed({ refreshLiveConnectivity() }, 600L)
            }
            ToggleResult.SYSTEM_RESTRICTED -> {
                if (!SystemMonitor.isBluetoothEnabled(this)) {
                    try {
                        @Suppress("DEPRECATION")
                        startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQ_ENABLE_BT)
                    } catch (_: Exception) {
                        SystemMonitor.openSystemBluetoothSettings(this)
                    }
                } else {
                    SystemMonitor.openSystemBluetoothSettings(this)
                    showGlassHud("Opening Bluetooth Settings", Glyph.BLUETOOTH)
                }
            }
        }
    }

    private fun updateClockDisplay() {
        reusableDate.time = System.currentTimeMillis()

        val timeText = (if (use24HourClock) fmt24h else fmt12h).format(reusableDate).uppercase()
        if (timeText != lastTimeText) {
            lastTimeText = timeText
            clockText.text = timeText
            ccClockText.text = timeText
        }

        if (standbyOverlay.visibility == View.VISIBLE) {
            val sbClock = (if (use24HourClock) fmt24h else fmtStandby12).format(reusableDate)
            if (sbClock != lastStandbyClock) {
                lastStandbyClock = sbClock
                standbyClockText.text = sbClock
            }
            val sbDate = fmtStandbyDate.format(reusableDate)
            if (sbDate != lastStandbyDate) {
                lastStandbyDate = sbDate
                standbyDateText.text = sbDate
            }
        }
    }

    private fun refreshLiveConnectivity() {
        val vol = SystemMonitor.getVolumePercent(this)
        volumeSlider.progress = vol
        volumePctText.text = "$vol%"
        io.execute {
            val wifiOn = SystemMonitor.isWifiEnabled(this)
            val wifiText = SystemMonitor.getWifiStatus(this)
            val btOn = SystemMonitor.isBluetoothEnabled(this)
            val btText = SystemMonitor.getBluetoothStatus(this)
            ui.post {
                if (isFinishing || isDestroyed) return@post
                wifiSubText.text = wifiText
                (wifiTileBox.background as? LiquidGlassDrawable)?.setTintColor(
                    if (wifiOn) argb(0x4A2563EB) else argb(0x280C1424)
                )
                btSubText.text = btText
                (btTileBox.background as? LiquidGlassDrawable)?.setTintColor(
                    if (btOn) argb(0x4A2563EB) else argb(0x280C1424)
                )
            }
        }
    }

    private fun refreshWeatherAsync(updatePanel: Boolean = false) {
        io.execute {
            val report = SystemMonitor.fetchFullWeather(this)
            ui.post {
                if (isFinishing || isDestroyed) return@post
                if (report != null) {
                    val conditionLabel = formatWeatherConditionLabel(report)
                    weatherPillIcon.text = SystemMonitor.weatherCodeToEmoji(report.code)
                    weatherPillText.text = "${report.city} · ${report.tempC}° $conditionLabel"
                    if (updatePanel && weatherOverlay.visibility == View.VISIBLE) {
                        renderWeatherPanel(report, requestButtonFocus = false)
                    }
                }
            }
        }
    }

    private fun loadAppsAsync() {
        io.execute {
            val apps = TvIconComposer.loadInstalledApps(this)
            apps.take(dockColumns).forEach { TvIconComposer.getOrComposeCard(this, it.packageName) }
            ui.post {
                if (isFinishing || isDestroyed) return@post
                val hadHomeFocus = !anyOverlayVisible() && (homeRecycler.hasFocus() || currentFocus == null)
                installedApps = apps
                homeAdapter.updateApps(apps)
                if (!hasSetInitialFocus && apps.isNotEmpty() && !anyOverlayVisible()) {
                    hasSetInitialFocus = true
                    focusDockTile(0)
                } else if (hadHomeFocus && apps.isNotEmpty()) {
                    restoreHomeFocus()
                }
            }
        }
    }

    private fun anyOverlayVisible(): Boolean {
        return weatherOverlay.visibility == View.VISIBLE ||
            controlOverlay.visibility == View.VISIBLE ||
            settingsOverlay.visibility == View.VISIBLE ||
            wifiPassOverlay.visibility == View.VISIBLE ||
            appMenuOverlay.visibility == View.VISIBLE ||
            standbyOverlay.visibility == View.VISIBLE
    }

    private fun launchApp(pkg: String) {
        val intent = packageManager.getLeanbackLaunchIntentForPackage(pkg)
            ?: packageManager.getLaunchIntentForPackage(pkg)
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } else {
            showGlassHud("Cannot launch app", Glyph.DOCK)
        }
    }

    @Deprecated("Native Activity result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK_WALLPAPER && resultCode == RESULT_OK) {
            data?.data?.let { uri ->
                io.execute {
                    if (WallpaperServer.saveFromUri(this, uri)) {
                        ui.post {
                            if (isFinishing || isDestroyed) return@post
                            loadWallpaper()
                            if (settingsOverlay.visibility == View.VISIBLE) renderSettingsDetail(preserveFocus = true)
                            showGlassHud("Wallpaper applied", Glyph.WALLPAPER)
                        }
                    }
                }
            }
        } else if (requestCode == REQ_ENABLE_BT) {
            refreshLiveConnectivity()
            if (ccBtPage.visibility == View.VISIBLE) renderBluetoothList(btListContainer, btMenuToggleBtn)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            if (controlOverlay.visibility == View.VISIBLE) closeControlCenter() else openControlCenter()
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            when {
                wifiPassOverlay.visibility == View.VISIBLE -> { closeWifiPasswordModal(); return true }
                appMenuOverlay.visibility == View.VISIBLE -> { closeAppMenuModal(); return true }
                standbyOverlay.visibility == View.VISIBLE -> { closeStandbyMode(); return true }
                weatherOverlay.visibility == View.VISIBLE -> { closeWeatherPanel(); return true }
                settingsOverlay.visibility == View.VISIBLE -> { closeSettingsOverlay(); return true }
                ccWifiPage.visibility == View.VISIBLE || ccBtPage.visibility == View.VISIBLE -> {
                    stopBluetoothReceiver(); showMainControlPage(); return true
                }
                controlOverlay.visibility == View.VISIBLE -> { closeControlCenter(); return true }
                else -> {
                    homeRecycler.smoothScrollToPosition(0)
                    focusDockTile(0)
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    // =========================================================================
    // APPLE TV HOME ADAPTER (LIQUID GLASS 6-TILE DOCK + APP GRID)
    // =========================================================================
    inner class AppleTvHomeAdapter(private var allApps: List<AppItem>) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private val TYPE_SPACER = 0
        private val TYPE_TOP_SHELF = 1
        private val TYPE_GRID_APP = 2

        private val screenW get() = resources.displayMetrics.widthPixels
        private val screenH get() = resources.displayMetrics.heightPixels

        private val tileHeightPx: Int
            get() {
                // Respect 48dp left + 48dp right safe-area padding + dock capsule padding
                val usableW = screenW - dp(128)
                val tileW = (usableW / dockColumns).coerceAtLeast(dp(56))
                return (tileW * 0.59f).toInt().coerceIn(dp(46), dp(106))
            }

        fun updateApps(newItems: List<AppItem>) {
            allApps = newItems
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int {
            if (allApps.isEmpty()) return 0
            val gridCount = (allApps.size - dockColumns).coerceAtLeast(0)
            return 2 + gridCount
        }

        override fun getItemViewType(position: Int): Int = when (position) {
            0 -> TYPE_SPACER
            1 -> TYPE_TOP_SHELF
            else -> TYPE_GRID_APP
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return when (viewType) {
                TYPE_SPACER -> {
                    val dockTotal = tileHeightPx + dp(58)
                    val bottomSafeClearance = dp(32)
                    val spacerH = (screenH - dockTotal - bottomSafeClearance).coerceAtLeast(dp(115))
                    val v = View(parent.context).apply {
                        isFocusable = false
                        layoutParams = RecyclerView.LayoutParams(-1, spacerH)
                    }
                    object : RecyclerView.ViewHolder(v) {}
                }
                TYPE_TOP_SHELF -> {
                    val wrapper = LinearLayout(parent.context).apply {
                        orientation = LinearLayout.VERTICAL
                        clipChildren = false
                        clipToPadding = false
                        layoutParams = RecyclerView.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
                    }
                    val frostedCapsule = LinearLayout(parent.context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(12), dp(12), dp(12), dp(12))
                        background = liquidGlass(tintColor = argb(0x2AFFFFFF), cornerRadiusPx = dp(28).toFloat())
                        clipChildren = false
                        clipToPadding = false
                    }
                    val focusedLabel = TextView(parent.context).apply {
                        text = ""
                        setTextColor(Color.WHITE)
                        textSize = 14.5f
                        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                        gravity = Gravity.CENTER
                        setShadowLayer(5f, 0f, 1.5f, argb(0xCC000000))
                        alpha = 0f
                    }
                    wrapper.addView(frostedCapsule, LinearLayout.LayoutParams(-1, -2))
                    wrapper.addView(focusedLabel, LinearLayout.LayoutParams(-1, dp(26)).apply { topMargin = dp(6) })
                    TopShelfVH(wrapper, frostedCapsule, focusedLabel)
                }
                else -> GridAppVH(buildGridTile(parent.context))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (holder) {
                is TopShelfVH -> {
                    holder.row.removeAllViews()
                    val topApps = allApps.take(dockColumns)
                    val dockTiles = mutableListOf<FrameLayout>()
                    topApps.forEachIndexed { idx, app ->
                        val tile = buildDockTile(holder.row.context, app, idx, holder.row, holder.focusedLabel)
                        dockTiles.add(tile)
                        holder.row.addView(tile, LinearLayout.LayoutParams(0, -2, 1f))
                    }
                    // Lock horizontal D-pad flow across dock tiles
                    dockTiles.forEachIndexed { idx, tile ->
                        tile.nextFocusLeftId = if (idx > 0) dockTiles[idx - 1].id else tile.id
                        tile.nextFocusRightId = if (idx < dockTiles.lastIndex) dockTiles[idx + 1].id else tile.id
                    }
                    for (i in topApps.size until dockColumns) {
                        holder.row.addView(View(holder.row.context).apply { isFocusable = false }, LinearLayout.LayoutParams(0, 1, 1f))
                    }
                }
                is GridAppVH -> {
                    val appIdx = position - 2 + dockColumns
                    if (appIdx in allApps.indices) bindGridTile(holder.tile, allApps[appIdx], appIdx)
                }
            }
        }

        private fun buildDockTile(
            ctx: Context,
            app: AppItem,
            dockIndex: Int,
            dockRow: ViewGroup,
            sharedDockLabel: TextView
        ): FrameLayout {
            val pad = dp(6)
            val cornerPx = dp(16)

            val container = FrameLayout(ctx).apply {
                id = View.generateViewId()
                setPadding(pad, 0, pad, 0)
                isFocusable = true
                isClickable = true
                isLongClickable = true
                clipChildren = false
                clipToPadding = false
                setOnClickListener { launchApp(app.packageName) }
                setOnLongClickListener { openAppActionMenu(app); true }
                // Explicitly jump to the top bar when pressing D-pad UP from the dock
                setOnKeyListener { _, keyCode, event ->
                    if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                        focusTopBarForDockIndex(dockIndex)
                        true
                    } else {
                        false
                    }
                }
            }

            val img = ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                clipToOutline = true
                outlineProvider = roundedOutline(cornerPx)
                elevation = dp(4).toFloat()
                tag = app.packageName
            }
            val border = View(ctx).apply {
                background = glassPill(0, Color.WHITE, cornerPx, dp(3))
                alpha = 0f
                elevation = dp(6).toFloat()
            }
            container.addView(img, FrameLayout.LayoutParams(-1, tileHeightPx))
            container.addView(border, FrameLayout.LayoutParams(-1, tileHeightPx))

            loadCardInto(img, app.packageName)

            container.setOnFocusChangeListener { v, hasFocus ->
                if (hasFocus) lastFocusedHomePkg = app.packageName
                TvMemoryManager.animateTvFocus(
                    view = v,
                    hasFocus = hasFocus,
                    focusedScale = 1.10f,
                    focusedTranslationZ = dp(12).toFloat(),
                    durationMs = 125L
                )
                TvMemoryManager.animateAlpha(border, if (hasFocus) 1f else 0f, 100L)
                if (hasFocus) {
                    sharedDockLabel.text = app.label
                    TvMemoryManager.animateAlpha(sharedDockLabel, 1f, 110L)
                } else {
                    v.post {
                        if (!dockRow.hasFocus()) {
                            TvMemoryManager.animateAlpha(sharedDockLabel, 0f, 95L)
                        }
                    }
                }
            }
            return container
        }

        private fun buildGridTile(ctx: Context): GridTileViews {
            val pad = dp(8)
            val cornerPx = dp(16)
            val col = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(pad, dp(6), pad, dp(6))
                isFocusable = true
                isClickable = true
                isLongClickable = true
                clipChildren = false
                clipToPadding = false
            }
            val frame = FrameLayout(ctx).apply { clipChildren = false; clipToPadding = false }
            val img = ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                clipToOutline = true
                outlineProvider = roundedOutline(cornerPx)
                elevation = dp(4).toFloat()
            }
            val border = View(ctx).apply {
                background = glassPill(0, Color.WHITE, cornerPx, dp(3))
                alpha = 0f
                elevation = dp(6).toFloat()
            }
            frame.addView(img, FrameLayout.LayoutParams(-1, tileHeightPx))
            frame.addView(border, FrameLayout.LayoutParams(-1, tileHeightPx))
            col.addView(frame, LinearLayout.LayoutParams(-1, tileHeightPx))

            val label = TextView(ctx).apply {
                setTextColor(Color.WHITE)
                textSize = 14f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.CENTER
                setPadding(0, dp(6), 0, 0)
                setShadowLayer(4f, 0f, 1.5f, argb(0xCC000000))
                alpha = 0f
                visibility = View.INVISIBLE
            }
            col.addView(label, LinearLayout.LayoutParams(-1, -2))

            col.setOnFocusChangeListener { v, hasFocus ->
                if (hasFocus) {
                    (img.tag as? String)?.let { lastFocusedHomePkg = it }
                }
                TvMemoryManager.animateTvFocus(
                    view = v,
                    hasFocus = hasFocus,
                    focusedScale = 1.10f,
                    focusedTranslationZ = dp(12).toFloat(),
                    durationMs = 125L
                )
                TvMemoryManager.animateAlpha(border, if (hasFocus) 1f else 0f, 100L)
                if (hasFocus) {
                    label.visibility = View.VISIBLE
                    TvMemoryManager.animateAlpha(label, 1f, 110L)
                } else {
                    TvMemoryManager.animateAlpha(label, 0f, 95L) {
                        if (!v.isFocused) label.visibility = View.INVISIBLE
                    }
                }
            }
            return GridTileViews(col, img, label)
        }

        private fun bindGridTile(tile: GridTileViews, app: AppItem, appIdx: Int) {
            tile.label.text = app.label
            tile.img.tag = app.packageName
            tile.root.setOnClickListener { launchApp(app.packageName) }
            tile.root.setOnLongClickListener { openAppActionMenu(app); true }
            // Pressing UP from the first grid row smoothly returns to the matching dock column
            val isFirstGridRow = appIdx in dockColumns until (dockColumns * 2)
            tile.root.setOnKeyListener(if (isFirstGridRow) {
                View.OnKeyListener { _, keyCode, event ->
                    if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                        focusDockTile(appIdx - dockColumns)
                        true
                    } else {
                        false
                    }
                }
            } else {
                null
            })
            loadCardInto(tile.img, app.packageName)
        }

        private fun loadCardInto(img: ImageView, pkg: String) {
            val cached = TvIconComposer.getCachedCard(pkg)
            if (cached != null && !cached.isRecycled) {
                img.setImageBitmap(cached)
            } else {
                img.setImageDrawable(glassPill(argb(0x38202838), 0, dp(16)))
                io.execute {
                    val bmp = TvIconComposer.getOrComposeCard(this@MainActivity, pkg)
                    ui.post {
                        if (!isFinishing && !isDestroyed && img.tag == pkg && !bmp.isRecycled) {
                            img.setImageBitmap(bmp)
                        }
                    }
                }
            }
        }

        inner class GridTileViews(val root: LinearLayout, val img: ImageView, val label: TextView)
        inner class TopShelfVH(root: View, val row: LinearLayout, val focusedLabel: TextView) : RecyclerView.ViewHolder(root)
        inner class GridAppVH(val tile: GridTileViews) : RecyclerView.ViewHolder(tile.root)
    }

    // =========================================================================
    // LIQUID GLASS UI HELPERS, VOLUME BAR & ZERO-ALLOCATION VECTORS
    // =========================================================================
    private fun buildToggleableTile(
        glyph: Glyph, title: String, subtitle: String,
        onShortClick: () -> Unit, onLongPress: () -> Unit
    ): Pair<LinearLayout, TextView> {
        val subTv = TextView(this).apply {
            text = subtitle
            setTextColor(argb(0xE8F0F6FF))
            textSize = 13f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setShadowLayer(3f, 0f, 1f, argb(0x70000000))
        }
        val tile = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(8), dp(14), dp(8))
            background = liquidGlass(tintColor = argb(0x280C1424), isPill = true)
            isFocusable = true; isClickable = true; isLongClickable = true
            setOnClickListener { onShortClick() }
            setOnLongClickListener { onLongPress(); true }
            attachGlassInteractive(this, 1.05f)

            addView(VectorIconView(context, glyph, Color.WHITE), LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(12) })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(context).apply {
                    text = title
                    setTextColor(Color.WHITE)
                    textSize = 15f
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    setShadowLayer(3f, 0f, 1f, argb(0x75000000))
                })
                addView(subTv)
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        return Pair(tile, subTv)
    }

    private fun buildAppleListRow(
        glyph: Glyph, title: String, subtitle: String, badge: String,
        onClick: () -> Unit, onLongClick: (() -> Unit)?
    ): LinearLayout {
        val iconView = VectorIconView(this, glyph, Color.WHITE)
        val titleTv = TextView(this).apply {
            text = title; setTextColor(Color.WHITE); textSize = 15f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }
        val subTv = if (subtitle.isNotEmpty()) TextView(this).apply {
            text = subtitle; setTextColor(argb(0xCCE2E8F0)); textSize = 12.5f
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        } else null

        val restingBg = glassPill(argb(0x22FFFFFF), argb(0x28FFFFFF), dp(14))
        val focusedBg = glassPill(argb(0xF5FFFFFF), Color.WHITE, dp(14))

        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(11), dp(14), dp(11))
            background = restingBg
            isFocusable = true; isClickable = true
            setOnClickListener { onClick() }
            if (onLongClick != null) {
                isLongClickable = true
                setOnLongClickListener { onLongClick(); true }
            }

            addView(iconView, LinearLayout.LayoutParams(dp(19), dp(19)).apply { marginEnd = dp(12) })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(titleTv)
                if (subTv != null) addView(subTv)
            }, LinearLayout.LayoutParams(0, -2, 1f))

            if (badge.isNotEmpty()) {
                addView(TextView(context).apply {
                    text = badge; setTextColor(Color.parseColor("#4ADE80")); textSize = 15f; setPadding(dp(8), 0, 0, 0)
                })
            }

            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }

            setOnFocusChangeListener { v, hasFocus ->
                v.background = if (hasFocus) focusedBg else restingBg
                val fg = if (hasFocus) Color.parseColor("#111622") else Color.WHITE
                iconView.setTint(fg)
                titleTv.setTextColor(fg)
                subTv?.setTextColor(if (hasFocus) Color.parseColor("#475569") else argb(0xCCE2E8F0))
            }
        }
    }

    private fun buildRoundAction(glyph: Glyph, label: String, onClick: () -> Unit): Pair<LinearLayout, FrameLayout> {
        val circleBtn = createLiquidGlassCircleBtn(glyph, dp(56), argb(0x320A101C), Color.WHITE, onClick)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            clipChildren = false
            clipToPadding = false
            addView(circleBtn, LinearLayout.LayoutParams(dp(56), dp(56)))
            addView(TextView(context).apply {
                text = label
                setTextColor(argb(0xF0FFFFFF))
                textSize = 12.5f
                gravity = Gravity.CENTER
                setShadowLayer(3f, 0f, 1f, argb(0x88000000))
                setPadding(0, dp(6), 0, 0)
            })
        }
        return Pair(container, circleBtn)
    }

    private fun createLiquidGlassCircleBtn(
        glyph: Glyph,
        sizePx: Int,
        fillColor: Int,
        iconColor: Int,
        onClick: () -> Unit
    ): FrameLayout {
        return FrameLayout(this).apply {
            background = liquidGlass(tintColor = fillColor, isCircle = true)
            val iconSize = (sizePx * 0.44f).toInt()
            addView(VectorIconView(context, glyph, iconColor), FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER))
            isFocusable = true; isClickable = true
            setOnClickListener { onClick() }
            attachGlassInteractive(this, 1.10f)
        }
    }

    private fun makePillAction(label: String, bgColor: Int, textColor: Int, onClick: () -> Unit): TextView {
        val baseBg = glassPill(bgColor, argb(0x50FFFFFF), dp(16))
        val focusBg = glassPill(bgColor, Color.WHITE, dp(16), dp(2))
        return TextView(this).apply {
            text = label; setTextColor(textColor); textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(dp(15), dp(8), dp(15), dp(8))
            background = baseBg
            gravity = Gravity.CENTER
            isFocusable = true; isClickable = true
            setOnClickListener { onClick() }
            setOnFocusChangeListener { v, hasFocus ->
                v.background = if (hasFocus) focusBg else baseBg
                TvMemoryManager.animateTvFocus(v, hasFocus, focusedScale = 1.08f, durationMs = 100L)
            }
        }
    }

    private fun makeSectionHeader(text: String): TextView = TextView(this).apply {
        this.text = text; setTextColor(argb(0xBCC8D7F0)); textSize = 12f
        letterSpacing = 0.08f
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        setPadding(dp(4), dp(10), dp(4), dp(6))
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachGlassInteractive(v: View, scale: Float) {
        v.setOnFocusChangeListener { view, hasFocus ->
            (view.background as? LiquidGlassDrawable)?.setFocusedState(hasFocus)
            TvMemoryManager.animateTvFocus(view, hasFocus, focusedScale = scale, durationMs = 110L)
        }
        v.setOnTouchListener { view, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    view.animate().cancel()
                    view.animate().scaleX(0.95f).scaleY(0.95f).setDuration(70).start()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val target = if (view.isFocused) scale else 1f
                    view.animate().cancel()
                    view.animate().scaleX(target).scaleY(target).setDuration(90).start()
                }
            }
            false
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachInteractiveScale(v: View, scale: Float) {
        v.setOnFocusChangeListener { view, hasFocus ->
            TvMemoryManager.animateTvFocus(view, hasFocus, focusedScale = scale, durationMs = 110L)
        }
        v.setOnTouchListener { view, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    view.animate().cancel()
                    view.animate().scaleX(0.95f).scaleY(0.95f).setDuration(70).start()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val target = if (view.isFocused) scale else 1f
                    view.animate().cancel()
                    view.animate().scaleX(target).scaleY(target).setDuration(90).start()
                }
            }
            false
        }
    }

    private fun roundedOutline(radiusPx: Int) = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            outline.setRoundRect(0, 0, view.width, view.height, radiusPx.toFloat())
        }
    }

    private fun liquidGlass(
        tintColor: Int,
        cornerRadiusPx: Float = dp(20).toFloat(),
        isPill: Boolean = false,
        isCircle: Boolean = false
    ): LiquidGlassDrawable {
        return LiquidGlassDrawable(
            density = resources.displayMetrics.density,
            baseTint = tintColor,
            cornerRadiusPx = cornerRadiusPx,
            isPill = isPill,
            isCircle = isCircle
        )
    }

    private fun glassPill(fillColor: Int, strokeColor: Int, cornerRadiusPx: Int, strokePx: Int = dp(1)): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fillColor)
            cornerRadius = cornerRadiusPx.toFloat()
            if (strokeColor != 0) setStroke(strokePx, strokeColor)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // =========================================================================
    // LIQUID GLASS DRAWABLE (ZERO BITMAPS, 60 FPS GPU)
    // =========================================================================
    private class LiquidGlassDrawable(
        private val density: Float,
        private var baseTint: Int,
        private val cornerRadiusPx: Float,
        private val isPill: Boolean = false,
        private val isCircle: Boolean = false
    ) : Drawable() {

        private var isFocusedState = false
        private val rect = RectF()
        private val strokeRect = RectF()

        private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = baseTint
        }
        private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply {
            style = Paint.Style.FILL
        }
        private val topLipPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply {
            style = Paint.Style.FILL
        }
        private val bottomDepthPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply {
            style = Paint.Style.FILL
        }
        private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply {
            style = Paint.Style.STROKE
        }

        fun setTintColor(newTint: Int) {
            if (baseTint != newTint) {
                baseTint = newTint
                basePaint.color = newTint
                invalidateSelf()
            }
        }

        fun setFocusedState(focused: Boolean) {
            if (isFocusedState != focused) {
                isFocusedState = focused
                rebuildShaders(bounds)
                invalidateSelf()
            }
        }

        override fun onBoundsChange(bounds: Rect) {
            super.onBoundsChange(bounds)
            rebuildShaders(bounds)
        }

        private fun rebuildShaders(b: Rect) {
            if (b.isEmpty) return
            val h = b.height().toFloat()
            rect.set(b)

            val strokeW = (if (isFocusedState) 2.4f else 1.45f) * density
            rimPaint.strokeWidth = strokeW
            strokeRect.set(
                b.left + strokeW * 0.5f,
                b.top + strokeW * 0.5f,
                b.right - strokeW * 0.5f,
                b.bottom - strokeW * 0.5f
            )

            val sheenPeak = if (isFocusedState) 0x60 else 0x36
            val sheenMid = if (isFocusedState) 0x22 else 0x0A
            val sheenBounce = if (isFocusedState) 0x3A else 0x20
            sheenPaint.shader = LinearGradient(
                rect.left, rect.top, rect.right, rect.bottom,
                intArrayOf(
                    Color.argb(sheenPeak, 255, 255, 255),
                    Color.argb(sheenMid, 255, 255, 255),
                    Color.argb(0x05, 255, 255, 255),
                    Color.argb(sheenBounce, 255, 255, 255)
                ),
                floatArrayOf(0f, 0.36f, 0.72f, 1f),
                Shader.TileMode.CLAMP
            )

            topLipPaint.shader = LinearGradient(
                0f, rect.top, 0f, rect.top + h * 0.28f,
                intArrayOf(
                    Color.argb(if (isFocusedState) 0x48 else 0x2C, 255, 255, 255),
                    Color.TRANSPARENT
                ),
                null,
                Shader.TileMode.CLAMP
            )

            bottomDepthPaint.shader = LinearGradient(
                0f, rect.top + h * 0.65f, 0f, rect.bottom,
                intArrayOf(Color.TRANSPARENT, Color.argb(0x28, 0, 0, 0)),
                null,
                Shader.TileMode.CLAMP
            )

            rimPaint.shader = if (isFocusedState) {
                LinearGradient(
                    strokeRect.left, strokeRect.top, strokeRect.right, strokeRect.bottom,
                    intArrayOf(
                        Color.argb(0xFF, 255, 255, 255),
                        Color.argb(0xD8, 255, 255, 255),
                        Color.argb(0xFF, 255, 255, 255)
                    ),
                    floatArrayOf(0f, 0.5f, 1f),
                    Shader.TileMode.CLAMP
                )
            } else {
                LinearGradient(
                    strokeRect.left, strokeRect.top, strokeRect.right, strokeRect.bottom,
                    intArrayOf(
                        Color.argb(0xEB, 255, 255, 255),
                        Color.argb(0x36, 255, 255, 255),
                        Color.argb(0x28, 255, 255, 255),
                        Color.argb(0xB8, 255, 255, 255)
                    ),
                    floatArrayOf(0f, 0.32f, 0.68f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
        }

        override fun draw(canvas: Canvas) {
            if (bounds.isEmpty) return
            val radius = when {
                isCircle -> minOf(rect.width(), rect.height()) * 0.5f
                isPill -> rect.height() * 0.5f
                else -> cornerRadiusPx
            }
            canvas.drawRoundRect(rect, radius, radius, basePaint)
            canvas.drawRoundRect(rect, radius, radius, sheenPaint)
            canvas.drawRoundRect(rect, radius, radius, topLipPaint)
            canvas.drawRoundRect(rect, radius, radius, bottomDepthPaint)

            val strokeRadius = (radius - rimPaint.strokeWidth * 0.5f).coerceAtLeast(0f)
            canvas.drawRoundRect(strokeRect, strokeRadius, strokeRadius, rimPaint)
        }

        override fun setAlpha(alpha: Int) {
            basePaint.alpha = alpha
            sheenPaint.alpha = alpha
            topLipPaint.alpha = alpha
            bottomDepthPaint.alpha = alpha
            rimPaint.alpha = alpha
            invalidateSelf()
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            basePaint.colorFilter = colorFilter
            invalidateSelf()
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    // =========================================================================
    // SLEEK APPLE tvOS VOLUME SLIDER
    // =========================================================================
    inner class LiquidVolumeSeekBar(context: Context) : SeekBar(context) {
        private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = argb(0x52FFFFFF)
            style = Paint.Style.FILL
        }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        private val thumbShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = argb(0x45000000)
            style = Paint.Style.FILL
        }
        private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        private val trackRect = RectF()
        private val fillRect = RectF()

        init {
            progressDrawable = null
            thumb = null
            background = null
            setPadding(dp(7), 0, dp(7), 0)
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return

            val cy = h * 0.5f
            val trackH = dp(6).toFloat()
            val trackR = trackH * 0.5f
            val thumbR = dp(7).toFloat()
            val startX = paddingLeft.toFloat().coerceAtLeast(thumbR)
            val endX = (w - paddingRight.toFloat()).coerceAtMost(w - thumbR)
            val usableW = (endX - startX).coerceAtLeast(1f)

            val fraction = if (max > 0) (progress.toFloat() / max.toFloat()).coerceIn(0f, 1f) else 0f
            val thumbX = startX + usableW * fraction

            trackRect.set(startX - trackR, cy - trackR, endX + trackR, cy + trackR)
            canvas.drawRoundRect(trackRect, trackR, trackR, trackPaint)

            if (fraction > 0f) {
                fillRect.set(startX - trackR, cy - trackR, thumbX, cy + trackR)
                canvas.drawRoundRect(fillRect, trackR, trackR, fillPaint)
            }

            canvas.drawCircle(thumbX, cy + dp(1), thumbR + dp(1), thumbShadowPaint)
            canvas.drawCircle(thumbX, cy, thumbR, thumbPaint)
        }
    }

    // ---------------- ZERO-ALLOCATION SF-STYLE CANVAS VECTORS ----------------
    enum class Glyph {
        WIFI, BLUETOOTH, CLOCK, SETTINGS, CONTROL_SETTINGS,
        SYNC, WALLPAPER, TRIM, VOLUME, USER, CLOSE, SUN, DOCK
    }

    inner class VectorIconView(context: Context, private var glyph: Glyph, color: Int) : View(context) {
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; this.color = color
        }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; this.color = color }
        private val clearKnobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; this.color = Color.WHITE }
        private val tmpRect = RectF()
        private val tmpPath = Path()

        fun setTint(color: Int) { strokePaint.color = color; fillPaint.color = color; invalidate() }

        fun setGlyph(newGlyph: Glyph) {
            if (glyph != newGlyph) {
                glyph = newGlyph
                invalidate()
            }
        }

        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            val cx = w / 2f; val cy = h / 2f; val s = minOf(w, h)
            strokePaint.strokeWidth = (s * 0.095f).coerceAtLeast(2f)

            when (glyph) {
                Glyph.CONTROL_SETTINGS -> {
                    val pillW = s * 0.84f
                    val pillH = s * 0.34f
                    val pillR = pillH * 0.5f
                    val gap = s * 0.12f
                    val topY = cy - gap * 0.5f - pillH
                    val botY = cy + gap * 0.5f
                    val left = cx - pillW * 0.5f
                    val right = cx + pillW * 0.5f
                    strokePaint.strokeWidth = (s * 0.085f).coerceAtLeast(1.8f)

                    tmpRect.set(left, topY, right, topY + pillH)
                    c.drawRoundRect(tmpRect, pillR, pillR, strokePaint)
                    c.drawCircle(left + pillR, topY + pillR, pillR * 0.56f, fillPaint)

                    tmpRect.set(left, botY, right, botY + pillH)
                    c.drawRoundRect(tmpRect, pillR, pillR, fillPaint)
                    c.drawCircle(right - pillR, botY + pillR, pillR * 0.56f, clearKnobPaint)
                }
                Glyph.WIFI -> {
                    c.drawCircle(cx, h * 0.78f, s * 0.08f, fillPaint)
                    tmpRect.set(cx - s * 0.24f, h * 0.52f, cx + s * 0.24f, h * 1.0f)
                    c.drawArc(tmpRect, 225f, 90f, false, strokePaint)
                    tmpRect.set(cx - s * 0.44f, h * 0.28f, cx + s * 0.44f, h * 1.16f)
                    c.drawArc(tmpRect, 225f, 90f, false, strokePaint)
                }
                Glyph.BLUETOOTH -> {
                    tmpPath.reset()
                    tmpPath.moveTo(cx - s * 0.2f, cy - s * 0.2f); tmpPath.lineTo(cx + s * 0.22f, cy + s * 0.2f)
                    tmpPath.lineTo(cx, cy + s * 0.38f); tmpPath.lineTo(cx, cy - s * 0.38f)
                    tmpPath.lineTo(cx + s * 0.22f, cy - s * 0.2f); tmpPath.lineTo(cx - s * 0.2f, cy + s * 0.2f)
                    c.drawPath(tmpPath, strokePaint)
                }
                Glyph.CLOCK -> {
                    strokePaint.strokeWidth = (s * 0.085f).coerceAtLeast(2.2f)
                    c.drawCircle(cx, cy, s * 0.40f, strokePaint)
                    c.drawLine(cx, cy, cx, cy - s * 0.22f, strokePaint)
                    c.drawLine(cx, cy, cx + s * 0.16f, cy + s * 0.10f, strokePaint)
                }
                Glyph.SETTINGS -> {
                    c.drawCircle(cx, cy, s * 0.17f, strokePaint)
                    c.drawCircle(cx, cy, s * 0.34f, strokePaint)
                    for (i in 0 until 6) {
                        val a = Math.toRadians(i * 60.0)
                        c.drawLine(
                            cx + (s * 0.34f * Math.cos(a)).toFloat(), cy + (s * 0.34f * Math.sin(a)).toFloat(),
                            cx + (s * 0.44f * Math.cos(a)).toFloat(), cy + (s * 0.44f * Math.sin(a)).toFloat(), strokePaint
                        )
                    }
                }
                Glyph.SYNC -> {
                    tmpRect.set(cx - s * 0.34f, cy - s * 0.34f, cx + s * 0.34f, cy + s * 0.34f)
                    c.drawArc(tmpRect, 35f, 290f, false, strokePaint)
                    c.drawLine(cx + s * 0.26f, cy - s * 0.32f, cx + s * 0.34f, cy - s * 0.10f, strokePaint)
                    c.drawLine(cx + s * 0.12f, cy - s * 0.14f, cx + s * 0.34f, cy - s * 0.10f, strokePaint)
                }
                Glyph.WALLPAPER -> {
                    tmpRect.set(cx - s * 0.40f, cy - s * 0.30f, cx + s * 0.40f, cy + s * 0.30f)
                    c.drawRoundRect(tmpRect, s * 0.1f, s * 0.1f, strokePaint)
                    c.drawCircle(cx - s * 0.16f, cy - s * 0.08f, s * 0.07f, fillPaint)
                    tmpPath.reset()
                    tmpPath.moveTo(cx - s * 0.32f, cy + s * 0.22f); tmpPath.lineTo(cx - s * 0.04f, cy - s * 0.02f)
                    tmpPath.lineTo(cx + s * 0.12f, cy + s * 0.12f); tmpPath.lineTo(cx + s * 0.24f, cy + s * 0.02f)
                    tmpPath.lineTo(cx + s * 0.34f, cy + s * 0.22f)
                    c.drawPath(tmpPath, strokePaint)
                }
                Glyph.TRIM -> {
                    tmpPath.reset()
                    tmpPath.moveTo(cx, cy - s * 0.38f); tmpPath.lineTo(cx + s * 0.10f, cy - s * 0.10f)
                    tmpPath.lineTo(cx + s * 0.38f, cy); tmpPath.lineTo(cx + s * 0.10f, cy + s * 0.10f)
                    tmpPath.lineTo(cx, cy + s * 0.38f); tmpPath.lineTo(cx - s * 0.10f, cy + s * 0.10f)
                    tmpPath.lineTo(cx - s * 0.38f, cy); tmpPath.lineTo(cx - s * 0.10f, cy - s * 0.10f); tmpPath.close()
                    c.drawPath(tmpPath, fillPaint)
                }
                Glyph.VOLUME -> {
                    tmpPath.reset()
                    tmpPath.moveTo(cx - s * 0.36f, cy - s * 0.12f); tmpPath.lineTo(cx - s * 0.18f, cy - s * 0.12f)
                    tmpPath.lineTo(cx + s * 0.04f, cy - s * 0.30f); tmpPath.lineTo(cx + s * 0.04f, cy + s * 0.30f)
                    tmpPath.lineTo(cx - s * 0.18f, cy + s * 0.12f); tmpPath.lineTo(cx - s * 0.36f, cy + s * 0.12f); tmpPath.close()
                    c.drawPath(tmpPath, fillPaint)
                    tmpRect.set(cx - s * 0.04f, cy - s * 0.22f, cx + s * 0.26f, cy + s * 0.22f)
                    c.drawArc(tmpRect, -45f, 90f, false, strokePaint)
                    tmpRect.set(cx - s * 0.02f, cy - s * 0.36f, cx + s * 0.42f, cy + s * 0.36f)
                    c.drawArc(tmpRect, -45f, 90f, false, strokePaint)
                }
                Glyph.USER -> {
                    c.drawCircle(cx, cy - s * 0.16f, s * 0.16f, fillPaint)
                    tmpRect.set(cx - s * 0.32f, cy + s * 0.04f, cx + s * 0.32f, cy + s * 0.56f)
                    c.drawArc(tmpRect, 180f, 180f, true, fillPaint)
                }
                Glyph.CLOSE -> {
                    val d = s * 0.25f
                    c.drawLine(cx - d, cy - d, cx + d, cy + d, strokePaint)
                    c.drawLine(cx + d, cy - d, cx - d, cy + d, strokePaint)
                }
                Glyph.SUN -> {
                    c.drawCircle(cx, cy, s * 0.21f, fillPaint)
                    for (i in 0 until 8) {
                        val a = Math.toRadians(i * 45.0)
                        c.drawLine(
                            cx + (s * 0.30f * Math.cos(a)).toFloat(), cy + (s * 0.30f * Math.sin(a)).toFloat(),
                            cx + (s * 0.42f * Math.cos(a)).toFloat(), cy + (s * 0.42f * Math.sin(a)).toFloat(), strokePaint
                        )
                    }
                }
                Glyph.DOCK -> {
                    tmpRect.set(cx - s * 0.42f, cy - s * 0.24f, cx + s * 0.42f, cy + s * 0.24f)
                    c.drawRoundRect(tmpRect, s * 0.12f, s * 0.12f, strokePaint)
                    c.drawCircle(cx - s * 0.20f, cy, s * 0.06f, fillPaint)
                    c.drawCircle(cx, cy, s * 0.06f, fillPaint)
                    c.drawCircle(cx + s * 0.20f, cy, s * 0.06f, fillPaint)
                }
            }
        }
    }
}
