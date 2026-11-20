package com.rayo.karoo

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale

@SuppressLint("MissingPermission")
class SettingsActivity : AppCompatActivity() {

    private lateinit var tvPairedStatus: TextView
    private lateinit var btnCommandPalette: TextView
    private lateinit var btnScanPair: TextView
    private lateinit var btnTestToggle: TextView

    private lateinit var defProfButtons: List<TextView>
    private lateinit var swRadarThreat: Switch
    private lateinit var radarProfButtons: List<TextView>
    private lateinit var holdButtons: List<TextView>
    private lateinit var swRadarSim: Switch

    private lateinit var tvLightSensorStatus: TextView
    private lateinit var swLightSensorEnabled: Switch
    private lateinit var luxButtons: List<TextView>
    private lateinit var darkProfButtons: List<TextView>
    private lateinit var brightButtons: List<TextView>

    private lateinit var swAutoStart: Switch
    private lateinit var pauseButtons: List<TextView>

    private lateinit var floatingToast: UiMotion.FloatingToastController

    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private var isScanning = false
    private val handler = Handler(Looper.getMainLooper())

    private val holdValues = listOf(1, 2, 3, 5)
    private val luxValues = listOf(50f, 150f, 400f)
    private val brightValues = listOf(-1, 0, 1, 2, 3)
    private val pauseKeys = listOf("NONE", "OFF")

    private val bleListener = object : BleManager.Listener {
        override fun onConnectionStateChange(connected: Boolean, deviceName: String?, mac: String?) {
            runOnUiThread {
                updateConnectionUi(connected, deviceName, mac)
                if (connected) {
                    floatingToast.show("Rayo verbunden")
                }
            }
        }

        override fun onBatteryUpdate(percent: Int) {
            runOnUiThread {
                val bm = RayoExtension.instance?.bleManager
                if (bm != null && bm.isConnected) {
                    updateConnectionUi(true, bm.connectedDeviceName, bm.connectedMac)
                }
            }
        }

        override fun onLightStateUpdate(isOn: Boolean) {
            runOnUiThread { updateTestButtonText(isOn) }
        }

        override fun onActiveModeUpdate(mode: Int) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        checkPermissions()
        bindViews()
        setupControls()

        try {
            startService(Intent(this, RayoExtension::class.java))
        } catch (e: Exception) {
            Log.e("SettingsActivity", "Error starting RayoExtension service", e)
        }
    }

    private fun bindViews() {
        tvPairedStatus = findViewById(R.id.tvPairedStatus)
        btnCommandPalette = findViewById(R.id.btnCommandPalette)
        btnScanPair = findViewById(R.id.btnScanPair)
        btnTestToggle = findViewById(R.id.btnTestToggle)

        defProfButtons = listOf(
            findViewById(R.id.btnDefProf0),
            findViewById(R.id.btnDefProf1),
            findViewById(R.id.btnDefProf2),
            findViewById(R.id.btnDefProf3),
        )

        swRadarThreat = findViewById(R.id.swRadarThreat)
        radarProfButtons = listOf(
            findViewById(R.id.btnRadarProf0),
            findViewById(R.id.btnRadarProf1),
            findViewById(R.id.btnRadarProf2),
            findViewById(R.id.btnRadarProf3),
        )
        holdButtons = listOf(
            findViewById(R.id.btnHold1),
            findViewById(R.id.btnHold2),
            findViewById(R.id.btnHold3),
            findViewById(R.id.btnHold5),
        )
        swRadarSim = findViewById(R.id.swRadarSim)

        tvLightSensorStatus = findViewById(R.id.tvLightSensorStatus)
        swLightSensorEnabled = findViewById(R.id.swLightSensorEnabled)
        luxButtons = listOf(
            findViewById(R.id.btnLux50),
            findViewById(R.id.btnLux150),
            findViewById(R.id.btnLux400),
        )
        darkProfButtons = listOf(
            findViewById(R.id.btnDarkProf0),
            findViewById(R.id.btnDarkProf1),
            findViewById(R.id.btnDarkProf2),
            findViewById(R.id.btnDarkProf3),
        )
        brightButtons = listOf(
            findViewById(R.id.btnBrightOff),
            findViewById(R.id.btnBrightProf0),
            findViewById(R.id.btnBrightProf1),
            findViewById(R.id.btnBrightProf2),
            findViewById(R.id.btnBrightProf3),
        )

        swAutoStart = findViewById(R.id.swAutoStart)
        pauseButtons = listOf(
            findViewById(R.id.btnPauseKeep),
            findViewById(R.id.btnPauseOff),
        )

        floatingToast = UiMotion.FloatingToastController(
            findViewById(R.id.layoutFloatingToast),
            findViewById(R.id.tvToastMessage),
            findViewById(R.id.btnToastUndo),
        )
    }

    private fun setupControls() {
        val prefs = getSharedPreferences(RayoExtension.PREFS_NAME, Context.MODE_PRIVATE)

        // Default Rayo Profile segmented control
        val savedDefProf = prefs.getInt(RayoExtension.PREF_DEFAULT_PROFILE, 0).coerceIn(0, 3)
        UiMotion.updateSegmentedButtons(defProfButtons, savedDefProf)
        defProfButtons.forEachIndexed { idx, btn ->
            btn.setOnClickListener {
                val prev = prefs.getInt(RayoExtension.PREF_DEFAULT_PROFILE, 0).coerceIn(0, 3)
                prefs.edit().putInt(RayoExtension.PREF_DEFAULT_PROFILE, idx).apply()
                UiMotion.updateSegmentedButtons(defProfButtons, idx)
                RayoExtension.instance?.bleManager?.let { bm ->
                    if (bm.isConnected && bm.currentMode != idx) bm.selectProfile(idx)
                }
                if (prev != idx) {
                    floatingToast.show("Standard-Profil: P${idx + 1}") {
                        prefs.edit().putInt(RayoExtension.PREF_DEFAULT_PROFILE, prev).apply()
                        UiMotion.updateSegmentedButtons(defProfButtons, prev)
                        RayoExtension.instance?.bleManager?.let { bm ->
                            if (bm.isConnected) bm.selectProfile(prev)
                        }
                    }
                }
            }
        }

        // Radar Threat
        swRadarThreat.isChecked = prefs.getBoolean(RayoExtension.PREF_RADAR_THREAT, true)
        swRadarThreat.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(RayoExtension.PREF_RADAR_THREAT, isChecked).apply()
        }

        val savedRadarProf = prefs.getInt(RayoExtension.PREF_RADAR_RAYO_PROFILE, 1).coerceIn(0, 3)
        UiMotion.updateSegmentedButtons(radarProfButtons, savedRadarProf)
        radarProfButtons.forEachIndexed { idx, btn ->
            btn.setOnClickListener {
                prefs.edit().putInt(RayoExtension.PREF_RADAR_RAYO_PROFILE, idx).apply()
                UiMotion.updateSegmentedButtons(radarProfButtons, idx)
                floatingToast.show("Radar-Zielprofil: P${idx + 1}")
            }
        }

        val savedHold = prefs.getInt(RayoExtension.PREF_RADAR_HOLD_SEC, 3)
        UiMotion.updateSegmentedButtons(holdButtons, holdValues.indexOf(savedHold).coerceAtLeast(2))
        holdButtons.forEachIndexed { idx, btn ->
            btn.setOnClickListener {
                val sec = holdValues[idx]
                prefs.edit().putInt(RayoExtension.PREF_RADAR_HOLD_SEC, sec).apply()
                UiMotion.updateSegmentedButtons(holdButtons, idx)
            }
        }

        swRadarSim.isChecked = prefs.getBoolean(RayoExtension.PREF_SIMULATE_RADAR, false)
        swRadarSim.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(RayoExtension.PREF_SIMULATE_RADAR, isChecked).apply()
            if (isChecked) {
                RayoExtension.instance?.startRadarSimulation()
                floatingToast.show("Radar-Simulator gestartet")
            } else {
                RayoExtension.instance?.stopRadarSimulation()
                floatingToast.show("Radar-Simulator beendet")
            }
        }

        // Ambient Light Sensor
        swLightSensorEnabled.isChecked = prefs.getBoolean(RayoExtension.PREF_LIGHT_SENSOR_ENABLED, false)
        swLightSensorEnabled.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(RayoExtension.PREF_LIGHT_SENSOR_ENABLED, isChecked).apply()
        }

        val savedLux = prefs.getFloat(RayoExtension.PREF_LIGHT_SENSOR_THRESHOLD, 150f)
        UiMotion.updateSegmentedButtons(luxButtons, luxValues.indexOf(savedLux).coerceAtLeast(1))
        luxButtons.forEachIndexed { idx, btn ->
            btn.setOnClickListener {
                prefs.edit().putFloat(RayoExtension.PREF_LIGHT_SENSOR_THRESHOLD, luxValues[idx]).apply()
                UiMotion.updateSegmentedButtons(luxButtons, idx)
            }
        }

        val savedDarkProf = prefs.getInt(RayoExtension.PREF_LIGHT_SENSOR_DARK_PROFILE, 0).coerceIn(0, 3)
        UiMotion.updateSegmentedButtons(darkProfButtons, savedDarkProf)
        darkProfButtons.forEachIndexed { idx, btn ->
            btn.setOnClickListener {
                prefs.edit().putInt(RayoExtension.PREF_LIGHT_SENSOR_DARK_PROFILE, idx).apply()
                UiMotion.updateSegmentedButtons(darkProfButtons, idx)
            }
        }

        val savedBright = prefs.getInt(RayoExtension.PREF_LIGHT_SENSOR_BRIGHT_ACTION, -1)
        UiMotion.updateSegmentedButtons(brightButtons, brightValues.indexOf(savedBright).coerceAtLeast(0))
        brightButtons.forEachIndexed { idx, btn ->
            btn.setOnClickListener {
                prefs.edit().putInt(RayoExtension.PREF_LIGHT_SENSOR_BRIGHT_ACTION, brightValues[idx]).apply()
                UiMotion.updateSegmentedButtons(brightButtons, idx)
            }
        }

        // Ride Automation
        swAutoStart.isChecked = prefs.getBoolean(RayoExtension.PREF_AUTO_START, true)
        swAutoStart.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(RayoExtension.PREF_AUTO_START, isChecked).apply()
        }

        val savedPause = prefs.getString(RayoExtension.PREF_PAUSE_BEHAVIOR, "NONE")
        UiMotion.updateSegmentedButtons(pauseButtons, pauseKeys.indexOf(savedPause).coerceAtLeast(0))
        pauseButtons.forEachIndexed { idx, btn ->
            btn.setOnClickListener {
                prefs.edit().putString(RayoExtension.PREF_PAUSE_BEHAVIOR, pauseKeys[idx]).apply()
                UiMotion.updateSegmentedButtons(pauseButtons, idx)
            }
        }

        // Primary & Secondary Buttons
        btnScanPair.setOnClickListener { startPairingScan() }
        btnTestToggle.setOnClickListener { toggleRayoFromSettings() }
        btnCommandPalette.setOnClickListener { openCommandPalette() }

        val allInteractive = listOf(btnCommandPalette, btnScanPair, btnTestToggle) +
            defProfButtons + radarProfButtons + holdButtons +
            luxButtons + darkProfButtons + brightButtons + pauseButtons
        UiMotion.attachPressFeedback(*allInteractive.toTypedArray())
    }

    private fun toggleRayoFromSettings() {
        val ext = RayoExtension.instance
        if (ext == null) {
            startService(Intent(this, RayoExtension::class.java))
            floatingToast.show("Starte Rayo-Dienst…")
            return
        }
        val bm = ext.bleManager
        if (!bm.isConnected) {
            val savedMac = getSharedPreferences(RayoExtension.PREFS_NAME, Context.MODE_PRIVATE)
                .getString(RayoExtension.PREF_MAC, null)
            if (!savedMac.isNullOrEmpty()) {
                ext.connectBle(savedMac, autoTurnOn = true)
                floatingToast.show("Verbinde mit Rayo…")
            } else {
                floatingToast.show("Bitte zuerst Rayo koppeln")
                startPairingScan()
            }
            return
        }

        val prevState = bm.isLightOn
        val newState = !prevState
        bm.setLight(newState)
        ext.updateAllRayoDataFields()
        updateTestButtonText(newState)
        floatingToast.show(if (newState) "Rayo: Licht AN" else "Rayo: Licht AUS") {
            bm.setLight(prevState)
            ext.updateAllRayoDataFields()
            updateTestButtonText(prevState)
        }
    }

    private fun openCommandPalette() {
        val prefs = getSharedPreferences(RayoExtension.PREFS_NAME, Context.MODE_PRIVATE)
        val ext = RayoExtension.instance
        val cmds = mutableListOf(
            UiMotion.CommandItem("Rayo suchen & koppeln", tvPairedStatus.text.toString()) {
                startPairingScan()
            },
            UiMotion.CommandItem("Licht umschalten (AN/AUS)", "Bluetooth-Steuerung") {
                toggleRayoFromSettings()
            },
            UiMotion.CommandItem(
                if (swRadarSim.isChecked) "Radar-Simulator stoppen" else "Radar-Simulator starten",
                "Offline Testlauf",
            ) {
                swRadarSim.isChecked = !swRadarSim.isChecked
            },
        )
        for (i in 0..3) {
            cmds.add(
                UiMotion.CommandItem("Standard-Profil P${i + 1} wählen", "Sofort aktivieren") {
                    defProfButtons[i].performClick()
                },
            )
        }
        cmds.add(
            UiMotion.CommandItem("Auto-Lichtsensor umschalten", tvLightSensorStatus.text.toString()) {
                swLightSensorEnabled.isChecked = !swLightSensorEnabled.isChecked
                floatingToast.show(
                    if (swLightSensorEnabled.isChecked) "Lichtsensor aktiviert" else "Lichtsensor deaktiviert",
                )
            },
        )
        if (ext != null) {
            cmds.add(
                UiMotion.CommandItem("Auf Werkseinstellungen zurücksetzen", "Alle Optionen auf Standard") {
                    prefs.edit()
                        .putInt(RayoExtension.PREF_DEFAULT_PROFILE, 0)
                        .putInt(RayoExtension.PREF_RADAR_RAYO_PROFILE, 1)
                        .putInt(RayoExtension.PREF_RADAR_HOLD_SEC, 3)
                        .putBoolean(RayoExtension.PREF_RADAR_THREAT, true)
                        .putBoolean(RayoExtension.PREF_LIGHT_SENSOR_ENABLED, false)
                        .putFloat(RayoExtension.PREF_LIGHT_SENSOR_THRESHOLD, 150f)
                        .putInt(RayoExtension.PREF_LIGHT_SENSOR_DARK_PROFILE, 0)
                        .putInt(RayoExtension.PREF_LIGHT_SENSOR_BRIGHT_ACTION, -1)
                        .putBoolean(RayoExtension.PREF_AUTO_START, true)
                        .putString(RayoExtension.PREF_PAUSE_BEHAVIOR, "NONE")
                        .apply()
                    recreate()
                },
            )
        }
        UiMotion.showCommandPalette(this, cmds)
    }

    override fun onResume() {
        super.onResume()
        val ext = RayoExtension.instance
        ext?.bleManager?.addListener(bleListener)

        ext?.onSimulationStep = { _, label ->
            runOnUiThread {
                floatingToast.show(label)
            }
        }

        if (ext != null) {
            updateLightSensorLabel(ext.hasLightSensor, ext.currentLux, ext.isDarkEnvironment)
            ext.onLightSensorUpdate = { lux, isDark ->
                runOnUiThread {
                    updateLightSensorLabel(ext.hasLightSensor, lux, isDark)
                }
            }
        }

        val bm = ext?.bleManager
        if (bm != null && bm.isConnected) {
            updateConnectionUi(true, bm.connectedDeviceName, bm.connectedMac)
        } else {
            val mac = getSharedPreferences(RayoExtension.PREFS_NAME, Context.MODE_PRIVATE)
                .getString(RayoExtension.PREF_MAC, null)
            updateConnectionUi(false, null, mac)
            if (!mac.isNullOrEmpty()) {
                ext?.connectBle(mac)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        RayoExtension.instance?.bleManager?.removeListener(bleListener)
        RayoExtension.instance?.onSimulationStep = null
        RayoExtension.instance?.onLightSensorUpdate = null
    }

    private fun updateConnectionUi(connected: Boolean, deviceName: String?, mac: String?) {
        val bm = RayoExtension.instance?.bleManager
        if (connected) {
            val bat = bm?.batteryPercent?.takeIf { it >= 0 }?.let { " · $it%" } ?: ""
            tvPairedStatus.text = "${deviceName ?: "RAYO"}$bat"
            tvPairedStatus.setTextColor(getColor(R.color.status_ok_fg))

            // Single Focal CTA moves from Pair to Power Test once paired & connected
            btnScanPair.setBackgroundResource(R.drawable.bg_btn_secondary)
            btnScanPair.setTextColor(getColor(R.color.text_primary))
            btnScanPair.text = "Anderes Rayo koppeln"

            btnTestToggle.setBackgroundResource(R.drawable.bg_btn_primary)
            btnTestToggle.setTextColor(getColor(R.color.accent_on_primary))
            updateTestButtonText(bm?.isLightOn ?: false)
        } else {
            if (!mac.isNullOrEmpty()) {
                tvPairedStatus.text = "Gekoppelt: $mac (getrennt)"
                tvPairedStatus.setTextColor(getColor(R.color.status_warn_fg))
            } else {
                tvPairedStatus.text = getString(R.string.not_paired)
                tvPairedStatus.setTextColor(getColor(R.color.text_secondary))
            }

            btnScanPair.setBackgroundResource(R.drawable.bg_btn_primary)
            btnScanPair.setTextColor(getColor(R.color.accent_on_primary))
            btnScanPair.text = getString(R.string.btn_scan_pair)

            btnTestToggle.setBackgroundResource(R.drawable.bg_btn_secondary)
            btnTestToggle.setTextColor(getColor(R.color.text_primary))
            btnTestToggle.text = "Rayo verbinden & testen"
        }
    }

    private fun updateLightSensorLabel(hasSensor: Boolean, lux: Float, isDark: Boolean) {
        if (!hasSensor) {
            tvLightSensorStatus.text = "Nicht verfügbar"
            tvLightSensorStatus.setTextColor(getColor(R.color.destructive_tint_fg))
        } else if (lux < 0f) {
            tvLightSensorStatus.text = "Warte…"
            tvLightSensorStatus.setTextColor(getColor(R.color.text_secondary))
        } else {
            val stateText = if (isDark) "Dunkel" else "Hell"
            tvLightSensorStatus.text = "${lux.toInt()} lx · $stateText"
            tvLightSensorStatus.setTextColor(
                getColor(if (isDark) R.color.status_warn_fg else R.color.status_ok_fg),
            )
        }
    }

    private fun updateTestButtonText(isLightOn: Boolean) {
        val isConnected = RayoExtension.instance?.bleManager?.isConnected == true
        if (!isConnected) {
            btnTestToggle.text = "Rayo verbinden & testen"
            return
        }
        btnTestToggle.text = if (isLightOn) {
            "Licht AN (Tippen für AUS)"
        } else {
            "Licht AUS (Tippen für AN)"
        }
    }

    private fun startPairingScan() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            floatingToast.show("Bluetooth ist deaktiviert!")
            return
        }
        if (isScanning) return

        val scanner = bluetoothAdapter.bluetoothLeScanner ?: return
        isScanning = true
        floatingToast.show(getString(R.string.status_scanning))
        btnScanPair.isEnabled = false

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                val dev = result?.device ?: return
                val name = dev.name ?: ""
                val address = dev.address

                if (name.contains("RAYO", ignoreCase = true) || name.contains("CLIQ", ignoreCase = true)) {
                    stopScan(scanner, this)
                    savePairedDevice(name, address)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                isScanning = false
                btnScanPair.isEnabled = true
                floatingToast.show("Scan fehlgeschlagen ($errorCode)")
            }
        }

        try {
            scanner.startScan(null, settings, callback)
            handler.postDelayed({
                if (isScanning) {
                    stopScan(scanner, callback)
                    floatingToast.show(getString(R.string.status_not_found))
                }
            }, 12000)
        } catch (e: Exception) {
            isScanning = false
            btnScanPair.isEnabled = true
            floatingToast.show("Scan-Fehler: ${e.message}")
        }
    }

    private fun stopScan(scanner: android.bluetooth.le.BluetoothLeScanner, callback: ScanCallback) {
        if (isScanning) {
            try {
                scanner.stopScan(callback)
            } catch (ignored: Exception) {}
            isScanning = false
            handler.post { btnScanPair.isEnabled = true }
        }
    }

    private fun savePairedDevice(name: String, mac: String) {
        val prefs = getSharedPreferences(RayoExtension.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(RayoExtension.PREF_MAC, mac).apply()

        tvPairedStatus.text = "Gekoppelt: $name ($mac)"
        tvPairedStatus.setTextColor(getColor(R.color.status_ok_fg))
        floatingToast.show("Rayo gekoppelt! Verbinde…")

        startService(Intent(this, RayoExtension::class.java))
        RayoExtension.instance?.connectBle(mac)
    }

    private fun checkPermissions() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        } else {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }
        if (needed.isNotEmpty()) {
            requestPermissions(needed.toTypedArray(), 100)
        }
    }
}
