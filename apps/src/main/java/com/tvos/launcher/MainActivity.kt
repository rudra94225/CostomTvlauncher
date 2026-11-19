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
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
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

    // User preferences (persisted)
    private var dockColumns = 6              // 6 = Standard, 9 = Compact
    private var themeMode = "LIQUID"         // "DARK", "LIGHT", "LIQUID"
    private var showTopRamPill = true
    private var use24HourClock = false
    private var showExtraCcTiles = true

    // Cached date formatters & state (eliminates GC churn every 2s on 2 GB TVs)
    private val fmt12h = SimpleDateFormat("h:mm a", Locale.getDefault())
    private val fmt24h = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val fmtStandby12 = SimpleDateFormat("h:mm", Locale.getDefault())
    private val fmtStandbyDate = SimpleDateFormat("EEEE, MMM d", Locale.getDefault())
    private val reusableDate = Date()
    private var lastTimeText = ""
    private var lastStandbyClock = ""
    private var lastStandbyDate = ""
    private var lastRamPillText = ""
    private var lastCcRamPercent = -1
    private var lastRamCheckMs = 0L

    // Root & Home Layers
    private lateinit var rootFrame: FrameLayout
    private lateinit var wallpaperView: ImageView
    private lateinit var scrollDimOverlay: View
    private lateinit var homeRecycler: RecyclerView
    private lateinit var homeLayoutManager: GridLayoutManager
    private lateinit var homeAdapter: AppleTvHomeAdapter
    private lateinit var topBar: RelativeLayout
    private lateinit var weatherPillBtn: LinearLayout
    private lateinit var weatherPillText: TextView
    private lateinit var topRamPillText: TextView
    private lateinit var clockText: TextView

    // Weather Panel Overlay
    private lateinit var weatherOverlay: FrameLayout
    private lateinit var weatherBackdrop: ImageView
    private lateinit var weatherContentHost: LinearLayout

    // Control Center Overlay
    private lateinit var controlOverlay: FrameLayout
    private lateinit var ccBackdrop: ImageView
    private lateinit var ccClockText: TextView
    private lateinit var ccMainPage: LinearLayout
    private lateinit var ccWifiPage: LinearLayout
    private lateinit var ccBtPage: LinearLayout
    private lateinit var ccExtraRow: LinearLayout
    private lateinit var wifiTileBox: LinearLayout
    private lateinit var wifiSubText: TextView
    private lateinit var btTileBox: LinearLayout
    private lateinit var btSubText: TextView
    private lateinit var ccRamText: TextView
    private lateinit var ccRamBarFill: View
    private lateinit var volumeSlider: SeekBar
    private lateinit var volumePctText: TextView

    // In-App Wi-Fi & Bluetooth Manager containers (shared in Control Center & Settings)
    private lateinit var wifiMenuToggleBtn: TextView
    private lateinit var wifiMenuStatusText: TextView
    private lateinit var wifiListContainer: LinearLayout
    private lateinit var btMenuToggleBtn: TextView
    private lateinit var btMenuScanBtn: TextView
    private lateinit var btListContainer: LinearLayout
    private val discoveredBtDevices = LinkedHashMap<String, BtDev>()
    private var btReceiverRegistered = false

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
    private lateinit var standbyClockText: TextView
    private lateinit var standbyDateText: TextView

    private var wallpaperServer: WallpaperServer? = null
    private var currentWallpaper: Bitmap? = null
    private var currentBlurred: Bitmap? = null
    private var installedApps: List<AppItem> = emptyList()

    private val clockAndRamTicker = object : Runnable {
        override fun run() {
            updateClockAndRam(forceRamRefresh = false)
            if (controlOverlay.visibility == View.VISIBLE && ccMainPage.visibility == View.VISIBLE) {
                refreshLiveConnectivity()
            }
            ui.postDelayed(this, 2000L)
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

    // Safe unsigned 32-bit ARGB helper (prevents Kotlin Long/Int hex errors forever)
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
        // Layer 1 & 2: Scrollable Apple TV Dock/Grid + Elevated Top Bar (guaranteed clickable!)
        buildHomeUi()
        // Layer 3: Full Frosted Hourly & 5-Day Weather Panel
        buildWeatherOverlay()
        // Layer 4: Apple TV Control Center + In-App Wi-Fi & Bluetooth Managers
        buildControlCenterOverlay()
        // Layer 5: Full Native Split-Screen tvOS Settings App (Dark / Light / Liquid Glass)
        buildSettingsOverlay()
        // Layer 6: Modals (Wi-Fi Password, App Long-Press Actions, StandBy Clock)
        buildWifiPasswordModal()
        buildAppMenuModal()
        buildStandbyOverlay()

        loadWallpaper()
        loadAppsAsync()
        refreshWeatherAsync()
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        ui.removeCallbacks(clockAndRamTicker)
        ui.post(clockAndRamTicker)
        refreshLiveConnectivity()
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(clockAndRamTicker)
        wallpaperServer?.stop()
        stopBluetoothReceiver()
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
        stopBluetoothReceiver()
        wallpaperServer?.stop()
        io.shutdownNow()
        super.onDestroy()
    }

    private fun loadUserPrefs() {
        val p = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        dockColumns = p.getInt("dock_columns", 6).let { if (it == 9) 9 else 6 }
        themeMode = p.getString("theme_mode", "LIQUID") ?: "LIQUID"
        showTopRamPill = p.getBoolean("show_top_ram", true)
        use24HourClock = p.getBoolean("use_24h_clock", false)
        showExtraCcTiles = p.getBoolean("show_extra_cc", true)
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
    // LAYER 0: FULL-BLEED 4K TRUE-COLOR WALLPAPER (0 MB JAVA HEAP ON API 26+)
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
                intArrayOf(Color.TRANSPARENT, argb(0x78060A14))
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
            // Decodes full 32-bit true color directly into GPU memory (Bitmap.Config.HARDWARE)
            val bmp = WallpaperServer.loadActiveWallpaperBitmap(this, dm.widthPixels, dm.heightPixels)
            // Decodes a tiny 144x81 pre-blurred backdrop separately (~46 KB RAM, zero CPU copy of 4K image)
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
                // Do not call old.recycle() synchronously here: on API 26+ Android's RenderThread
                // may still be drawing the previous frame; NativeAllocationRegistry frees it safely.
            }
        }
    }

    // =========================================================================
    // LAYER 1 & 2: AUTHENTIC APPLE TV HOME SCREEN + ELEVATED CLICKABLE TOP BAR
    // =========================================================================
    private fun buildHomeUi() {
        // 1) Scrollable RecyclerView added FIRST so Top Bar always sits above it
        homeRecycler = RecyclerView(this).apply {
            clipToPadding = false
            clipChildren = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setPadding(dp(24), 0, dp(24), dp(28))
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
                topBar.alpha = barAlpha
                topBar.visibility = if (barAlpha < 0.06f) View.GONE else View.VISIBLE
            }
        })
        rootFrame.addView(homeRecycler, FrameLayout.LayoutParams(-1, -1))

        // 2) Elevated Floating Top Bar (Added directly to rootFrame AFTER RecyclerView!)
        topBar = RelativeLayout(this).apply {
            setPadding(dp(24), dp(14), dp(24), dp(8))
            elevation = dp(16).toFloat()
            clipChildren = false
            clipToPadding = false
        }

        val leftGroup = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
        }

        // Weather Pill -> Opens Full Frosted 5-Day Weather Panel
        weatherPillBtn = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(7), dp(13), dp(7))
            background = glassPill(argb(0x64121826), argb(0x55FFFFFF), dp(20))
            isFocusable = true
            isClickable = true
            setOnClickListener { openWeatherPanel() }
            attachInteractiveScale(this, 1.08f)

            addView(VectorIconView(context, Glyph.SUN, Color.parseColor("#FBBF24")), LinearLayout.LayoutParams(dp(15), dp(15)).apply {
                marginEnd = dp(5)
            })
            weatherPillText = TextView(context).apply {
                text = "--°"
                setTextColor(Color.WHITE)
                textSize = 13f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            }
            addView(weatherPillText)
        }
        leftGroup.addView(weatherPillBtn)

        // Round Wallpaper / Settings Circle -> Opens Wallpapers Tab in Native Settings
        val wpCircleBtn = createVectorCircleBtn(Glyph.WALLPAPER, dp(36), argb(0x64121826), Color.WHITE) {
            openSettingsOverlay(tab = 2)
        }
        leftGroup.addView(wpCircleBtn, LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginStart = dp(8) })

        // Live RAM Pill -> Opens Control Center
        topRamPillText = TextView(this).apply {
            text = "● --"
            setTextColor(Color.WHITE)
            textSize = 11.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(dp(11), dp(7), dp(11), dp(7))
            background = glassPill(argb(0x60121826), argb(0x45FFFFFF), dp(20))
            isFocusable = true
            isClickable = true
            visibility = if (showTopRamPill) View.VISIBLE else View.GONE
            setOnClickListener { openControlCenter() }
            attachInteractiveScale(this, 1.07f)
        }
        leftGroup.addView(topRamPillText, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })

        topBar.addView(leftGroup, RelativeLayout.LayoutParams(-2, -2).apply {
            addRule(RelativeLayout.ALIGN_PARENT_START)
            addRule(RelativeLayout.CENTER_VERTICAL)
        })

        // Top-Right: Sleek Apple TV Time + Orange Avatar Capsule -> Opens Control Center
        val rightCapsule = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(3), dp(3), dp(3))
            background = glassPill(argb(0x70121826), argb(0x55FFFFFF), dp(22))
            isFocusable = true
            isClickable = true
            setOnClickListener { openControlCenter() }
            attachInteractiveScale(this, 1.06f)
        }

        clockText = TextView(this).apply {
            text = "12:00 PM"
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(0, 0, dp(9), 0)
        }
        rightCapsule.addView(clockText)

        val avatarBadge = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(argb(0xFFDF6F38))
                setStroke(dp(1), argb(0x90FFFFFF))
            }
            addView(VectorIconView(context, Glyph.USER, Color.WHITE), FrameLayout.LayoutParams(dp(16), dp(16), Gravity.CENTER))
        }
        rightCapsule.addView(avatarBadge, LinearLayout.LayoutParams(dp(30), dp(30)))

        topBar.addView(rightCapsule, RelativeLayout.LayoutParams(-2, -2).apply {
            addRule(RelativeLayout.ALIGN_PARENT_END)
            addRule(RelativeLayout.CENTER_VERTICAL)
        })

        rootFrame.addView(topBar, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
    }

    private fun applyDockColumns(cols: Int) {
        dockColumns = cols
        savePref { putInt("dock_columns", cols) }
        homeLayoutManager.spanCount = cols
        homeLayoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int = if (position == 0 || position == 1) cols else 1
        }
        homeAdapter.updateApps(installedApps)
    }

    // =========================================================================
    // LAYER 3: FULL FROSTED HOURLY & 5-DAY WEATHER PANEL
    // =========================================================================
    private fun buildWeatherOverlay() {
        weatherOverlay = FrameLayout(this).apply {
            visibility = View.GONE
            isClickable = true
            isFocusable = true
            elevation = dp(24).toFloat()
        }
        weatherBackdrop = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setColorFilter(argb(0x68060A14), PorterDuff.Mode.SRC_OVER)
            isClickable = true
            setOnClickListener { closeWeatherPanel() }
        }
        weatherOverlay.addView(weatherBackdrop, FrameLayout.LayoutParams(-1, -1))

        val panelCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
            background = glassPill(argb(0xAA161D2C), argb(0x60FFFFFF), dp(28))
            isClickable = true
        }
        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        weatherContentHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(weatherContentHost, FrameLayout.LayoutParams(-1, -2))
        panelCard.addView(scroll, LinearLayout.LayoutParams(-1, -2))

        val maxW = (resources.displayMetrics.widthPixels * 0.78f).toInt().coerceAtMost(dp(640))
        weatherOverlay.addView(panelCard, FrameLayout.LayoutParams(maxW, -2, Gravity.CENTER).apply {
            setMargins(dp(18), dp(18), dp(18), dp(18))
        })
        rootFrame.addView(weatherOverlay, FrameLayout.LayoutParams(-1, -1))
    }

    private fun openWeatherPanel() {
        applyWallpaperBlur(true)
        renderWeatherPanel(SystemMonitor.cachedWeather)
        weatherOverlay.visibility = View.VISIBLE
        weatherOverlay.requestFocus()
        refreshWeatherAsync(updatePanel = true)
    }

    private fun closeWeatherPanel() {
        applyWallpaperBlur(false)
        weatherOverlay.visibility = View.GONE
        homeRecycler.requestFocus()
    }

    private fun renderWeatherPanel(report: WeatherReport?) {
        weatherContentHost.removeAllViews()

        val header = RelativeLayout(this)
        val title = TextView(this).apply {
            text = report?.city ?: SystemMonitor.getSavedCityName(this@MainActivity)
            setTextColor(Color.WHITE)
            textSize = 18f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        header.addView(title, RelativeLayout.LayoutParams(-2, -2).apply {
            addRule(RelativeLayout.ALIGN_PARENT_START)
            addRule(RelativeLayout.CENTER_VERTICAL)
        })

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(makePillAction("Change City", argb(0x38FFFFFF), Color.WHITE) {
            closeWeatherPanel()
            openSettingsOverlay(tab = 5)
        })
        actions.addView(
            makePillAction("Close", argb(0xFFDF6F38), Color.WHITE) { closeWeatherPanel() },
            LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) }
        )
        header.addView(actions, RelativeLayout.LayoutParams(-2, -2).apply {
            addRule(RelativeLayout.ALIGN_PARENT_END)
            addRule(RelativeLayout.CENTER_VERTICAL)
        })
        weatherContentHost.addView(header, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })

        if (report == null) {
            weatherContentHost.addView(TextView(this).apply {
                text = "Loading live forecast from Open-Meteo..."
                setTextColor(argb(0xCCFFFFFF))
                textSize = 14f
                setPadding(0, dp(24), 0, dp(24))
                gravity = Gravity.CENTER
            })
            return
        }

        val split = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        val leftCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        leftCol.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(context).apply {
                text = "${report.tempC}°"
                setTextColor(Color.WHITE)
                textSize = 44f
                typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, 0, 0)
                addView(TextView(context).apply {
                    text = "${SystemMonitor.weatherCodeToEmoji(report.code)}  ${report.condition}"
                    setTextColor(Color.WHITE)
                    textSize = 14f
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                })
                addView(TextView(context).apply {
                    text = "H:${report.highC}°  L:${report.lowC}°"
                    setTextColor(argb(0xC8E2E8F0))
                    textSize = 12f
                })
            })
        })

        leftCol.addView(makeSectionHeader("HOURLY FORECAST"))
        val hourlyRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = glassPill(argb(0x28FFFFFF), argb(0x25FFFFFF), dp(16))
        }
        for (h in report.hourly) {
            hourlyRow.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                addView(TextView(context).apply {
                    text = h.label; setTextColor(argb(0xCCE2E8F0)); textSize = 10.5f; gravity = Gravity.CENTER
                })
                addView(TextView(context).apply {
                    text = SystemMonitor.weatherCodeToEmoji(h.code); textSize = 15f; gravity = Gravity.CENTER
                    setPadding(0, dp(3), 0, dp(3))
                })
                addView(TextView(context).apply {
                    text = "${h.tempC}°"; setTextColor(Color.WHITE); textSize = 12f; gravity = Gravity.CENTER
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                })
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        leftCol.addView(hourlyRow, LinearLayout.LayoutParams(-1, -2))

        leftCol.addView(makeSectionHeader("5-DAY FORECAST"))
        val dailyCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = glassPill(argb(0x28FFFFFF), argb(0x25FFFFFF), dp(16))
        }
        val overallMin = report.daily.minOfOrNull { it.minC } ?: 0
        val overallMax = (report.daily.maxOfOrNull { it.maxC } ?: 40).coerceAtLeast(overallMin + 1)

        for (d in report.daily) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(4), 0, dp(4))
            }
            row.addView(TextView(this).apply {
                text = d.day; setTextColor(Color.WHITE); textSize = 12f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            }, LinearLayout.LayoutParams(dp(46), -2))

            row.addView(TextView(this).apply {
                text = SystemMonitor.weatherCodeToEmoji(d.code); textSize = 14f; gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(dp(26), -2))

            row.addView(TextView(this).apply {
                text = "${d.minC}°"; setTextColor(argb(0xB8E2E8F0)); textSize = 11.5f; gravity = Gravity.END
            }, LinearLayout.LayoutParams(dp(28), -2))

            val barTrack = FrameLayout(this).apply {
                background = glassPill(argb(0x38000000), 0, dp(4))
            }
            val startFrac = ((d.minC - overallMin).toFloat() / (overallMax - overallMin)).coerceIn(0f, 0.85f)
            val endFrac = ((d.maxC - overallMin).toFloat() / (overallMax - overallMin)).coerceIn(startFrac + 0.15f, 1f)
            val barWidthPx = dp(86)
            val fillView = View(this).apply {
                background = GradientDrawable(
                    GradientDrawable.Orientation.LEFT_RIGHT,
                    intArrayOf(Color.parseColor("#4ADE80"), Color.parseColor("#FACC15"), Color.parseColor("#FB923C"))
                ).apply { cornerRadius = dp(4).toFloat() }
            }
            val lpFill = FrameLayout.LayoutParams(((endFrac - startFrac) * barWidthPx).toInt().coerceAtLeast(dp(12)), dp(5)).apply {
                marginStart = (startFrac * barWidthPx).toInt()
            }
            barTrack.addView(fillView, lpFill)
            row.addView(barTrack, LinearLayout.LayoutParams(barWidthPx, dp(5)).apply {
                marginStart = dp(8); marginEnd = dp(8)
            })

            row.addView(TextView(this).apply {
                text = "${d.maxC}°"; setTextColor(Color.WHITE); textSize = 11.5f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            }, LinearLayout.LayoutParams(dp(28), -2))

            dailyCard.addView(row)
        }
        leftCol.addView(dailyCard, LinearLayout.LayoutParams(-1, -2))

        val rightCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(4), 0, 0)
        }
        rightCol.addView(buildWeatherMetricPair("FEELS LIKE", "${report.feelsLikeC}°", "UV INDEX", "${report.uvIndex}"))
        rightCol.addView(buildWeatherMetricPair("SUNRISE", report.sunrise, "SUNSET", report.sunset))
        rightCol.addView(buildWeatherMetricPair("HUMIDITY", "${report.humidity}%", "WIND", "${report.windKmh} km/h"))
        rightCol.addView(TextView(this).apply {
            text = "Rain Chance (6h): ${report.rainChance}%\nOpen-Meteo • Updated ${report.updatedAt}"
            setTextColor(argb(0x99E2E8F0))
            textSize = 10.5f
            setPadding(dp(4), dp(8), 0, 0)
        })

        split.addView(leftCol, LinearLayout.LayoutParams(0, -2, 1.25f))
        split.addView(rightCol, LinearLayout.LayoutParams(0, -2, 1f))
        weatherContentHost.addView(split)
    }

    private fun buildWeatherMetricPair(t1: String, v1: String, t2: String, v2: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(buildWeatherMetricBox(t1, v1), LinearLayout.LayoutParams(0, -2, 1f))
            addView(buildWeatherMetricBox(t2, v2), LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) })
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
        }
    }

    private fun buildWeatherMetricBox(label: String, value: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(9), dp(10), dp(9))
            background = glassPill(argb(0x2AFFFFFF), argb(0x28FFFFFF), dp(16))
            addView(TextView(context).apply {
                text = label; setTextColor(argb(0xA8E2E8F0)); textSize = 9.5f
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            })
            addView(TextView(context).apply {
                text = value; setTextColor(Color.WHITE); textSize = 16f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setPadding(0, dp(3), 0, 0)
            })
        }
    }

    // =========================================================================
    // LAYER 4: APPLE TV CONTROL CENTER + IN-APP WI-FI & BLUETOOTH MANAGERS
    // =========================================================================
    private fun buildControlCenterOverlay() {
        controlOverlay = FrameLayout(this).apply {
            visibility = View.GONE
            isClickable = true
            isFocusable = true
            elevation = dp(26).toFloat()
        }

        ccBackdrop = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setColorFilter(argb(0x64080C16), PorterDuff.Mode.SRC_OVER)
            isClickable = true
            setOnClickListener { closeControlCenter() }
        }
        controlOverlay.addView(ccBackdrop, FrameLayout.LayoutParams(-1, -1))

        val rightWrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(24), dp(14))
            gravity = Gravity.END
        }

        val headerRow = RelativeLayout(this)
        ccClockText = TextView(this).apply {
            text = "12:00 PM"
            setTextColor(Color.WHITE)
            textSize = 14.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        headerRow.addView(ccClockText, RelativeLayout.LayoutParams(-2, -2).apply {
            addRule(RelativeLayout.ALIGN_PARENT_START)
            addRule(RelativeLayout.CENTER_VERTICAL)
        })

        val headerBtns = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        headerBtns.addView(createVectorCircleBtn(Glyph.SETTINGS, dp(36), argb(0xEBF4F6FA), Color.parseColor("#121620")) {
            closeControlCenter()
            openSettingsOverlay(tab = 0)
        })
        headerBtns.addView(
            createVectorCircleBtn(Glyph.CLOSE, dp(36), argb(0xFFDF6F38), Color.WHITE) { closeControlCenter() },
            LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginStart = dp(10) }
        )
        headerRow.addView(headerBtns, RelativeLayout.LayoutParams(-2, -2).apply {
            addRule(RelativeLayout.ALIGN_PARENT_END)
            addRule(RelativeLayout.CENTER_VERTICAL)
        })
        rightWrapper.addView(headerRow, LinearLayout.LayoutParams(dp(320), -2).apply { bottomMargin = dp(10) })

        val pageHost = FrameLayout(this)
        ccMainPage = buildControlCenterMainPage()
        ccWifiPage = buildInAppWifiMenu()
        ccBtPage = buildInAppBluetoothMenu()

        pageHost.addView(ccMainPage, FrameLayout.LayoutParams(dp(320), -2))
        pageHost.addView(ccWifiPage, FrameLayout.LayoutParams(dp(320), -1))
        pageHost.addView(ccBtPage, FrameLayout.LayoutParams(dp(320), -1))

        rightWrapper.addView(pageHost, LinearLayout.LayoutParams(dp(320), 0, 1f))
        controlOverlay.addView(rightWrapper, FrameLayout.LayoutParams(-2, -1, Gravity.END))
        rootFrame.addView(controlOverlay, FrameLayout.LayoutParams(-1, -1))
    }

    private fun buildControlCenterMainPage(): LinearLayout {
        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        val inner = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val topGrid = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val standbyTile = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = glassPill(argb(0xF0F5F7FA), argb(0xFFFFFFFF), dp(22))
            isFocusable = true
            isClickable = true
            setOnClickListener { openStandbyMode() }
            attachInteractiveScale(this, 1.05f)
            addView(VectorIconView(context, Glyph.CLOCK, Color.parseColor("#161A24")), LinearLayout.LayoutParams(dp(28), dp(28)))
            addView(TextView(context).apply {
                text = "StandBy"
                setTextColor(Color.parseColor("#161A24"))
                textSize = 12.5f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setPadding(0, dp(6), 0, 0)
            })
        }
        topGrid.addView(standbyTile, LinearLayout.LayoutParams(dp(132), dp(114)))

        val rightStack = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val wifiPair = buildToggleableTile(Glyph.WIFI, "Wi-Fi", "Checking...",
            onShortClick = { handleWifiShortToggle() },
            onLongPress = { openWifiManagerPage() }
        )
        wifiTileBox = wifiPair.first
        wifiSubText = wifiPair.second
        rightStack.addView(wifiTileBox, LinearLayout.LayoutParams(-1, dp(53)))

        val btPair = buildToggleableTile(Glyph.BLUETOOTH, "Bluetooth", "Checking...",
            onShortClick = { handleBluetoothShortToggle() },
            onLongPress = { openBluetoothManagerPage() }
        )
        btTileBox = btPair.first
        btSubText = btPair.second
        rightStack.addView(btTileBox, LinearLayout.LayoutParams(-1, dp(53)).apply { topMargin = dp(8) })

        topGrid.addView(rightStack, LinearLayout.LayoutParams(dp(178), dp(114)).apply { marginStart = dp(10) })
        inner.addView(topGrid, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })

        inner.addView(TextView(this).apply {
            text = "Click: Toggle Wi-Fi/BT  •  Hold: Manage In-App"
            setTextColor(argb(0xB0E2E8F0))
            textSize = 10f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(8))
        })

        val quickRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        quickRow.addView(buildRoundAction(Glyph.SETTINGS, "Settings") {
            closeControlCenter()
            openSettingsOverlay(tab = 0)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        quickRow.addView(buildRoundAction(Glyph.TRIM, "Trim RAM") { triggerRamTrim() }, LinearLayout.LayoutParams(0, -2, 1f))
        quickRow.addView(buildRoundAction(Glyph.WALLPAPER, "Walls") {
            closeControlCenter()
            openSettingsOverlay(tab = 2)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        quickRow.addView(buildRoundAction(Glyph.SUN, "Weather") {
            closeControlCenter()
            openWeatherPanel()
        }, LinearLayout.LayoutParams(0, -2, 1f))
        inner.addView(quickRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })

        ccExtraRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            visibility = if (showExtraCcTiles) View.VISIBLE else View.GONE
        }
        ccExtraRow.addView(buildRoundAction(Glyph.VOLUME, "Mute") {
            val muted = SystemMonitor.toggleMute(this)
            refreshLiveConnectivity()
            Toast.makeText(this, if (muted) "Muted" else "Unmuted", Toast.LENGTH_SHORT).show()
        }, LinearLayout.LayoutParams(0, -2, 1f))
        ccExtraRow.addView(buildRoundAction(Glyph.DOCK, "Dock 6/9") {
            val next = if (dockColumns == 6) 9 else 6
            applyDockColumns(next)
            Toast.makeText(this, if (next == 9) "Compact 9-tile Dock" else "Standard 6-tile Dock", Toast.LENGTH_SHORT).show()
        }, LinearLayout.LayoutParams(0, -2, 1f))
        ccExtraRow.addView(buildRoundAction(Glyph.SYNC, "Sync") {
            TvIconComposer.clearCache()
            loadAppsAsync()
            refreshWeatherAsync()
            refreshLiveConnectivity()
            Toast.makeText(this, "Apps & telemetry refreshed", Toast.LENGTH_SHORT).show()
        }, LinearLayout.LayoutParams(0, -2, 1f))
        ccExtraRow.addView(buildRoundAction(Glyph.WIFI, "Networks") {
            openWifiManagerPage()
        }, LinearLayout.LayoutParams(0, -2, 1f))
        inner.addView(ccExtraRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })

        val volRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(6), dp(14), dp(6))
            background = glassPill(argb(0x52161D2C), argb(0x70FFFFFF), dp(24))
        }
        volRow.addView(VectorIconView(this, Glyph.VOLUME, Color.WHITE), LinearLayout.LayoutParams(dp(18), dp(18)))

        volumeSlider = SeekBar(this).apply {
            max = 100
            keyProgressIncrement = 5
            isFocusable = true
            progress = SystemMonitor.getVolumePercent(this@MainActivity)
            progressDrawable = buildSleekSeekBarDrawable()
            thumb = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.WHITE)
                setSize(dp(14), dp(14))
            }
            splitTrack = false
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    volumePctText.text = "$p%"
                    if (fromUser) SystemMonitor.setVolumePercent(this@MainActivity, p)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        volRow.addView(volumeSlider, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8); marginEnd = dp(8) })

        volumePctText = TextView(this).apply {
            text = "${SystemMonitor.getVolumePercent(this@MainActivity)}%"
            setTextColor(Color.WHITE)
            textSize = 12f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        volRow.addView(volumePctText)
        inner.addView(volRow, LinearLayout.LayoutParams(-1, dp(44)).apply { bottomMargin = dp(8) })

        val ramCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = glassPill(argb(0x4C161D2C), argb(0x55FFFFFF), dp(18))
            isFocusable = true
            isClickable = true
            setOnClickListener { triggerRamTrim() }
            attachInteractiveScale(this, 1.03f)
        }
        ccRamText = TextView(this).apply {
            text = "TV RAM: Measuring..."
            setTextColor(Color.WHITE)
            textSize = 11.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        ramCard.addView(ccRamText)
        val barTrack = FrameLayout(this).apply { background = glassPill(argb(0x55000000), argb(0x28FFFFFF), dp(4)) }
        ccRamBarFill = View(this).apply { background = glassPill(argb(0xFF4ADE80), 0, dp(4)) }
        barTrack.addView(ccRamBarFill, FrameLayout.LayoutParams(dp(80), dp(5)))
        ramCard.addView(barTrack, LinearLayout.LayoutParams(-1, dp(5)).apply { topMargin = dp(5) })
        inner.addView(ramCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })

        val editBtn = makePillAction("Edit Tiles", argb(0x48FFFFFF), Color.WHITE) {
            showExtraCcTiles = !showExtraCcTiles
            savePref { putBoolean("show_extra_cc", showExtraCcTiles) }
            ccExtraRow.visibility = if (showExtraCcTiles) View.VISIBLE else View.GONE
            Toast.makeText(this, if (showExtraCcTiles) "Extra tiles shown" else "Compact tiles enabled", Toast.LENGTH_SHORT).show()
        }
        val editWrap = FrameLayout(this).apply {
            addView(editBtn, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        }
        inner.addView(editWrap, LinearLayout.LayoutParams(-1, -2))

        scroll.addView(inner, FrameLayout.LayoutParams(-1, -2))
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(scroll, LinearLayout.LayoutParams(-1, -2))
        }
    }

    // ---------------- IN-APP WI-FI MANAGER ----------------
    private fun buildInAppWifiMenu(): LinearLayout {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = glassPill(argb(0x78121826), argb(0x55FFFFFF), dp(24))
        }
        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        nav.addView(makePillAction("‹ Wi-Fi", argb(0x33FFFFFF), Color.WHITE) { showMainControlPage() })
        nav.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        wifiMenuToggleBtn = makePillAction("On", argb(0xFF2563EB), Color.WHITE) {
            handleWifiShortToggle()
            ui.postDelayed({ renderWifiList(wifiListContainer, wifiMenuToggleBtn, wifiMenuStatusText) }, 700L)
        }
        nav.addView(wifiMenuToggleBtn)
        nav.addView(
            makePillAction("Scan", argb(0x40FFFFFF), Color.WHITE) {
                renderWifiList(wifiListContainer, wifiMenuToggleBtn, wifiMenuStatusText)
            },
            LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6) }
        )
        page.addView(nav, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })

        wifiMenuStatusText = TextView(this).apply {
            text = "Scanning..."
            setTextColor(argb(0xCCE2E8F0))
            textSize = 11f
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
        renderWifiList(wifiListContainer, wifiMenuToggleBtn, wifiMenuStatusText)
        ccWifiPage.requestFocus()
    }

    private fun renderWifiList(container: LinearLayout, toggleBtn: TextView?, statusTv: TextView?) {
        val enabled = SystemMonitor.isWifiEnabled(this)
        toggleBtn?.text = if (enabled) "On" else "Off"
        toggleBtn?.background = glassPill(if (enabled) argb(0xFF2563EB) else argb(0x45FFFFFF), argb(0x66FFFFFF), dp(14))
        statusTv?.text = if (enabled) {
            "Connected: ${SystemMonitor.getWifiStatus(this)} • IP: ${WallpaperServer.getLocalIpAddress()}"
        } else {
            "Wi-Fi is turned off"
        }

        container.removeAllViews()
        if (!enabled) return
        container.addView(TextView(this).apply {
            text = "Scanning nearby Wi-Fi networks..."
            setTextColor(argb(0xB8FFFFFF))
            textSize = 12f
            setPadding(dp(8), dp(10), dp(8), dp(10))
        })

        io.execute {
            val nets = SystemMonitor.scanWifiNetworks(this)
            ui.post {
                if (isFinishing || isDestroyed) return@post
                container.removeAllViews()
                if (nets.isEmpty()) {
                    container.addView(TextView(this).apply {
                        text = "No networks found. Grant Location permission and tap Scan."
                        setTextColor(argb(0xB8E2E8F0))
                        textSize = 11.5f
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
                                    Toast.makeText(this, "Already connected to ${net.ssid}", Toast.LENGTH_SHORT).show()
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
            }
        }
    }

    // ---------------- IN-APP BLUETOOTH MANAGER ----------------
    private fun buildInAppBluetoothMenu(): LinearLayout {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = glassPill(argb(0x78121826), argb(0x55FFFFFF), dp(24))
        }
        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        nav.addView(makePillAction("‹ Bluetooth", argb(0x33FFFFFF), Color.WHITE) {
            stopBluetoothReceiver()
            showMainControlPage()
        })
        nav.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        btMenuToggleBtn = makePillAction("On", argb(0xFF2563EB), Color.WHITE) {
            handleBluetoothShortToggle()
            ui.postDelayed({ renderBluetoothList(btListContainer, btMenuToggleBtn) }, 700L)
        }
        nav.addView(btMenuToggleBtn)
        btMenuScanBtn = makePillAction("Scan", argb(0x40FFFFFF), Color.WHITE) { startBluetoothScanInApp() }
        nav.addView(btMenuScanBtn, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6) })
        page.addView(nav, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })

        page.addView(TextView(this).apply {
            text = "Click: Connect/Pair  •  Hold: Forget Device"
            setTextColor(argb(0xB4D2E0FA))
            textSize = 10f
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
        discoveredBtDevices.clear()
        startBluetoothReceiver()
        renderBluetoothList(btListContainer, btMenuToggleBtn)
        startBluetoothScanInApp()
        ccBtPage.requestFocus()
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
        val enabled = SystemMonitor.isBluetoothEnabled(this)
        toggleBtn?.text = if (enabled) "On" else "Off"
        toggleBtn?.background = glassPill(if (enabled) argb(0xFF2563EB) else argb(0x45FFFFFF), argb(0x66FFFFFF), dp(14))
        container.removeAllViews()

        if (!enabled) {
            container.addView(TextView(this).apply {
                text = "Bluetooth is off — tap Off to turn on."
                setTextColor(argb(0xB8E2E8F0))
                textSize = 12f
                setPadding(dp(8), dp(12), dp(8), dp(12))
            })
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
                            SystemMonitor.disconnectBluetoothDevice(this, dev.device) { _, msg ->
                                ui.post {
                                    if (isFinishing || isDestroyed) return@post
                                    Toast.makeText(this, "${dev.name}: $msg", Toast.LENGTH_SHORT).show()
                                    ui.postDelayed({ renderBluetoothList(container, toggleBtn); refreshLiveConnectivity() }, 900L)
                                }
                            }
                        } else {
                            SystemMonitor.connectBluetoothDevice(this, dev.device) { _, msg ->
                                ui.post {
                                    if (isFinishing || isDestroyed) return@post
                                    Toast.makeText(this, "${dev.name}: $msg", Toast.LENGTH_SHORT).show()
                                    ui.postDelayed({ renderBluetoothList(container, toggleBtn); refreshLiveConnectivity() }, 1200L)
                                }
                            }
                        }
                    },
                    onLongClick = {
                        val ok = SystemMonitor.forgetBluetoothDevice(dev.device)
                        Toast.makeText(this, if (ok) "Forgot ${dev.name}" else "Unable to unpair", Toast.LENGTH_SHORT).show()
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
                setTextColor(argb(0xA8DCE6F5))
                textSize = 11.5f
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
                        Toast.makeText(this, if (ok) "Pairing with ${dev.name}..." else "Pairing failed", Toast.LENGTH_SHORT).show()
                    },
                    onLongClick = null
                ))
            }
        }
    }

    // =========================================================================
    // LAYER 5: FULL NATIVE SPLIT-SCREEN tvOS SETTINGS APP (DARK / LIGHT / LIQUID)
    // =========================================================================
    private fun buildSettingsOverlay() {
        settingsOverlay = FrameLayout(this).apply {
            setBackgroundColor(argb(0xCC060910))
            visibility = View.GONE
            isClickable = true
            isFocusable = true
            elevation = dp(28).toFloat()
        }

        settingsCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            isClickable = true
        }

        val leftScroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        settingsSidebar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        leftScroll.addView(settingsSidebar, FrameLayout.LayoutParams(-1, -2))
        settingsCard.addView(leftScroll, LinearLayout.LayoutParams(dp(210), -1))

        settingsCard.addView(View(this).apply { setBackgroundColor(argb(0x28FFFFFF)) }, LinearLayout.LayoutParams(dp(1), -1))

        val rightScroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        settingsDetailHost = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(18))
        }
        rightScroll.addView(settingsDetailHost, FrameLayout.LayoutParams(-1, -2))
        settingsCard.addView(rightScroll, LinearLayout.LayoutParams(0, -1, 1f))

        val cardW = (resources.displayMetrics.widthPixels * 0.88f).toInt().coerceAtMost(dp(760))
        val cardH = (resources.displayMetrics.heightPixels * 0.86f).toInt().coerceAtMost(dp(460))
        settingsOverlay.addView(settingsCard, FrameLayout.LayoutParams(cardW, cardH, Gravity.CENTER))
        rootFrame.addView(settingsOverlay, FrameLayout.LayoutParams(-1, -1))
    }

    private fun openSettingsOverlay(tab: Int = 0) {
        activeSettingsTab = tab
        applyThemeToSettingsCard()
        renderSettingsSidebar()
        renderSettingsDetail()
        settingsOverlay.visibility = View.VISIBLE
        settingsOverlay.requestFocus()
    }

    private fun closeSettingsOverlay() {
        wallpaperServer?.stop()
        stopBluetoothReceiver()
        settingsOverlay.visibility = View.GONE
        homeRecycler.requestFocus()
    }

    private fun isLightTheme(): Boolean = themeMode == "LIGHT"

    private fun applyThemeToSettingsCard() {
        val bg = when (themeMode) {
            "LIGHT" -> argb(0xF5F2F4F8)
            "DARK" -> argb(0xFA11141C)
            else -> argb(0xDA131A29)
        }
        val stroke = if (isLightTheme()) argb(0x30000000) else argb(0x45FFFFFF)
        settingsCard.background = glassPill(bg, stroke, dp(26))
    }

    private fun renderSettingsSidebar() {
        settingsSidebar.removeAllViews()
        val fg = if (isLightTheme()) Color.parseColor("#111622") else Color.WHITE
        val subFg = if (isLightTheme()) Color.parseColor("#526075") else argb(0xA8DCE6F5)

        val profileHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(4), dp(6), dp(14))
            addView(FrameLayout(context).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(argb(0xFFDF6F38)) }
                addView(VectorIconView(context, Glyph.USER, Color.WHITE), FrameLayout.LayoutParams(dp(16), dp(16), Gravity.CENTER))
            }, LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginEnd = dp(10) })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(context).apply {
                    text = "TvOS Native"; setTextColor(fg); textSize = 13.5f
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                })
                addView(TextView(context).apply { text = "v3.0 • Settings"; setTextColor(subFg); textSize = 10.5f })
            })
        }
        settingsSidebar.addView(profileHeader)

        val tabs = listOf(
            Triple(Glyph.SUN, "Appearance", argb(0xFF0EA5E9)),
            Triple(Glyph.DOCK, "Home & Dock", argb(0xFF6366F1)),
            Triple(Glyph.WALLPAPER, "Wallpapers", argb(0xFFEC4899)),
            Triple(Glyph.WIFI, "Wi-Fi", argb(0xFF3B82F6)),
            Triple(Glyph.BLUETOOTH, "Bluetooth", argb(0xFF2563EB)),
            Triple(Glyph.CLOCK, "Weather & Clock", argb(0xFFF59E0B)),
            Triple(Glyph.TRIM, "RAM & System", argb(0xFF10B981))
        )

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
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(8), dp(10), dp(8))
                background = rowBg
                isFocusable = true
                isClickable = true
                setOnClickListener {
                    activeSettingsTab = idx
                    renderSettingsSidebar()
                    renderSettingsDetail()
                }
                addView(FrameLayout(context).apply {
                    background = glassPill(badgeColor, 0, dp(7))
                    addView(VectorIconView(context, glyph, Color.WHITE), FrameLayout.LayoutParams(dp(14), dp(14), Gravity.CENTER))
                }, LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(10) })
                addView(TextView(context).apply {
                    text = title; setTextColor(rowTextColor); textSize = 12.5f
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                })
            }
            settingsSidebar.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) })
        }

        settingsSidebar.addView(
            makePillAction("Done", argb(0xFFDF6F38), Color.WHITE) { closeSettingsOverlay() },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) }
        )
    }

    private fun renderSettingsDetail() {
        // Keep WallpaperServer alive while staying on the Wallpapers tab (avoids port-8080 bind churn)
        if (activeSettingsTab != 2) wallpaperServer?.stop()
        if (activeSettingsTab != 4) stopBluetoothReceiver()
        settingsDetailHost.removeAllViews()

        val fg = if (isLightTheme()) Color.parseColor("#111622") else Color.WHITE
        val subFg = if (isLightTheme()) Color.parseColor("#526075") else argb(0xB0D8E2F2)
        val boxBg = if (isLightTheme()) argb(0x12000000) else argb(0x1EFFFFFF)

        fun heading(text: String) {
            settingsDetailHost.addView(TextView(this).apply {
                this.text = text; setTextColor(fg); textSize = 19f
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setPadding(0, 0, 0, dp(12))
            })
        }

        fun settingButton(title: String, subtitle: String, active: Boolean = false, onClick: () -> Unit) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(11), dp(14), dp(11))
                background = glassPill(if (active) argb(0xFF2563EB) else boxBg, argb(0x30FFFFFF), dp(16))
                isFocusable = true
                isClickable = true
                setOnClickListener { onClick() }
                attachInteractiveScale(this, 1.02f)

                val col = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(TextView(context).apply {
                        text = title
                        setTextColor(if (active) Color.WHITE else fg)
                        textSize = 13.5f
                        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    })
                    addView(TextView(context).apply {
                        text = subtitle
                        setTextColor(if (active) argb(0xDCE2E8F0) else subFg)
                        textSize = 11f
                    })
                }
                addView(col, LinearLayout.LayoutParams(0, -2, 1f))
                if (active) {
                    addView(TextView(context).apply {
                        text = "✓"; setTextColor(Color.WHITE); textSize = 15f
                    })
                }
            }
            settingsDetailHost.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }

        when (activeSettingsTab) {
            0 -> {
                heading("Appearance")
                settingButton("Liquid Glass (Default)", "Translucent frosted tvOS blur surfaces", themeMode == "LIQUID") {
                    themeMode = "LIQUID"; savePref { putString("theme_mode", themeMode) }
                    applyThemeToSettingsCard(); renderSettingsSidebar(); renderSettingsDetail()
                }
                settingButton("Dark Mode", "Deep OLED dark panels with high contrast", themeMode == "DARK") {
                    themeMode = "DARK"; savePref { putString("theme_mode", themeMode) }
                    applyThemeToSettingsCard(); renderSettingsSidebar(); renderSettingsDetail()
                }
                settingButton("Light Mode", "Bright Apple TV light settings theme", themeMode == "LIGHT") {
                    themeMode = "LIGHT"; savePref { putString("theme_mode", themeMode) }
                    applyThemeToSettingsCard(); renderSettingsSidebar(); renderSettingsDetail()
                }
            }
            1 -> {
                heading("Home Screen & Dock")
                settingButton("Standard Dock (6 Tiles)", "Classic large Apple TV Top Shelf icons", dockColumns == 6) {
                    applyDockColumns(6); renderSettingsDetail()
                }
                settingButton("Compact Dock (9 Tiles)", "Sleek nine smaller tiles in a row (tvOS 4.0)", dockColumns == 9) {
                    applyDockColumns(9); renderSettingsDetail()
                }
                settingButton(
                    "Show Live RAM Pill on Home Screen",
                    if (showTopRamPill) "Currently visible on top bar" else "Hidden (visible in Control Center only)",
                    showTopRamPill
                ) {
                    showTopRamPill = !showTopRamPill
                    savePref { putBoolean("show_top_ram", showTopRamPill) }
                    topRamPillText.visibility = if (showTopRamPill) View.VISIBLE else View.GONE
                    renderSettingsDetail()
                }
                val hiddenCount = TvIconComposer.getHiddenPackages(this).size
                settingButton("Restore Hidden Apps ($hiddenCount)", "Unhide any apps hidden via Hold OK menu") {
                    TvIconComposer.unhideAllApps(this)
                    loadAppsAsync()
                    renderSettingsDetail()
                    Toast.makeText(this, "All apps restored", Toast.LENGTH_SHORT).show()
                }
            }
            2 -> {
                heading("4K Wallpapers")
                if (wallpaperServer == null) {
                    wallpaperServer = WallpaperServer(this) {
                        if (isFinishing || isDestroyed) return@WallpaperServer
                        loadWallpaper()
                        Toast.makeText(this, "4K Wallpaper updated!", Toast.LENGTH_SHORT).show()
                        if (settingsOverlay.visibility == View.VISIBLE && activeSettingsTab == 2) {
                            renderSettingsDetail()
                        }
                    }
                }
                val url = wallpaperServer!!.start(8080)
                settingsDetailHost.addView(TextView(this).apply {
                    text = "Local Wi-Fi 4K Upload URL (open on your phone browser):\n$url"
                    setTextColor(if (isLightTheme()) Color.parseColor("#0284C7") else Color.parseColor("#7DD3FC"))
                    textSize = 12.5f
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    background = glassPill(boxBg, argb(0x30FFFFFF), dp(14))
                }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })

                val hScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(4), 0, dp(10)) }
                val activeId = WallpaperServer.getSelectedWallpaperId(this)
                val builtIns = WallpaperServer.listBuiltInWallpapers(this)

                builtIns.forEachIndexed { i, name ->
                    val id = "builtin:$name"
                    val thumb = createWallpaperThumbCard("Built-in ${i + 1}", activeId == id, fg) {
                        WallpaperServer.setSelectedWallpaperId(this, id)
                        loadWallpaper(); renderSettingsDetail()
                    }
                    row.addView(thumb.first)
                    io.execute {
                        val bmp = WallpaperServer.loadAssetThumbnail(this, name)
                        ui.post { if (!isFinishing && !isDestroyed && bmp != null) thumb.second.setImageBitmap(bmp) }
                    }
                }
                if (WallpaperServer.hasCustomWallpaper(this)) {
                    val thumb = createWallpaperThumbCard("Custom 4K", activeId == "custom", fg) {
                        WallpaperServer.setSelectedWallpaperId(this, "custom")
                        loadWallpaper(); renderSettingsDetail()
                    }
                    row.addView(thumb.first)
                    io.execute {
                        val bmp = WallpaperServer.loadCustomThumbnail(this)
                        ui.post { if (!isFinishing && !isDestroyed && bmp != null) thumb.second.setImageBitmap(bmp) }
                    }
                }
                hScroll.addView(row)
                settingsDetailHost.addView(hScroll, LinearLayout.LayoutParams(-1, -2))

                settingButton("Pick 4K Image from Device Storage", "Select any WebP/JPG/PNG directly from your phone or TV") {
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
                    ui.postDelayed({ renderSettingsDetail() }, 700L)
                }
                val sBtn = makePillAction("Scan Nearby", argb(0x40FFFFFF), fg) { renderSettingsDetail() }
                topRow.addView(tBtn)
                topRow.addView(sBtn, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
                settingsDetailHost.addView(topRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })

                val statusTv = TextView(this).apply { setTextColor(subFg); textSize = 11.5f; setPadding(0, 0, 0, dp(8)) }
                settingsDetailHost.addView(statusTv)
                val listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                settingsDetailHost.addView(listBox)
                renderWifiList(listBox, tBtn, statusTv)
            }
            4 -> {
                heading("Bluetooth Devices")
                startBluetoothReceiver()
                startBluetoothScanInApp()
                val topRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                val tBtn = makePillAction(if (SystemMonitor.isBluetoothEnabled(this)) "On" else "Off", argb(0xFF2563EB), Color.WHITE) {
                    handleBluetoothShortToggle()
                    ui.postDelayed({ renderSettingsDetail() }, 700L)
                }
                val sBtn = makePillAction("Scan Nearby", argb(0x40FFFFFF), fg) {
                    discoveredBtDevices.clear(); startBluetoothScanInApp(); renderSettingsDetail()
                }
                topRow.addView(tBtn)
                topRow.addView(sBtn, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
                settingsDetailHost.addView(topRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })

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
                    textSize = 13.5f
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    background = glassPill(boxBg, argb(0x40FFFFFF), dp(14))
                }
                settingsDetailHost.addView(cityInput, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })

                settingButton("Update Weather Location", "Fetches live hourly & 5-day Open-Meteo forecast") {
                    val q = cityInput.text?.toString().orEmpty()
                    Toast.makeText(this, "Searching $q...", Toast.LENGTH_SHORT).show()
                    io.execute {
                        val (ok, msg) = SystemMonitor.searchAndSaveCity(this, q)
                        ui.post {
                            if (isFinishing || isDestroyed) return@post
                            if (ok) {
                                Toast.makeText(this, "Weather city set to $msg", Toast.LENGTH_SHORT).show()
                                refreshWeatherAsync()
                            } else {
                                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
                settingButton("24-Hour Clock Format", if (use24HourClock) "Using 24-hour time (15:30)" else "Using 12-hour time (3:30 PM)", use24HourClock) {
                    use24HourClock = !use24HourClock
                    savePref { putBoolean("use_24h_clock", use24HourClock) }
                    lastTimeText = ""
                    updateClockAndRam(forceRamRefresh = false)
                    renderSettingsDetail()
                }
            }
            6 -> {
                heading("RAM & System")
                val ram = SystemMonitor.getRamInfo(this)
                val usedGb = String.format(Locale.US, "%.2f", ram.usedMb / 1024f)
                val totalGb = String.format(Locale.US, "%.2f", ram.totalMb / 1024f)
                settingButton("Trim Background RAM Now", "Currently using $usedGb / $totalGb GB (${ram.percent}%) • ${ram.freeMb} MB Free") {
                    triggerRamTrim()
                    ui.postDelayed({ if (!isFinishing && !isDestroyed) renderSettingsDetail() }, 500L)
                }
                settingButton("Set as Default Home Launcher", "Open Android Home App selector") {
                    try { startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) }
                    catch (_: Exception) { startActivity(Intent(Settings.ACTION_SETTINGS)) }
                }
                settingButton("Open Android System Settings", "Access device display, sound & storage settings") {
                    startActivity(Intent(Settings.ACTION_SETTINGS))
                }
            }
        }
    }

    private fun createWallpaperThumbCard(label: String, active: Boolean, textColor: Int, onClick: () -> Unit): Pair<LinearLayout, ImageView> {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(5), dp(4), dp(5), dp(4))
            isFocusable = true
            isClickable = true
            setOnClickListener { onClick() }
            attachInteractiveScale(this, 1.06f)
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
            textSize = 11f
            setPadding(0, dp(4), 0, 0)
        }
        box.addView(img, LinearLayout.LayoutParams(dp(120), dp(68)))
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
            isFocusable = true
            elevation = dp(32).toFloat()
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(20), dp(22), dp(20))
            background = glassPill(argb(0xF0141B29), argb(0x70FFFFFF), dp(24))
        }
        wifiPassTitle = TextView(this).apply {
            text = "Join Wi-Fi"; setTextColor(Color.WHITE); textSize = 16f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        card.addView(wifiPassTitle)
        wifiPassInput = EditText(this).apply {
            hint = "Enter Password"; setHintTextColor(argb(0x99C8D2E6)); setTextColor(Color.WHITE); textSize = 14f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = glassPill(argb(0x45FFFFFF), argb(0x70FFFFFF), dp(14))
        }
        card.addView(wifiPassInput, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12); bottomMargin = dp(14) })

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        row.addView(makePillAction("Cancel", argb(0x35FFFFFF), Color.WHITE) { wifiPassOverlay.visibility = View.GONE })
        row.addView(
            makePillAction("Connect", argb(0xFFDF6F38), Color.WHITE) {
                val net = pendingWifiNet ?: return@makePillAction
                val pw = wifiPassInput.text?.toString().orEmpty()
                wifiPassOverlay.visibility = View.GONE
                connectWifiInApp(net, pw)
            },
            LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(10) }
        )
        card.addView(row)
        wifiPassOverlay.addView(card, FrameLayout.LayoutParams(dp(340), -2, Gravity.CENTER))
        rootFrame.addView(wifiPassOverlay, FrameLayout.LayoutParams(-1, -1))
    }

    private fun openWifiPasswordModal(net: WifiNet) {
        pendingWifiNet = net
        wifiPassTitle.text = "Join \"${net.ssid}\""
        wifiPassInput.setText("")
        wifiPassOverlay.visibility = View.VISIBLE
        wifiPassInput.requestFocus()
    }

    private fun connectWifiInApp(net: WifiNet, password: String?) {
        Toast.makeText(this, "Connecting to ${net.ssid}...", Toast.LENGTH_SHORT).show()
        SystemMonitor.connectToWifi(this, net.ssid, password, net.secured) { _, msg ->
            ui.post {
                if (isFinishing || isDestroyed) return@post
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
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
            isFocusable = true
            elevation = dp(32).toFloat()
            setOnClickListener { visibility = View.GONE }
        }
        appMenuCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(18))
            background = glassPill(argb(0xF0141B29), argb(0x65FFFFFF), dp(24))
            isClickable = true
        }
        appMenuOverlay.addView(appMenuCard, FrameLayout.LayoutParams(dp(300), -2, Gravity.CENTER))
        rootFrame.addView(appMenuOverlay, FrameLayout.LayoutParams(-1, -1))
    }

    private fun openAppActionMenu(app: AppItem) {
        appMenuCard.removeAllViews()
        appMenuCard.addView(TextView(this).apply {
            text = app.label; setTextColor(Color.WHITE); textSize = 16.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setPadding(dp(4), 0, dp(4), dp(10))
        })

        fun addMenuItem(label: String, onClick: () -> Unit) {
            appMenuCard.addView(buildAppleListRow(Glyph.DOCK, label, "", "", {
                appMenuOverlay.visibility = View.GONE
                onClick()
            }, null))
        }

        addMenuItem("Open App") { launchApp(app.packageName) }
        addMenuItem("Pin to Front of Dock") {
            TvIconComposer.pinAppToFront(this, app.packageName)
            loadAppsAsync()
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
            Toast.makeText(this, "Hidden ${app.label} (restore in Settings)", Toast.LENGTH_SHORT).show()
        }

        appMenuOverlay.visibility = View.VISIBLE
        appMenuOverlay.requestFocus()
    }

    private fun buildStandbyOverlay() {
        standbyOverlay = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
            isClickable = true
            isFocusable = true
            elevation = dp(34).toFloat()
            setOnClickListener { visibility = View.GONE }
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        standbyClockText = TextView(this).apply {
            text = "12:00"; setTextColor(argb(0xEEFFFFFF)); textSize = 84f
            typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL); gravity = Gravity.CENTER
        }
        standbyDateText = TextView(this).apply {
            text = "Thursday, Nov 19"; setTextColor(argb(0x99FFFFFF)); textSize = 18f
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL); gravity = Gravity.CENTER
        }
        box.addView(standbyClockText)
        box.addView(standbyDateText)
        standbyOverlay.addView(box, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        rootFrame.addView(standbyOverlay, FrameLayout.LayoutParams(-1, -1))
    }

    // =========================================================================
    // STATE, TELEMETRY & REMOTE NAVIGATION
    // =========================================================================
    private fun applyWallpaperBlur(enable: Boolean) {
        // Because ccBackdrop & weatherBackdrop already display the 46 KB pre-blurred
        // bitmap full-screen, avoid burning GPU bandwidth on live 4K RenderEffect blur
        // on 2 GB Android 12+ TVs unless the pre-blurred backdrop isn't ready yet.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val shouldLiveBlur = enable && currentBlurred == null
            wallpaperView.setRenderEffect(
                if (shouldLiveBlur) RenderEffect.createBlurEffect(28f, 28f, Shader.TileMode.CLAMP) else null
            )
        }
    }

    private fun openControlCenter() {
        applyWallpaperBlur(true)
        showMainControlPage()
        updateClockAndRam(forceRamRefresh = true)
        controlOverlay.visibility = View.VISIBLE
        controlOverlay.requestFocus()
    }

    private fun closeControlCenter() {
        stopBluetoothReceiver()
        applyWallpaperBlur(false)
        controlOverlay.visibility = View.GONE
        homeRecycler.requestFocus()
    }

    private fun showMainControlPage() {
        ccWifiPage.visibility = View.GONE
        ccBtPage.visibility = View.GONE
        ccMainPage.visibility = View.VISIBLE
        refreshLiveConnectivity()
        ccMainPage.requestFocus()
    }

    private fun openStandbyMode() {
        closeControlCenter()
        standbyOverlay.visibility = View.VISIBLE
        updateClockAndRam(forceRamRefresh = false)
        standbyOverlay.requestFocus()
    }

    private fun triggerRamTrim() {
        io.execute {
            val freed = SystemMonitor.trimRam(this)
            ui.post {
                if (isFinishing || isDestroyed) return@post
                updateClockAndRam(forceRamRefresh = true)
                Toast.makeText(this, "RAM trimmed • Freed ~${freed} MB", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun handleWifiShortToggle() {
        when (SystemMonitor.toggleWifi(this)) {
            ToggleResult.TURNED_ON -> {
                Toast.makeText(this, "Wi-Fi Turning On...", Toast.LENGTH_SHORT).show()
                ui.postDelayed({ refreshLiveConnectivity() }, 800L)
            }
            ToggleResult.TURNED_OFF -> {
                Toast.makeText(this, "Wi-Fi Turned Off", Toast.LENGTH_SHORT).show()
                ui.postDelayed({ refreshLiveConnectivity() }, 600L)
            }
            ToggleResult.SYSTEM_RESTRICTED -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    try { startActivity(Intent(Settings.Panel.ACTION_WIFI)) } catch (_: Exception) { openWifiManagerPage() }
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
                Toast.makeText(this, "Bluetooth Turning On...", Toast.LENGTH_SHORT).show()
                ui.postDelayed({ refreshLiveConnectivity() }, 800L)
            }
            ToggleResult.TURNED_OFF -> {
                Toast.makeText(this, "Bluetooth Turned Off", Toast.LENGTH_SHORT).show()
                ui.postDelayed({ refreshLiveConnectivity() }, 600L)
            }
            ToggleResult.SYSTEM_RESTRICTED -> {
                if (!SystemMonitor.isBluetoothEnabled(this)) {
                    try {
                        @Suppress("DEPRECATION")
                        startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQ_ENABLE_BT)
                    } catch (_: Exception) { openBluetoothManagerPage() }
                } else {
                    openBluetoothManagerPage()
                }
            }
        }
    }

    private fun updateClockAndRam(forceRamRefresh: Boolean = false) {
        val nowMs = System.currentTimeMillis()
        reusableDate.time = nowMs

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

        val ccVisible = controlOverlay.visibility == View.VISIBLE
        val ramVisible = ccVisible || (showTopRamPill && topBar.visibility == View.VISIBLE)
        val ramIntervalMs = if (ccVisible) 2000L else 4000L

        if (!ramVisible || (!forceRamRefresh && (nowMs - lastRamCheckMs) < ramIntervalMs)) {
            return
        }
        lastRamCheckMs = nowMs

        val ram = SystemMonitor.getRamInfo(this)
        val usedGb = String.format(Locale.US, "%.2f", ram.usedMb / 1024f)
        val totalGb = String.format(Locale.US, "%.2f", ram.totalMb / 1024f)

        val pillStr = "● $usedGb / $totalGb GB"
        if (pillStr != lastRamPillText) {
            lastRamPillText = pillStr
            topRamPillText.text = pillStr
        }

        // Only update & relayout Control Center RAM bar when Control Center is actually visible
        if (ccVisible || forceRamRefresh) {
            val barColor = when {
                ram.percent < 70 -> Color.parseColor("#4ADE80")
                ram.percent < 85 -> Color.parseColor("#FBBF24")
                else -> Color.parseColor("#F87171")
            }
            ccRamText.text = "TV RAM: $usedGb / $totalGb GB (${ram.percent}%) • ${ram.freeMb} MB Free"
            if (ram.percent != lastCcRamPercent || forceRamRefresh) {
                lastCcRamPercent = ram.percent
                val parentW = (ccRamBarFill.parent as? View)?.width ?: dp(280)
                if (parentW > 0) {
                    val lp = ccRamBarFill.layoutParams
                    lp.width = ((parentW * ram.percent) / 100).coerceAtLeast(dp(8))
                    ccRamBarFill.layoutParams = lp
                    (ccRamBarFill.background as? GradientDrawable)?.setColor(barColor)
                }
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
                wifiTileBox.background = glassPill(
                    if (wifiOn) argb(0x782563EB) else argb(0x52161D2C),
                    if (wifiOn) argb(0xA893C5FD) else argb(0x65FFFFFF),
                    dp(20)
                )
                btSubText.text = btText
                btTileBox.background = glassPill(
                    if (btOn) argb(0x782563EB) else argb(0x52161D2C),
                    if (btOn) argb(0xA893C5FD) else argb(0x65FFFFFF),
                    dp(20)
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
                    weatherPillText.text = "${report.tempC}°"
                    if (updatePanel && weatherOverlay.visibility == View.VISIBLE) {
                        renderWeatherPanel(report)
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
                installedApps = apps
                homeAdapter.updateApps(apps)
            }
        }
    }

    private fun launchApp(pkg: String) {
        val intent = packageManager.getLeanbackLaunchIntentForPackage(pkg)
            ?: packageManager.getLaunchIntentForPackage(pkg)
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } else {
            Toast.makeText(this, "Cannot launch $pkg", Toast.LENGTH_SHORT).show()
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
                            if (settingsOverlay.visibility == View.VISIBLE) renderSettingsDetail()
                            Toast.makeText(this, "4K Wallpaper applied!", Toast.LENGTH_SHORT).show()
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
                wifiPassOverlay.visibility == View.VISIBLE -> { wifiPassOverlay.visibility = View.GONE; return true }
                appMenuOverlay.visibility == View.VISIBLE -> { appMenuOverlay.visibility = View.GONE; return true }
                standbyOverlay.visibility == View.VISIBLE -> { standbyOverlay.visibility = View.GONE; return true }
                weatherOverlay.visibility == View.VISIBLE -> { closeWeatherPanel(); return true }
                settingsOverlay.visibility == View.VISIBLE -> { closeSettingsOverlay(); return true }
                ccWifiPage.visibility == View.VISIBLE || ccBtPage.visibility == View.VISIBLE -> {
                    stopBluetoothReceiver(); showMainControlPage(); return true
                }
                controlOverlay.visibility == View.VISIBLE -> { closeControlCenter(); return true }
                else -> { homeRecycler.smoothScrollToPosition(0); return true }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    // =========================================================================
    // APPLE TV HOME ADAPTER (SYMMETRICAL LIQUID GLASS DOCK + 6 OR 9 TILES)
    // =========================================================================
    inner class AppleTvHomeAdapter(private var allApps: List<AppItem>) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private val TYPE_SPACER = 0
        private val TYPE_TOP_SHELF = 1
        private val TYPE_GRID_APP = 2

        private val screenW get() = resources.displayMetrics.widthPixels
        private val screenH get() = resources.displayMetrics.heightPixels

        private val tileHeightPx: Int
            get() {
                val usableW = screenW - dp(80)
                val tileW = (usableW / dockColumns).coerceAtLeast(dp(56))
                return (tileW * 0.59f).toInt().coerceIn(dp(44), dp(104))
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
                    val dockTotal = tileHeightPx + dp(52)
                    val peekRow2 = dp(28)
                    val spacerH = (screenH - dockTotal - peekRow2).coerceAtLeast(dp(115))
                    val v = View(parent.context).apply { layoutParams = RecyclerView.LayoutParams(-1, spacerH) }
                    object : RecyclerView.ViewHolder(v) {}
                }
                TYPE_TOP_SHELF -> {
                    val wrapper = LinearLayout(parent.context).apply {
                        orientation = LinearLayout.VERTICAL
                        clipChildren = false
                        clipToPadding = false
                        layoutParams = RecyclerView.LayoutParams(-1, -2).apply { bottomMargin = dp(4) }
                    }
                    val frostedCapsule = LinearLayout(parent.context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(10), dp(10), dp(10), dp(10))
                        background = glassPill(argb(0x3CFFFFFF), argb(0x78FFFFFF), dp(26))
                        clipChildren = false
                        clipToPadding = false
                    }
                    val focusedLabel = TextView(parent.context).apply {
                        text = ""
                        setTextColor(Color.WHITE)
                        textSize = 12f
                        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                        gravity = Gravity.CENTER
                        setShadowLayer(5f, 0f, 1.5f, argb(0xCC000000))
                        alpha = 0f
                    }
                    wrapper.addView(frostedCapsule, LinearLayout.LayoutParams(-1, -2))
                    wrapper.addView(focusedLabel, LinearLayout.LayoutParams(-1, dp(22)).apply { topMargin = dp(4) })
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
                    for (app in topApps) {
                        val tile = buildDockTile(holder.row.context, app, holder.row, holder.focusedLabel)
                        holder.row.addView(tile, LinearLayout.LayoutParams(0, -2, 1f))
                    }
                    for (i in topApps.size until dockColumns) {
                        holder.row.addView(View(holder.row.context), LinearLayout.LayoutParams(0, 1, 1f))
                    }
                }
                is GridAppVH -> {
                    val appIdx = position - 2 + dockColumns
                    if (appIdx in allApps.indices) bindGridTile(holder.tile, allApps[appIdx])
                }
            }
        }

        private fun buildDockTile(
            ctx: Context,
            app: AppItem,
            dockRow: ViewGroup,
            sharedDockLabel: TextView
        ): FrameLayout {
            val pad = if (dockColumns == 9) dp(4) else dp(6)
            val cornerPx = (tileHeightPx * 0.20f).toInt().coerceAtLeast(dp(10))

            val container = FrameLayout(ctx).apply {
                setPadding(pad, 0, pad, 0)
                isFocusable = true
                isClickable = true
                isLongClickable = true
                clipChildren = false
                clipToPadding = false
                setOnClickListener { launchApp(app.packageName) }
                setOnLongClickListener { openAppActionMenu(app); true }
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
                TvMemoryManager.animateTvFocus(
                    view = v,
                    hasFocus = hasFocus,
                    focusedScale = 1.12f,
                    focusedTranslationZ = dp(12).toFloat(),
                    durationMs = 125L
                )
                TvMemoryManager.animateAlpha(border, if (hasFocus) 1f else 0f, 100L)
                if (hasFocus) {
                    sharedDockLabel.text = app.label
                    TvMemoryManager.animateAlpha(sharedDockLabel, 1f, 110L)
                } else {
                    // Only fade out label if focus actually left the entire dock row (fixes flicker!)
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
            val pad = if (dockColumns == 9) dp(4) else dp(7)
            val cornerPx = (tileHeightPx * 0.20f).toInt().coerceAtLeast(dp(10))
            val col = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(pad, dp(4), pad, dp(4))
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
                textSize = 11f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.CENTER
                setPadding(0, dp(4), 0, 0)
                setShadowLayer(4f, 0f, 1.5f, argb(0xCC000000))
                alpha = 0f
                visibility = View.INVISIBLE
            }
            col.addView(label, LinearLayout.LayoutParams(-1, -2))

            col.setOnFocusChangeListener { v, hasFocus ->
                TvMemoryManager.animateTvFocus(
                    view = v,
                    hasFocus = hasFocus,
                    focusedScale = 1.11f,
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

        private fun bindGridTile(tile: GridTileViews, app: AppItem) {
            tile.label.text = app.label
            tile.img.tag = app.packageName
            tile.root.setOnClickListener { launchApp(app.packageName) }
            tile.root.setOnLongClickListener { openAppActionMenu(app); true }
            loadCardInto(tile.img, app.packageName)
        }

        private fun loadCardInto(img: ImageView, pkg: String) {
            val cached = TvIconComposer.getCachedCard(pkg)
            if (cached != null && !cached.isRecycled) {
                img.setImageBitmap(cached)
            } else {
                img.setImageDrawable(glassPill(argb(0x38202838), 0, dp(12)))
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
    // UI COMPONENT HELPERS & ZERO-ALLOCATION SF-STYLE VECTOR GLYPHS
    // =========================================================================
    private fun buildToggleableTile(
        glyph: Glyph, title: String, subtitle: String,
        onShortClick: () -> Unit, onLongPress: () -> Unit
    ): Pair<LinearLayout, TextView> {
        val subTv = TextView(this).apply {
            text = subtitle; setTextColor(argb(0xDCF0F6FF)); textSize = 11f
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }
        val tile = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = glassPill(argb(0x52161D2C), argb(0x65FFFFFF), dp(20))
            isFocusable = true; isClickable = true; isLongClickable = true
            setOnClickListener { onShortClick() }
            setOnLongClickListener { onLongPress(); true }
            attachInteractiveScale(this, 1.05f)

            addView(VectorIconView(context, glyph, Color.WHITE), LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(10) })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(context).apply {
                    text = title; setTextColor(Color.WHITE); textSize = 12.5f
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
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
            text = title; setTextColor(Color.WHITE); textSize = 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }
        val subTv = if (subtitle.isNotEmpty()) TextView(this).apply {
            text = subtitle; setTextColor(argb(0xBEE2E8F0)); textSize = 10.5f
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        } else null

        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(9), dp(12), dp(9))
            background = glassPill(argb(0x22FFFFFF), argb(0x28FFFFFF), dp(14))
            isFocusable = true; isClickable = true
            setOnClickListener { onClick() }
            if (onLongClick != null) {
                isLongClickable = true
                setOnLongClickListener { onLongClick(); true }
            }

            addView(iconView, LinearLayout.LayoutParams(dp(17), dp(17)).apply { marginEnd = dp(10) })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(titleTv)
                if (subTv != null) addView(subTv)
            }, LinearLayout.LayoutParams(0, -2, 1f))

            if (badge.isNotEmpty()) {
                addView(TextView(context).apply {
                    text = badge; setTextColor(Color.parseColor("#4ADE80")); textSize = 13f; setPadding(dp(6), 0, 0, 0)
                })
            }

            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) }

            setOnFocusChangeListener { v, hasFocus ->
                v.background = if (hasFocus) glassPill(argb(0xF5FFFFFF), Color.WHITE, dp(14)) else glassPill(argb(0x22FFFFFF), argb(0x28FFFFFF), dp(14))
                val fg = if (hasFocus) Color.parseColor("#111622") else Color.WHITE
                iconView.setTint(fg)
                titleTv.setTextColor(fg)
                subTv?.setTextColor(if (hasFocus) Color.parseColor("#475569") else argb(0xBEE2E8F0))
            }
        }
    }

    private fun buildRoundAction(glyph: Glyph, label: String, onClick: () -> Unit): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(createVectorCircleBtn(glyph, dp(46), argb(0x52161D2C), Color.WHITE, onClick), LinearLayout.LayoutParams(dp(46), dp(46)))
            addView(TextView(context).apply {
                text = label; setTextColor(argb(0xE8FFFFFF)); textSize = 10f; gravity = Gravity.CENTER; setPadding(0, dp(4), 0, 0)
            })
        }
    }

    private fun createVectorCircleBtn(glyph: Glyph, sizePx: Int, fillColor: Int, iconColor: Int, onClick: () -> Unit): FrameLayout {
        return FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(fillColor)
                setStroke(dp(1), argb(0x78FFFFFF))
            }
            val iconSize = (sizePx * 0.46f).toInt()
            addView(VectorIconView(context, glyph, iconColor), FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER))
            isFocusable = true; isClickable = true
            setOnClickListener { onClick() }
            attachInteractiveScale(this, 1.12f)
        }
    }

    private fun makePillAction(label: String, bgColor: Int, textColor: Int, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label; setTextColor(textColor); textSize = 12f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(dp(13), dp(6), dp(13), dp(6))
            background = glassPill(bgColor, argb(0x50FFFFFF), dp(14))
            gravity = Gravity.CENTER
            isFocusable = true; isClickable = true
            setOnClickListener { onClick() }
            attachInteractiveScale(this, 1.07f)
        }
    }

    private fun makeSectionHeader(text: String): TextView = TextView(this).apply {
        this.text = text; setTextColor(argb(0xAAC8D7F0)); textSize = 10f
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        setPadding(dp(4), dp(8), dp(4), dp(4))
    }

    private fun buildSleekSeekBarDrawable(): Drawable {
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE; cornerRadius = dp(4).toFloat()
            setColor(argb(0x5AFFFFFF)); setSize(-1, dp(5))
        }
        val prog = ClipDrawable(
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE; cornerRadius = dp(4).toFloat()
                setColor(Color.WHITE); setSize(-1, dp(5))
            }, Gravity.START, ClipDrawable.HORIZONTAL
        )
        return LayerDrawable(arrayOf(bg, prog)).apply {
            setId(0, android.R.id.background); setId(1, android.R.id.progress)
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
            false // NEVER consume touch so onClickListener always fires immediately!
        }
    }

    private fun roundedOutline(radiusPx: Int) = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            outline.setRoundRect(0, 0, view.width, view.height, radiusPx.toFloat())
        }
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

    // ---------------- ZERO-ALLOCATION SF-STYLE CANVAS VECTORS ----------------
    enum class Glyph { WIFI, BLUETOOTH, CLOCK, SETTINGS, SYNC, WALLPAPER, TRIM, VOLUME, USER, CLOSE, SUN, DOCK }

    inner class VectorIconView(context: Context, private val glyph: Glyph, color: Int) : View(context) {
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; this.color = color
        }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; this.color = color }
        private val tmpRect = RectF()
        private val tmpPath = Path()

        fun setTint(color: Int) { strokePaint.color = color; fillPaint.color = color; invalidate() }

        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            val cx = w / 2f; val cy = h / 2f; val s = minOf(w, h)
            strokePaint.strokeWidth = (s * 0.095f).coerceAtLeast(2f)

            when (glyph) {
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