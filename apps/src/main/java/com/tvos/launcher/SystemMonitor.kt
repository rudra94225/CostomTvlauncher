package com.tvos.launcher

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.*

data class RamInfo(val usedMb: Long, val totalMb: Long, val freeMb: Long, val percent: Int)

data class WifiNet(val ssid: String, val secured: Boolean, val level: Int, val connected: Boolean)

data class BtDev(
    val name: String,
    val address: String,
    val connected: Boolean,
    val bonded: Boolean,
    val device: BluetoothDevice
)

enum class ToggleResult { TURNED_ON, TURNED_OFF, SYSTEM_RESTRICTED }

data class HourlyPoint(val label: String, val tempC: Int, val code: Int)
data class DailyPoint(val day: String, val minC: Int, val maxC: Int, val code: Int, val rainProb: Int)

data class WeatherReport(
    val city: String,
    val tempC: Int,
    val feelsLikeC: Int,
    val condition: String,
    val code: Int,
    val highC: Int,
    val lowC: Int,
    val humidity: Int,
    val windKmh: Int,
    val uvIndex: Int,
    val rainChance: Int,
    val sunrise: String,
    val sunset: String,
    val updatedAt: String,
    val hourly: List<HourlyPoint>,
    val daily: List<DailyPoint>
)

object SystemMonitor {

    private const val PREFS = "tvos_launcher_prefs"
    private const val KEY_CITY = "weather_city"
    private const val KEY_LAT = "weather_lat"
    private const val KEY_LON = "weather_lon"

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile var cachedWeather: WeatherReport? = null

    // ---------------- REAL-TIME RAM ----------------
    fun getRamInfo(context: Context): RamInfo {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        val totalMb = (mi.totalMem / (1024L * 1024L)).coerceAtLeast(1L)
        val freeMb = (mi.availMem / (1024L * 1024L)).coerceAtLeast(0L)
        val usedMb = (totalMb - freeMb).coerceAtLeast(0L)
        val pct = ((usedMb * 100L) / totalMb).toInt().coerceIn(0, 100)
        return RamInfo(usedMb, totalMb, freeMb, pct)
    }

    fun trimRam(context: Context): Long {
        val before = getRamInfo(context).freeMb
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        try {
            val apps = context.packageManager.getInstalledApplications(0)
            for (app in apps) {
                if (app.packageName != context.packageName) {
                    am.killBackgroundProcesses(app.packageName)
                }
            }
        } catch (_: Exception) {}
        TvIconComposer.clearCache()
        System.gc()
        val after = getRamInfo(context).freeMb
        return (after - before).coerceAtLeast(0L)
    }

    // ---------------- WI-FI ----------------
    fun isWifiEnabled(context: Context): Boolean {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        return wm?.isWifiEnabled == true
    }

    @Suppress("DEPRECATION")
    fun toggleWifi(context: Context): ToggleResult {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return ToggleResult.SYSTEM_RESTRICTED
        val target = !wm.isWifiEnabled
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return ToggleResult.SYSTEM_RESTRICTED
        }
        return try {
            if (wm.setWifiEnabled(target)) {
                if (target) ToggleResult.TURNED_ON else ToggleResult.TURNED_OFF
            } else {
                ToggleResult.SYSTEM_RESTRICTED
            }
        } catch (_: Exception) {
            ToggleResult.SYSTEM_RESTRICTED
        }
    }

    @Suppress("DEPRECATION")
    fun getWifiStatus(context: Context): String {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        if (wm != null && !wm.isWifiEnabled) return "Off"

        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return "Not Connected"
        val caps = cm.getNetworkCapabilities(network) ?: return "Not Connected"

        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return "Ethernet LAN"
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return "Not Connected"

        val hasLoc = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasLoc) return "Connected"

        return try {
            val rawSsid = wm?.connectionInfo?.ssid?.replace("\"", "")?.trim().orEmpty()
            if (rawSsid.isEmpty() || rawSsid.equals("<unknown ssid>", ignoreCase = true)) {
                "Connected"
            } else {
                rawSsid
            }
        } catch (_: Exception) {
            "Connected"
        }
    }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    fun scanWifiNetworks(context: Context): List<WifiNet> {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return emptyList()
        if (!wm.isWifiEnabled) return emptyList()
        val hasLoc = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasLoc) return emptyList()

        return try {
            wm.startScan()
            val activeSsid = getWifiStatus(context)
            val bestBySsid = LinkedHashMap<String, WifiNet>()
            for (r in wm.scanResults ?: emptyList()) {
                val ssid = r.SSID?.trim().orEmpty()
                if (ssid.isEmpty()) continue
                val lvl = WifiManager.calculateSignalLevel(r.level, 5)
                val sec = r.capabilities.contains("WPA") || r.capabilities.contains("WEP") || r.capabilities.contains("SAE")
                val conn = ssid == activeSsid
                val existing = bestBySsid[ssid]
                if (existing == null || lvl > existing.level) {
                    bestBySsid[ssid] = WifiNet(ssid, sec, lvl, conn)
                }
            }
            bestBySsid.values.sortedWith(compareByDescending<WifiNet> { it.connected }.thenByDescending { it.level })
        } catch (_: Exception) {
            emptyList()
        }
    }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    fun connectToWifi(
        context: Context,
        ssid: String,
        password: String?,
        secured: Boolean,
        onResult: (Boolean, String) -> Unit
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val builder = WifiNetworkSpecifier.Builder().setSsid(ssid)
                if (secured && !password.isNullOrEmpty()) builder.setWpa2Passphrase(password)
                val req = NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .setNetworkSpecifier(builder.build())
                    .build()
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                cm.requestNetwork(req, object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        cm.bindProcessToNetwork(network)
                        onResult(true, "Connected to $ssid")
                    }
                    override fun onUnavailable() {
                        onResult(false, "Could not connect to $ssid")
                    }
                })
            } catch (e: Exception) {
                onResult(false, e.message ?: "Connection failed")
            }
        } else {
            try {
                val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val cfg = WifiConfiguration().apply {
                    SSID = "\"$ssid\""
                    if (secured && !password.isNullOrEmpty()) {
                        preSharedKey = "\"$password\""
                    } else {
                        allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
                    }
                }
                val netId = wm.addNetwork(cfg)
                if (netId != -1) {
                    wm.disconnect()
                    wm.enableNetwork(netId, true)
                    wm.reconnect()
                    onResult(true, "Connecting to $ssid...")
                } else {
                    onResult(false, "Failed to save Wi-Fi config")
                }
            } catch (e: Exception) {
                onResult(false, e.message ?: "Connection failed")
            }
        }
    }

    // ---------------- BLUETOOTH ----------------
    fun isBluetoothEnabled(context: Context): Boolean {
        val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        return bm?.adapter?.isEnabled == true
    }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    fun toggleBluetooth(context: Context): ToggleResult {
        val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = bm?.adapter ?: return ToggleResult.SYSTEM_RESTRICTED
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                return ToggleResult.SYSTEM_RESTRICTED
            }
        }
        return try {
            val target = !adapter.isEnabled
            val ok = if (target) adapter.enable() else adapter.disable()
            if (ok) {
                if (target) ToggleResult.TURNED_ON else ToggleResult.TURNED_OFF
            } else {
                ToggleResult.SYSTEM_RESTRICTED
            }
        } catch (_: Exception) {
            ToggleResult.SYSTEM_RESTRICTED
        }
    }

    fun isDeviceConnected(device: BluetoothDevice): Boolean {
        return try {
            val m = device.javaClass.getMethod("isConnected")
            m.invoke(device) as? Boolean ?: false
        } catch (_: Exception) {
            false
        }
    }

    @SuppressLint("MissingPermission")
    fun getBluetoothStatus(context: Context): String {
        val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = bm?.adapter ?: return "Unavailable"
        if (!adapter.isEnabled) return "Off"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                return "On"
            }
        }
        return try {
            val names = mutableListOf<String>()
            for (d in adapter.bondedDevices ?: emptySet()) {
                if (isDeviceConnected(d)) {
                    names.add(d.name?.trim().orEmpty().ifEmpty { "Device" })
                }
            }
            when {
                names.isEmpty() -> "On"
                names.size == 1 -> names[0]
                else -> "${names[0]} +${names.size - 1}"
            }
        } catch (_: Exception) {
            "On"
        }
    }

    @SuppressLint("MissingPermission")
    fun getPairedBluetoothDevices(context: Context): List<BtDev> {
        val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = bm?.adapter ?: return emptyList()
        if (!adapter.isEnabled) return emptyList()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) return emptyList()

        return try {
            (adapter.bondedDevices ?: emptySet()).map { dev ->
                BtDev(
                    name = dev.name?.trim().orEmpty().ifEmpty { dev.address },
                    address = dev.address,
                    connected = isDeviceConnected(dev),
                    bonded = true,
                    device = dev
                )
            }.sortedWith(compareByDescending<BtDev> { it.connected }.thenBy { it.name.lowercase() })
        } catch (_: Exception) {
            emptyList()
        }
    }

    @SuppressLint("MissingPermission")
    fun startBluetoothDiscovery(context: Context): Boolean {
        val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = bm?.adapter ?: return false
        if (!adapter.isEnabled) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED
        ) return false
        return try {
            if (adapter.isDiscovering) adapter.cancelDiscovery()
            adapter.startDiscovery()
        } catch (_: Exception) {
            false
        }
    }

    @SuppressLint("MissingPermission")
    fun stopBluetoothDiscovery(context: Context) {
        val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = bm?.adapter ?: return
        try {
            if (adapter.isDiscovering) adapter.cancelDiscovery()
        } catch (_: Exception) {}
    }

    @SuppressLint("MissingPermission")
    fun pairBluetoothDevice(device: BluetoothDevice): Boolean {
        return try { device.createBond() } catch (_: Exception) { false }
    }

    fun forgetBluetoothDevice(device: BluetoothDevice): Boolean {
        return try {
            val m = device.javaClass.getMethod("removeBond")
            m.invoke(device) as? Boolean ?: false
        } catch (_: Exception) { false }
    }

    fun connectBluetoothDevice(context: Context, device: BluetoothDevice, onDone: (Boolean, String) -> Unit) {
        invokeBluetoothProfileAction(context, device, "connect", onDone)
    }

    fun disconnectBluetoothDevice(context: Context, device: BluetoothDevice, onDone: (Boolean, String) -> Unit) {
        invokeBluetoothProfileAction(context, device, "disconnect", onDone)
    }

    @SuppressLint("MissingPermission")
    private fun invokeBluetoothProfileAction(
        context: Context,
        device: BluetoothDevice,
        methodName: String,
        onDone: (Boolean, String) -> Unit
    ) {
        val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = bm?.adapter ?: run { onDone(false, "Bluetooth unavailable"); return }

        val profiles = listOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET)
        var acted = false
        for (p in profiles) {
            try {
                adapter.getProfileProxy(context.applicationContext, object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
                        try {
                            val m = proxy?.javaClass?.getMethod(methodName, BluetoothDevice::class.java)
                            m?.isAccessible = true
                            val res = m?.invoke(proxy, device) as? Boolean ?: false
                            if (res && !acted) {
                                acted = true
                                onDone(true, if (methodName == "connect") "Connecting..." else "Disconnected")
                            }
                        } catch (_: Exception) {
                        } finally {
                            try { adapter.closeProfileProxy(profile, proxy) } catch (_: Exception) {}
                        }
                    }
                    override fun onServiceDisconnected(profile: Int) {}
                }, p)
            } catch (_: Exception) {}
        }
        mainHandler.postDelayed({
            if (!acted) onDone(false, "Signal sent to ${device.name ?: "device"}")
        }, 1200L)
    }

    // ---------------- AUDIO ----------------
    fun getVolumePercent(context: Context): Int {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        return ((cur * 100f) / max).toInt().coerceIn(0, 100)
    }

    fun setVolumePercent(context: Context, percent: Int) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val target = ((percent.coerceIn(0, 100) / 100f) * max).toInt()
        am.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
    }

    fun toggleMute(context: Context): Boolean {
        val cur = getVolumePercent(context)
        return if (cur > 0) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("last_vol", cur).apply()
            setVolumePercent(context, 0)
            true
        } else {
            val prev = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("last_vol", 40)
            setVolumePercent(context, prev.coerceAtLeast(15))
            false
        }
    }

    // ---------------- FULL OPEN-METEO WEATHER + CITY SEARCH ----------------
    fun getSavedCityName(context: Context): String {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_CITY, "Raebareli") ?: "Raebareli"
    }

    fun searchAndSaveCity(context: Context, query: String): Pair<Boolean, String> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return Pair(false, "Enter a city name")
        var conn: HttpURLConnection? = null
        return try {
            val encoded = URLEncoder.encode(trimmed, "UTF-8")
            val url = URL("https://geocoding-api.open-meteo.com/v1/search?name=$encoded&count=1&language=en&format=json")
            conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 6000; readTimeout = 6000; requestMethod = "GET"
            }
            if (conn.responseCode != 200) return Pair(false, "Search failed")
            val root = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val results = root.optJSONArray("results") ?: return Pair(false, "City not found")
            if (results.length() == 0) return Pair(false, "City not found")
            val first = results.getJSONObject(0)
            val name = first.optString("name", trimmed)
            val lat = first.getDouble("latitude")
            val lon = first.getDouble("longitude")
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_CITY, name)
                .putFloat(KEY_LAT, lat.toFloat())
                .putFloat(KEY_LON, lon.toFloat())
                .apply()
            Pair(true, name)
        } catch (_: Exception) {
            Pair(false, "Network error")
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    fun fetchFullWeather(context: Context): WeatherReport? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val city = prefs.getString(KEY_CITY, "Raebareli") ?: "Raebareli"
        val lat = prefs.getFloat(KEY_LAT, 26.2145f).toDouble()
        val lon = prefs.getFloat(KEY_LON, 81.2528f).toDouble()

        var conn: HttpURLConnection? = null
        return try {
            val endpoint = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                "&current=temperature_2m,relative_humidity_2m,apparent_temperature,weather_code,wind_speed_10m" +
                "&hourly=temperature_2m,weather_code,precipitation_probability" +
                "&daily=weather_code,temperature_2m_max,temperature_2m_min,sunrise,sunset,uv_index_max,precipitation_probability_max" +
                "&timezone=auto&forecast_days=5"
            conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                connectTimeout = 6500; readTimeout = 6500; requestMethod = "GET"
            }
            if (conn.responseCode != 200) return cachedWeather
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            val cur = json.getJSONObject("current")
            val hourlyObj = json.getJSONObject("hourly")
            val dailyObj = json.getJSONObject("daily")

            val tempC = cur.optDouble("temperature_2m", 0.0).toInt()
            val feelsC = cur.optDouble("apparent_temperature", tempC.toDouble()).toInt()
            val hum = cur.optInt("relative_humidity_2m", 0)
            val wind = cur.optDouble("wind_speed_10m", 0.0).toInt()
            val code = cur.optInt("weather_code", 0)

            val hTimes = hourlyObj.getJSONArray("time")
            val hTemps = hourlyObj.getJSONArray("temperature_2m")
            val hCodes = hourlyObj.getJSONArray("weather_code")
            val hRain = hourlyObj.optJSONArray("precipitation_probability")

            val nowIsoPrefix = SimpleDateFormat("yyyy-MM-dd'T'HH", Locale.US).format(Date())
            var startIdx = 0
            for (i in 0 until hTimes.length()) {
                if (hTimes.getString(i).startsWith(nowIsoPrefix)) {
                    startIdx = i; break
                }
            }
            val hourlyList = mutableListOf<HourlyPoint>()
            var nextRainMax = 0
            for (offset in 0 until 6) {
                val idx = (startIdx + offset).coerceAtMost(hTimes.length() - 1)
                val rawTime = hTimes.getString(idx)
                val hour24 = rawTime.substringAfter("T").substringBefore(":").toIntOrNull() ?: 0
                val lbl = if (offset == 0) "Now" else formatHourShort(hour24)
                hourlyList.add(HourlyPoint(lbl, hTemps.getDouble(idx).toInt(), hCodes.getInt(idx)))
                if (hRain != null && idx < hRain.length()) {
                    nextRainMax = maxOf(nextRainMax, hRain.optInt(idx, 0))
                }
            }

            val dTimes = dailyObj.getJSONArray("time")
            val dMax = dailyObj.getJSONArray("temperature_2m_max")
            val dMin = dailyObj.getJSONArray("temperature_2m_min")
            val dCodes = dailyObj.getJSONArray("weather_code")
            val dRain = dailyObj.optJSONArray("precipitation_probability_max")
            val dSunrise = dailyObj.optJSONArray("sunrise")
            val dSunset = dailyObj.optJSONArray("sunset")
            val dUv = dailyObj.optJSONArray("uv_index_max")

            val inFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val dayFmt = SimpleDateFormat("EEE", Locale.getDefault())
            val dailyList = mutableListOf<DailyPoint>()
            for (i in 0 until minOf(5, dTimes.length())) {
                val dayLabel = if (i == 0) "Today" else {
                    try { dayFmt.format(inFmt.parse(dTimes.getString(i))!!) } catch (_: Exception) { "Day ${i + 1}" }
                }
                dailyList.add(
                    DailyPoint(
                        day = dayLabel,
                        minC = dMin.getDouble(i).toInt(),
                        maxC = dMax.getDouble(i).toInt(),
                        code = dCodes.getInt(i),
                        rainProb = dRain?.optInt(i, 0) ?: 0
                    )
                )
            }

            val sr = formatIsoTime(dSunrise?.optString(0) ?: "")
            val ss = formatIsoTime(dSunset?.optString(0) ?: "")
            val uv = dUv?.optDouble(0, 0.0)?.toInt() ?: 0
            val updated = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())

            val report = WeatherReport(
                city = city,
                tempC = tempC,
                feelsLikeC = feelsC,
                condition = weatherCodeToLabel(code),
                code = code,
                highC = dailyList.firstOrNull()?.maxC ?: tempC,
                lowC = dailyList.firstOrNull()?.minC ?: tempC,
                humidity = hum,
                windKmh = wind,
                uvIndex = uv,
                rainChance = nextRainMax,
                sunrise = sr,
                sunset = ss,
                updatedAt = updated,
                hourly = hourlyList,
                daily = dailyList
            )
            cachedWeather = report
            report
        } catch (_: Exception) {
            cachedWeather
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    fun weatherCodeToLabel(code: Int): String = when (code) {
        0 -> "Clear"
        1, 2 -> "Partly Cloudy"
        3 -> "Cloudy"
        45, 48 -> "Foggy"
        51, 53, 55, 61, 63, 65 -> "Rain"
        71, 73, 75 -> "Snow"
        80, 81, 82 -> "Showers"
        95, 96, 99 -> "Thunderstorm"
        else -> "Clear"
    }

    fun weatherCodeToEmoji(code: Int): String = when (code) {
        0 -> "☀"
        1, 2 -> "⛅"
        3 -> "☁"
        45, 48 -> "🌫"
        51, 53, 55, 61, 63, 65, 80, 81, 82 -> "🌧"
        71, 73, 75 -> "❄"
        95, 96, 99 -> "⛈"
        else -> "☀"
    }

    private fun formatHourShort(h: Int): String = when {
        h == 0 -> "12AM"
        h < 12 -> "${h}AM"
        h == 12 -> "12PM"
        else -> "${h - 12}PM"
    }

    private fun formatIsoTime(iso: String): String {
        val timePart = iso.substringAfter("T", "")
        if (timePart.isEmpty()) return "--"
        val parts = timePart.split(":")
        val h = parts.getOrNull(0)?.toIntOrNull() ?: return "--"
        val m = parts.getOrNull(1) ?: "00"
        val ampm = if (h < 12) "AM" else "PM"
        val h12 = if (h % 12 == 0) 12 else h % 12
        return "$h12:$m $ampm"
    }
}