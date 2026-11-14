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
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale

@SuppressLint("MissingPermission")
class SettingsActivity : AppCompatActivity() {

    // 1. Rayo BLE Views
    private lateinit var tvPairedStatus: TextView
    private lateinit var btnScanPair: Button
    private lateinit var spnDefaultProfile: Spinner
    private lateinit var btnTestToggle: Button

    // 2. Radar Threat Views
    private lateinit var swRadarThreat: Switch
    private lateinit var spnRadarRayoProfile: Spinner
    private lateinit var spnRadarHoldSec: Spinner
    private lateinit var swRadarSim: Switch

    // 3. G-Brake Views
    private lateinit var swBrakeEnabled: Switch
    private lateinit var spnBrakeSensitivity: Spinner
    private lateinit var spnBrakeHoldSec: Spinner

    // 4. Speed-Adaptive Views
    private lateinit var swSpeedAdaptive: Switch
    private lateinit var spnSpeedThreshold: Spinner

    // 5. Automation Views
    private lateinit var swAutoStart: Switch
    private lateinit var spnPauseBehavior: Spinner
    private lateinit var swInRideAlerts: Switch
    private lateinit var tvSettingsLog: TextView

    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private var isScanning = false
    private val handler = Handler(Looper.getMainLooper())

    private val bleListener = object : BleManager.Listener {
        override fun onConnectionStateChange(connected: Boolean, deviceName: String?, mac: String?) {
            runOnUiThread {
                if (connected) {
                    val bat = RayoExtension.instance?.bleManager?.batteryPercent ?: -1
                    val batStr = if (bat >= 0) " ($bat%)" else ""
                    tvPairedStatus.text = "Verbunden: ${deviceName ?: "RAYO"} [$mac]$batStr"
                    tvPairedStatus.setTextColor(0xFF00E676.toInt())
                    tvSettingsLog.text = "Rayo verbunden!"
                    updateTestButtonText(RayoExtension.instance?.bleManager?.isLightOn ?: false)
                } else {
                    val savedMac = getSharedPreferences(RayoExtension.PREFS_NAME, Context.MODE_PRIVATE)
                        .getString(RayoExtension.PREF_MAC, null)
                    if (!savedMac.isNullOrEmpty()) {
                        tvPairedStatus.text = "Gekoppelt: $savedMac (getrennt)"
                        tvPairedStatus.setTextColor(0xFFFFAA00.toInt())
                    } else {
                        tvPairedStatus.text = getString(R.string.not_paired)
                        tvPairedStatus.setTextColor(0xFFFFFFFF.toInt())
                    }
                    btnTestToggle.text = "Rayo verbinden & testen"
                    tvSettingsLog.text = "Rayo nicht verbunden."
                }
            }
        }

        override fun onBatteryUpdate(percent: Int) {
            runOnUiThread {
                val name = RayoExtension.instance?.bleManager?.connectedDeviceName ?: "RAYO"
                val mac = RayoExtension.instance?.bleManager?.connectedMac ?: ""
                tvPairedStatus.text = "Verbunden: $name [$mac] ($percent%)"
            }
        }

        override fun onLightStateUpdate(isOn: Boolean) {
            runOnUiThread {
                updateTestButtonText(isOn)
            }
        }

        override fun onActiveModeUpdate(mode: Int) {
            runOnUiThread {
                if (spnDefaultProfile.selectedItemPosition != mode) {
                    spnDefaultProfile.setSelection(mode)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        bindViews()
        checkPermissions()
        loadPreferences()

        // Ensure background extension service is running
        try {
            startService(Intent(this, RayoExtension::class.java))
        } catch (e: Exception) {
            Log.e("SettingsActivity", "Error starting RayoExtension service", e)
        }

        setupSpinnersAndSwitches()
        setupClickListeners()
    }

    private fun bindViews() {
        tvPairedStatus = findViewById(R.id.tvPairedStatus)
        btnScanPair = findViewById(R.id.btnScanPair)
        spnDefaultProfile = findViewById(R.id.spnDefaultProfile)
        btnTestToggle = findViewById(R.id.btnTestToggle)

        swRadarThreat = findViewById(R.id.swRadarThreat)
        spnRadarRayoProfile = findViewById(R.id.spnRadarRayoProfile)
        spnRadarHoldSec = findViewById(R.id.spnRadarHoldSec)
        swRadarSim = findViewById(R.id.swRadarSim)

        swBrakeEnabled = findViewById(R.id.swBrakeEnabled)
        spnBrakeSensitivity = findViewById(R.id.spnBrakeSensitivity)
        spnBrakeHoldSec = findViewById(R.id.spnBrakeHoldSec)

        swSpeedAdaptive = findViewById(R.id.swSpeedAdaptive)
        spnSpeedThreshold = findViewById(R.id.spnSpeedThreshold)

        swAutoStart = findViewById(R.id.swAutoStart)
        spnPauseBehavior = findViewById(R.id.spnPauseBehavior)
        swInRideAlerts = findViewById(R.id.swInRideAlerts)
        tvSettingsLog = findViewById(R.id.tvSettingsLog)
    }

    private fun setupSpinnersAndSwitches() {
        val prefs = getSharedPreferences(RayoExtension.PREFS_NAME, Context.MODE_PRIVATE)

        // Rayo Profile
        val profiles = arrayOf("Profil 1", "Profil 2", "Profil 3", "Profil 4")
        spnDefaultProfile.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, profiles)
        spnDefaultProfile.setSelection(prefs.getInt(RayoExtension.PREF_DEFAULT_PROFILE, 0))
        spnDefaultProfile.onItemSelectedListener = simpleSelectListener { pos ->
            prefs.edit().putInt(RayoExtension.PREF_DEFAULT_PROFILE, pos).apply()
            RayoExtension.instance?.bleManager?.let { bm ->
                if (bm.isConnected && bm.currentMode != pos) bm.selectProfile(pos)
            }
        }

        // Radar Threat -> Rayo Target Profile (Default: Profil 2 = index 1)
        spnRadarRayoProfile.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, profiles)
        spnRadarRayoProfile.setSelection(prefs.getInt(RayoExtension.PREF_RADAR_RAYO_PROFILE, 1).coerceIn(0, 3))
        spnRadarRayoProfile.onItemSelectedListener = simpleSelectListener { pos ->
            prefs.edit().putInt(RayoExtension.PREF_RADAR_RAYO_PROFILE, pos).apply()
        }

        // Radar Hold Sec
        val holdSecOptions = listOf(1, 2, 3, 5)
        val holdSecLabels = holdSecOptions.map { "$it Sekunden" }
        spnRadarHoldSec.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, holdSecLabels)
        val savedHold = prefs.getInt(RayoExtension.PREF_RADAR_HOLD_SEC, 3)
        spnRadarHoldSec.setSelection(holdSecOptions.indexOf(savedHold).coerceAtLeast(0))
        spnRadarHoldSec.onItemSelectedListener = simpleSelectListener { pos ->
            prefs.edit().putInt(RayoExtension.PREF_RADAR_HOLD_SEC, holdSecOptions[pos]).apply()
        }

        // Radar Simulator Switch
        swRadarSim.isChecked = prefs.getBoolean(RayoExtension.PREF_SIMULATE_RADAR, false)
        swRadarSim.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(RayoExtension.PREF_SIMULATE_RADAR, isChecked).apply()
            if (isChecked) {
                RayoExtension.instance?.startRadarSimulation()
                tvSettingsLog.text = "🧪 Radar-Simulator gestartet!"
            } else {
                RayoExtension.instance?.stopRadarSimulation()
                tvSettingsLog.text = "Radar-Simulator beendet."
            }
        }

        // G-Brake Sensitivity
        val sensValues = listOf(0.15f, 0.20f, 0.30f, 0.40f)
        val sensLabels = listOf("0.15 g (Sehr empfindlich)", "0.20 g (Normal / Empfohlen)", "0.30 g (Sportlich)", "0.40 g (Nur starkes Bremsen)")
        spnBrakeSensitivity.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, sensLabels)
        val savedSens = prefs.getFloat(RayoExtension.PREF_BRAKE_THRESHOLD, 0.20f)
        spnBrakeSensitivity.setSelection(sensValues.indexOf(savedSens).coerceAtLeast(0))
        spnBrakeSensitivity.onItemSelectedListener = simpleSelectListener { pos ->
            prefs.edit().putFloat(RayoExtension.PREF_BRAKE_THRESHOLD, sensValues[pos]).apply()
        }

        // G-Brake Hold Sec
        val brakeHoldOptions = listOf(1, 2, 3, 5)
        val brakeHoldLabels = brakeHoldOptions.map { "$it Sekunden" }
        spnBrakeHoldSec.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, brakeHoldLabels)
        val savedBrakeHold = prefs.getInt(RayoExtension.PREF_BRAKE_HOLD_SEC, 2)
        spnBrakeHoldSec.setSelection(brakeHoldOptions.indexOf(savedBrakeHold).coerceAtLeast(0))
        spnBrakeHoldSec.onItemSelectedListener = simpleSelectListener { pos ->
            prefs.edit().putInt(RayoExtension.PREF_BRAKE_HOLD_SEC, brakeHoldOptions[pos]).apply()
        }

        // Speed Adaptive Threshold
        val speedOptions = listOf(20, 25, 28, 30, 35)
        val speedLabels = speedOptions.map { "Ab $it km/h" }
        spnSpeedThreshold.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, speedLabels)
        val savedSpeedThresh = prefs.getInt(RayoExtension.PREF_SPEED_THRESHOLD, 28)
        spnSpeedThreshold.setSelection(speedOptions.indexOf(savedSpeedThresh).coerceAtLeast(0))
        spnSpeedThreshold.onItemSelectedListener = simpleSelectListener { pos ->
            prefs.edit().putInt(RayoExtension.PREF_SPEED_THRESHOLD, speedOptions[pos]).apply()
        }

        // Pause Behavior
        val pauseKeys = listOf("NONE", "LOW", "OFF")
        val pauseLabels = listOf("Licht unverändert anlassen", "Auf Sparmodus (Dauerlicht Spar) dimmen", "Licht komplett ausschalten")
        spnPauseBehavior.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, pauseLabels)
        val savedPause = prefs.getString(RayoExtension.PREF_PAUSE_BEHAVIOR, "NONE")
        spnPauseBehavior.setSelection(pauseKeys.indexOf(savedPause).coerceAtLeast(0))
        spnPauseBehavior.onItemSelectedListener = simpleSelectListener { pos ->
            prefs.edit().putString(RayoExtension.PREF_PAUSE_BEHAVIOR, pauseKeys[pos]).apply()
        }

        // Switches
        swRadarThreat.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(RayoExtension.PREF_RADAR_THREAT, isChecked).apply()
        }
        swBrakeEnabled.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(RayoExtension.PREF_BRAKE_ENABLED, isChecked).apply()
        }
        swSpeedAdaptive.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(RayoExtension.PREF_SPEED_ADAPTIVE_ENABLED, isChecked).apply()
        }
        swAutoStart.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(RayoExtension.PREF_AUTO_START, isChecked).apply()
        }
        swInRideAlerts.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(RayoExtension.PREF_IN_RIDE_ALERTS, isChecked).apply()
        }
    }

    private fun setupClickListeners() {
        val prefs = getSharedPreferences(RayoExtension.PREFS_NAME, Context.MODE_PRIVATE)
        val isGerman = Locale.getDefault().language.startsWith("de")

        btnScanPair.setOnClickListener {
            startPairingScan()
        }

        btnTestToggle.setOnClickListener {
            val ext = RayoExtension.instance
            if (ext == null) {
                startService(Intent(this, RayoExtension::class.java))
                tvSettingsLog.text = "Starte Rayo-Dienst… bitte gleich nochmal tippen."
                return@setOnClickListener
            }

            val bm = ext.bleManager
            if (!bm.isConnected) {
                val mac = prefs.getString(RayoExtension.PREF_MAC, null)
                if (mac.isNullOrEmpty()) {
                    tvSettingsLog.text = "Bitte zuerst ein Rayo koppeln!"
                } else {
                    tvSettingsLog.text = "Verbinde mit Rayo ($mac)…"
                    ext.connectBle(mac)
                }
                return@setOnClickListener
            }

            val newState = !bm.isLightOn
            bm.setLight(newState)
            ext.updateAllRayoDataFields()
            tvSettingsLog.text = if (newState) "Rayo: LICHT AN!" else "Rayo: LICHT AUS!"
            updateTestButtonText(newState)
        }
    }

    private fun simpleSelectListener(action: (Int) -> Unit) = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
            action(position)
        }
        override fun onNothingSelected(parent: AdapterView<*>?) {}
    }

    override fun onResume() {
        super.onResume()
        RayoExtension.instance?.bleManager?.addListener(bleListener)

        RayoExtension.instance?.onSimulationStep = { _, label ->
            runOnUiThread {
                tvSettingsLog.text = label
            }
        }

        val bm = RayoExtension.instance?.bleManager
        if (bm != null && bm.isConnected) {
            val bat = if (bm.batteryPercent >= 0) " (${bm.batteryPercent}%)" else ""
            tvPairedStatus.text = "Verbunden: ${bm.connectedDeviceName ?: "RAYO"} [${bm.connectedMac}]$bat"
            tvPairedStatus.setTextColor(0xFF00E676.toInt())
            updateTestButtonText(bm.isLightOn)
        } else {
            val mac = getSharedPreferences(RayoExtension.PREFS_NAME, Context.MODE_PRIVATE)
                .getString(RayoExtension.PREF_MAC, null)
            if (!mac.isNullOrEmpty()) {
                tvPairedStatus.text = "Gekoppelt: $mac (getrennt)"
                tvPairedStatus.setTextColor(0xFFFFAA00.toInt())
                RayoExtension.instance?.connectBle(mac)
            }
            btnTestToggle.text = "Rayo verbinden & testen"
        }
    }

    override fun onPause() {
        super.onPause()
        RayoExtension.instance?.bleManager?.removeListener(bleListener)
        RayoExtension.instance?.onSimulationStep = null
    }

    private fun updateTestButtonText(isLightOn: Boolean) {
        if (isLightOn) {
            btnTestToggle.text = "Rayo: LICHT AN (Tippen für AUS)"
            btnTestToggle.setBackgroundColor(0xFF00E676.toInt())
            btnTestToggle.setTextColor(0xFF000000.toInt())
        } else {
            btnTestToggle.text = "Rayo: LICHT AUS (Tippen für AN)"
            btnTestToggle.setBackgroundColor(0xFF282A33.toInt())
            btnTestToggle.setTextColor(0xFFFFFFFF.toInt())
        }
    }

    private fun loadPreferences() {
        val prefs = getSharedPreferences(RayoExtension.PREFS_NAME, Context.MODE_PRIVATE)
        val mac = prefs.getString(RayoExtension.PREF_MAC, null)
        if (!mac.isNullOrEmpty()) {
            tvPairedStatus.text = getString(R.string.paired_device_format, mac)
            tvPairedStatus.setTextColor(0xFFFFAA00.toInt())
        } else {
            tvPairedStatus.text = getString(R.string.not_paired)
            tvPairedStatus.setTextColor(0xFFFFFFFF.toInt())
        }

        swRadarThreat.isChecked = prefs.getBoolean(RayoExtension.PREF_RADAR_THREAT, true)
        swBrakeEnabled.isChecked = prefs.getBoolean(RayoExtension.PREF_BRAKE_ENABLED, true)
        swSpeedAdaptive.isChecked = prefs.getBoolean(RayoExtension.PREF_SPEED_ADAPTIVE_ENABLED, false)
        swAutoStart.isChecked = prefs.getBoolean(RayoExtension.PREF_AUTO_START, true)
        swInRideAlerts.isChecked = prefs.getBoolean(RayoExtension.PREF_IN_RIDE_ALERTS, true)
    }

    private fun startPairingScan() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            tvSettingsLog.text = "Bluetooth ist deaktiviert!"
            return
        }
        if (isScanning) return

        val scanner = bluetoothAdapter.bluetoothLeScanner ?: return
        isScanning = true
        tvSettingsLog.text = getString(R.string.status_scanning)
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
                tvSettingsLog.text = "Scan fehlgeschlagen ($errorCode)"
            }
        }

        try {
            scanner.startScan(null, settings, callback)
            handler.postDelayed({
                if (isScanning) {
                    stopScan(scanner, callback)
                    tvSettingsLog.text = getString(R.string.status_not_found)
                }
            }, 12000)
        } catch (e: Exception) {
            isScanning = false
            btnScanPair.isEnabled = true
            tvSettingsLog.text = "Fehler beim Scannen: ${e.message}"
        }
    }

    private fun stopScan(scanner: android.bluetooth.le.BluetoothLeScanner, callback: ScanCallback) {
        if (isScanning) {
            try { scanner.stopScan(callback) } catch (ignored: Exception) {}
            isScanning = false
            handler.post { btnScanPair.isEnabled = true }
        }
    }

    private fun savePairedDevice(name: String, mac: String) {
        val prefs = getSharedPreferences(RayoExtension.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(RayoExtension.PREF_MAC, mac).apply()

        tvPairedStatus.text = "Gekoppelt: $name ($mac)"
        tvPairedStatus.setTextColor(0xFF00E676.toInt())
        tvSettingsLog.text = "Rayo gekoppelt! Verbinde…"

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
