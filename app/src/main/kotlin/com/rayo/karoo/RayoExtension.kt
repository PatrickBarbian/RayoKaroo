package com.rayo.karoo

import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import android.widget.RemoteViews
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.BatteryStatus
import io.hammerhead.karooext.models.ConnectionStatus
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.Device
import io.hammerhead.karooext.models.DeviceEvent
import io.hammerhead.karooext.models.InRideAlert
import io.hammerhead.karooext.models.OnBatteryStatus
import io.hammerhead.karooext.models.OnConnectionStatus
import io.hammerhead.karooext.models.OnDataPoint
import io.hammerhead.karooext.models.OnStreamState
import io.hammerhead.karooext.models.ReleaseBluetooth
import io.hammerhead.karooext.models.RequestBluetooth
import io.hammerhead.karooext.models.RideState
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.UpdateGraphicConfig
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.coroutines.*
import java.util.Locale

class RayoExtension : KarooExtension("rayo", "1.0.0") {

    companion object {
        private const val TAG = "RayoExtension"

        var instance: RayoExtension? = null
            private set

        // Intents for in-ride touch actions
        const val ACTION_TOGGLE_LIGHT = "com.rayo.karoo.ACTION_TOGGLE_LIGHT"
        const val ACTION_CYCLE_PROFILE = "com.rayo.karoo.ACTION_CYCLE_PROFILE"

        // SharedPreferences Keys
        const val PREFS_NAME = "rayo_karoo_prefs"
        const val PREF_MAC = "paired_mac"
        const val PREF_AUTO_START = "auto_start_enabled"
        const val PREF_DEFAULT_PROFILE = "default_profile"

        // Radar Threat -> Rayo Profile settings
        const val PREF_RADAR_THREAT = "radar_threat_enabled"
        const val PREF_RADAR_RAYO_PROFILE = "radar_rayo_profile"
        const val PREF_RADAR_HOLD_SEC = "radar_hold_sec"
        const val PREF_SIMULATE_RADAR = "simulate_radar"

        // G-Brake settings
        const val PREF_BRAKE_ENABLED = "brake_enabled"
        const val PREF_BRAKE_THRESHOLD = "brake_threshold"
        const val PREF_BRAKE_HOLD_SEC = "brake_hold_sec"

        // Speed Adaptive settings
        const val PREF_SPEED_ADAPTIVE_ENABLED = "speed_adaptive_enabled"
        const val PREF_SPEED_THRESHOLD = "speed_threshold"

        // Pause & Alert settings
        const val PREF_PAUSE_BEHAVIOR = "pause_behavior"
        const val PREF_IN_RIDE_ALERTS = "in_ride_alerts"

        // Extension Data Types
        const val TYPE_RAYO_CONTROL = "rayo_control"
        const val TYPE_RAYO_BATTERY = "rayo_battery"
    }

    lateinit var bleManager: BleManager
        private set

    lateinit var karooSystem: KarooSystemService
        private set

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Emitters for Rayo Data Fields
    private val activeRayoViewEmitters = mutableListOf<ViewEmitter>()
    private val activeRayoBatteryEmitters = mutableListOf<Emitter<StreamState>>()

    // --- State variables ---
    var isRadarThreatActive: Boolean = false
        private set
    var currentThreatLevel: Int = 0
        private set

    @Volatile var isBrakingActive: Boolean = false
        private set

    @Volatile var isSpeedAdaptiveActive: Boolean = false
        private set

    @Volatile var currentSpeedKmh: Float = 0f
        private set

    private var preThreatRayoProfile: Int? = null
    private var threatHoldJob: Job? = null
    private var brakeHoldJob: Job? = null
    private var radarSimulatorJob: Job? = null

    var onSimulationStep: ((threatLevel: Int, label: String) -> Unit)? = null

    private var radarConsumerId: String? = null
    private var speedConsumerId: String? = null

    // G-Sensor Accelerometer
    private var sensorManager: SensorManager? = null
    private var accelListener: SensorEventListener? = null

    override val types by lazy {
        listOf(
            RayoControlDataType(this, extension),
            RayoBatteryDataType(this, extension)
        )
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        karooSystem = KarooSystemService(this)

        // Setup G-Sensor Accelerometer for G-Force Bremslicht
        setupGForceBrakeSensor()

        // BLE Manager for Rayo
        bleManager = BleManager(this, object : BleManager.Listener {
            override fun onConnectionStateChange(connected: Boolean, deviceName: String?, mac: String?) {
                updateAllRayoDataFields()
                updateRayoBatteryStreams()
            }

            override fun onBatteryUpdate(percent: Int) {
                updateAllRayoDataFields()
                updateRayoBatteryStreams()
            }

            override fun onLightStateUpdate(isOn: Boolean) {
                updateAllRayoDataFields()
            }

            override fun onActiveModeUpdate(mode: Int) {
                updateAllRayoDataFields()
            }
        })

        // Auto-connect to paired Rayo MAC if saved
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedMac = prefs.getString(PREF_MAC, null)
        if (!savedMac.isNullOrEmpty()) {
            bleManager.connect(savedMac)
        }

        // Connect Karoo System Service and setup consumers
        karooSystem.connect { connected ->
            Log.d(TAG, "Karoo System connected: $connected")
            if (connected) {
                try {
                    karooSystem.dispatch(RequestBluetooth(extension))
                } catch (ignored: Exception) {}
                setupKarooConsumers()
            }
        }

        // Start radar simulator if enabled in settings
        if (prefs.getBoolean(PREF_SIMULATE_RADAR, false)) {
            startRadarSimulation()
        }
    }

    private fun setupKarooConsumers() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // 1. Ride State Consumer (Start, Pause, Resume, Idle)
        karooSystem.addConsumer<RideState> { rideState ->
            val autoStart = prefs.getBoolean(PREF_AUTO_START, true)

            when (rideState) {
                is RideState.Recording -> {
                    // Ride started or resumed!
                    try {
                        karooSystem.dispatch(RequestBluetooth(extension))
                    } catch (ignored: Exception) {}

                    if (autoStart) {
                        // Start Rayo using Scan-then-Connect
                        val savedMac = prefs.getString(PREF_MAC, null)
                        if (!savedMac.isNullOrEmpty() && !bleManager.isConnected) {
                            bleManager.connect(savedMac, autoTurnOnAfterConnect = true)
                        } else if (bleManager.isConnected) {
                            val defProfile = prefs.getInt(PREF_DEFAULT_PROFILE, 0)
                            bleManager.selectProfile(defProfile)
                            bleManager.setLight(true)
                        }
                    }
                }
                is RideState.Paused -> {
                    // Ride paused
                    val pauseBehavior = prefs.getString(PREF_PAUSE_BEHAVIOR, "NONE") ?: "NONE"
                    if (pauseBehavior == "OFF") {
                        if (bleManager.isConnected) bleManager.setLight(false)
                    }
                }
                is RideState.Idle -> {
                    // Ride ended! Turn off Rayo light
                    if (bleManager.isConnected) {
                        bleManager.setLight(false)
                    }
                    try {
                        karooSystem.dispatch(ReleaseBluetooth(extension))
                    } catch (ignored: Exception) {}
                }
                else -> {}
            }
        }

        // 2. Radar Threat Stream
        startRadarMonitoring()

        // 3. GPS Speed Stream for Speed-Adaptive Lighting
        startSpeedMonitoring()
    }

    // --- G-FORCE BRAKE LIGHT ACCELEROMETER ---

    private fun setupGForceBrakeSensor() {
        if (sensorManager == null) {
            sensorManager = getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        }
        val accel = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return

        accelListener = object : SensorEventListener {
            private var lastY = 0f
            private var continuousBrakeStartTime = 0L

            override fun onSensorChanged(event: SensorEvent?) {
                if (event == null || event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
                val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                if (!prefs.getBoolean(PREF_BRAKE_ENABLED, true)) return

                val y = event.values[1]
                val deltaY = y - lastY
                lastY = y

                val decelerationG = -deltaY / 9.81f
                val thresholdG = prefs.getFloat(PREF_BRAKE_THRESHOLD, 0.20f)
                val now = System.currentTimeMillis()

                if (decelerationG >= thresholdG) {
                    if (continuousBrakeStartTime == 0L) {
                        continuousBrakeStartTime = now
                    } else if (now - continuousBrakeStartTime >= 200L) {
                        if (!isBrakingActive) {
                            isBrakingActive = true
                            Log.d(TAG, "G-Brake triggered! Deceleration=$decelerationG g")
                            applyBrakeLight()
                        }
                        val holdSec = prefs.getInt(PREF_BRAKE_HOLD_SEC, 2)
                        brakeHoldJob?.cancel()
                        brakeHoldJob = scope.launch {
                            delay(holdSec * 1000L)
                            isBrakingActive = false
                            continuousBrakeStartTime = 0L
                            clearBrakeLight()
                        }
                    }
                } else {
                    if (!isBrakingActive) {
                        continuousBrakeStartTime = 0L
                    }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        sensorManager?.registerListener(accelListener, accel, SensorManager.SENSOR_DELAY_GAME)
    }

    private fun applyBrakeLight() {
        if (isRadarThreatActive) return
    }

    private fun clearBrakeLight() {
        if (isRadarThreatActive) return
    }

    // --- SPEED ADAPTIVE LIGHTING ---

    private fun startSpeedMonitoring() {
        if (speedConsumerId != null) return
        speedConsumerId = karooSystem.addConsumer(
            OnStreamState.StartStreaming(DataType.Type.SPEED)
        ) { event: OnStreamState ->
            if (event.state is StreamState.Streaming) {
                val values = (event.state as StreamState.Streaming).dataPoint.values
                val speedMs = values[DataType.Field.SPEED]?.toFloat() ?: 0f
                currentSpeedKmh = speedMs * 3.6f
                checkSpeedAdaptiveLighting()
            }
        }
    }

    private fun checkSpeedAdaptiveLighting() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(PREF_SPEED_ADAPTIVE_ENABLED, false)) return
        if (isRadarThreatActive || isBrakingActive) return

        val thresholdKmh = prefs.getInt(PREF_SPEED_THRESHOLD, 28)

        if (currentSpeedKmh >= thresholdKmh && !isSpeedAdaptiveActive) {
            isSpeedAdaptiveActive = true
        } else if (currentSpeedKmh < (thresholdKmh - 3) && isSpeedAdaptiveActive) {
            isSpeedAdaptiveActive = false
        }
    }

    // --- RADAR THREAT DETECTION ---

    private fun startRadarMonitoring() {
        if (radarConsumerId != null) return
        radarConsumerId = karooSystem.addConsumer(
            OnStreamState.StartStreaming(DataType.Type.RADAR)
        ) { event: OnStreamState ->
            if (event.state is StreamState.Streaming) {
                val values = (event.state as StreamState.Streaming).dataPoint.values
                val threat = values[DataType.Field.RADAR_THREAT_LEVEL]?.toInt() ?: 0
                onRadarThreatLevel(threat)
            }
        }
    }

    fun onRadarThreatLevel(threatLevel: Int) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val threatReactionEnabled = prefs.getBoolean(PREF_RADAR_THREAT, true)
        currentThreatLevel = threatLevel

        if (threatLevel > 0) {
            threatHoldJob?.cancel()
            threatHoldJob = null

            if (!isRadarThreatActive) {
                isRadarThreatActive = true
                if (threatReactionEnabled && bleManager.isConnected) {
                    val fallbackProfile = prefs.getInt(PREF_DEFAULT_PROFILE, 0)
                    val currentProfile = if (bleManager.currentMode in 0..3) bleManager.currentMode else fallbackProfile
                    preThreatRayoProfile = currentProfile
                    val targetProfile = prefs.getInt(PREF_RADAR_RAYO_PROFILE, 1).coerceIn(0, 3)
                    if (bleManager.currentMode != targetProfile) {
                        bleManager.selectProfile(targetProfile)
                        updateAllRayoDataFields()
                    }
                }
            }
        } else {
            if (!isRadarThreatActive && threatHoldJob == null) return
            if (!isRadarThreatActive) return

            threatHoldJob?.cancel()
            val holdSec = prefs.getInt(PREF_RADAR_HOLD_SEC, 3)
            threatHoldJob = scope.launch {
                delay(holdSec * 1000L)
                if (!isRadarThreatActive) return@launch

                isRadarThreatActive = false
                currentThreatLevel = 0
                if (threatReactionEnabled && bleManager.isConnected) {
                    val restoreProfile = preThreatRayoProfile
                    preThreatRayoProfile = null
                    if (restoreProfile != null && bleManager.currentMode != restoreProfile) {
                        bleManager.selectProfile(restoreProfile)
                        updateAllRayoDataFields()
                    }
                } else {
                    preThreatRayoProfile = null
                }
                threatHoldJob = null
            }
        }
    }

    // --- RADAR SIMULATOR (INDOOR TESTING) ---

    fun startRadarSimulation() {
        if (radarSimulatorJob != null) return
        val isGerman = Locale.getDefault().language.startsWith("de")
        showInRideAlert("🧪 Radar-Simulator", if (isGerman) "Testlauf gestartet" else "Test simulation started")

        radarSimulatorJob = scope.launch {
            while (isActive) {
                onSimulationStep?.invoke(0, if (isGerman) "✅ Straße frei (Normal)" else "✅ Road Clear")
                onRadarThreatLevel(0)
                delay(6000L)
                if (!isActive) break

                onSimulationStep?.invoke(1, if (isGerman) "🚗 Annäherung: Warnblinken (Gelb)" else "🚗 Vehicle Warning (Yellow)")
                onRadarThreatLevel(1)
                delay(7000L)
                if (!isActive) break

                onSimulationStep?.invoke(2, if (isGerman) "🚨 Schnelles Fahrzeug: Schneller Puls (Rot)" else "🚨 Fast Vehicle Alert (Red)")
                onRadarThreatLevel(2)
                delay(6000L)
                if (!isActive) break

                onSimulationStep?.invoke(0, if (isGerman) "✅ Fahrzeug überholt: Normalbetrieb" else "✅ Vehicle Passed")
                onRadarThreatLevel(0)
                delay(5000L)
                if (!isActive) break

                onSimulationStep?.invoke(2, if (isGerman) "🚨 Schnelles Fahrzeug (Rot)" else "🚨 Fast Vehicle (Red)")
                onRadarThreatLevel(2)
                delay(7000L)
                if (!isActive) break

                onSimulationStep?.invoke(0, if (isGerman) "✅ Straße frei" else "✅ Road Clear")
                onRadarThreatLevel(0)
                delay(8000L)
            }
        }
    }

    fun stopRadarSimulation() {
        radarSimulatorJob?.cancel()
        radarSimulatorJob = null
        onRadarThreatLevel(0)
        val isGerman = Locale.getDefault().language.startsWith("de")
        onSimulationStep?.invoke(0, if (isGerman) "Simulator beendet." else "Simulator stopped.")
        showInRideAlert("🧪 Radar-Simulator", if (isGerman) "Testlauf beendet" else "Test simulation stopped")
    }

    // --- IN-RIDE ALERTS ---

    fun showInRideAlert(title: String, detail: String) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(PREF_IN_RIDE_ALERTS, true)) return

        try {
            karooSystem.dispatch(
                InRideAlert(
                    id = "rayo-alert",
                    icon = R.drawable.ic_launcher,
                    title = title,
                    detail = detail,
                    autoDismissMs = 3000,
                    backgroundColor = android.R.color.black,
                    textColor = android.R.color.white
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error dispatching InRideAlert", e)
        }
    }

    override fun onBonusAction(actionId: String) {
        when (actionId) {
            "toggle-rayo" -> {
                toggleLight()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedMac = prefs.getString(PREF_MAC, null)
        if (!savedMac.isNullOrEmpty() && !bleManager.isConnected) {
            try {
                karooSystem.dispatch(RequestBluetooth(extension))
            } catch (ignored: Exception) {}
            bleManager.connect(savedMac)
        }

        intent?.action?.let { action ->
            when (action) {
                ACTION_TOGGLE_LIGHT -> toggleLight()
                ACTION_CYCLE_PROFILE -> cycleProfile()
            }
        }

        return START_STICKY
    }

    // --- RAYO BLE IN-RIDE ACTIONS ---

    fun connectBle(mac: String? = null, autoTurnOn: Boolean = false) {
        val targetMac = mac ?: getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(PREF_MAC, null)
        if (!targetMac.isNullOrEmpty()) {
            try {
                karooSystem.dispatch(RequestBluetooth(extension))
            } catch (ignored: Exception) {}
            bleManager.connect(targetMac, autoTurnOnAfterConnect = autoTurnOn)
        }
    }

    fun toggleLight() {
        if (!bleManager.isConnected) {
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val savedMac = prefs.getString(PREF_MAC, null)
            try {
                karooSystem.dispatch(RequestBluetooth(extension))
            } catch (ignored: Exception) {}
            bleManager.connect(savedMac, autoTurnOnAfterConnect = true)
            updateAllRayoDataFields()
            return
        }
        val newState = !bleManager.isLightOn
        bleManager.setLight(newState)
        updateAllRayoDataFields()
    }

    fun cycleProfile() {
        if (!bleManager.isConnected) {
            connectBle()
            return
        }
        val next = (bleManager.currentMode + 1) % 4
        bleManager.selectProfile(next)
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt(PREF_DEFAULT_PROFILE, next).apply()
        updateAllRayoDataFields()
    }

    // --- EMITTER REGISTRATION & UPDATES ---

    fun registerRayoViewEmitter(emitter: ViewEmitter) {
        synchronized(activeRayoViewEmitters) { activeRayoViewEmitters.add(emitter) }
        emitRayoDataField(emitter)
    }

    fun unregisterRayoViewEmitter(emitter: ViewEmitter) {
        synchronized(activeRayoViewEmitters) { activeRayoViewEmitters.remove(emitter) }
    }

    fun registerRayoBatteryEmitter(emitter: Emitter<StreamState>) {
        synchronized(activeRayoBatteryEmitters) { activeRayoBatteryEmitters.add(emitter) }
        emitRayoBatteryStream(emitter)
    }

    fun unregisterRayoBatteryEmitter(emitter: Emitter<StreamState>) {
        synchronized(activeRayoBatteryEmitters) { activeRayoBatteryEmitters.remove(emitter) }
    }

    fun updateAllRayoDataFields() {
        synchronized(activeRayoViewEmitters) {
            for (emitter in activeRayoViewEmitters) {
                emitRayoDataField(emitter)
            }
        }
    }

    private fun updateRayoBatteryStreams() {
        synchronized(activeRayoBatteryEmitters) {
            for (emitter in activeRayoBatteryEmitters) {
                emitRayoBatteryStream(emitter)
            }
        }
    }

    private fun emitRayoBatteryStream(emitter: Emitter<StreamState>) {
        if (!bleManager.isConnected) {
            emitter.onNext(StreamState.Searching)
        } else if (bleManager.batteryPercent >= 0) {
            emitter.onNext(
                StreamState.Streaming(
                    DataPoint(
                        dataTypeId = DataType.dataTypeId(extension, TYPE_RAYO_BATTERY),
                        values = mapOf(DataType.Field.SINGLE to bleManager.batteryPercent.toDouble())
                    )
                )
            )
        } else {
            emitter.onNext(StreamState.NotAvailable)
        }
    }

    // --- RAYO IN-RIDE VIEW EMISSION ---

    private fun emitRayoDataField(emitter: ViewEmitter) {
        val isGerman = Locale.getDefault().language.startsWith("de")
        val rv = RemoteViews(packageName, R.layout.view_rayo_datafield)

        val toggleIntent = Intent(this, RayoActionReceiver::class.java).apply {
            action = ACTION_TOGGLE_LIGHT
        }
        val pToggle = PendingIntent.getBroadcast(
            this,
            101,
            toggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        rv.setOnClickPendingIntent(R.id.btnToggleLight, pToggle)

        if (bleManager.isConnecting) {
            rv.setTextViewText(R.id.btnToggleLight, if (isGerman) "VERBINDE..." else "CONNECTING...")
            rv.setTextColor(R.id.btnToggleLight, Color.parseColor("#FFAA00"))
        } else if (!bleManager.isConnected) {
            rv.setTextViewText(R.id.btnToggleLight, if (isGerman) "RAYO VERBINDEN" else "CONNECT RAYO")
            rv.setTextColor(R.id.btnToggleLight, Color.parseColor("#9E9EA7"))
        } else if (bleManager.isLightOn) {
            rv.setTextViewText(R.id.btnToggleLight, if (isGerman) "LICHT AN" else "LIGHT ON")
            rv.setTextColor(R.id.btnToggleLight, Color.parseColor("#00E676"))
        } else {
            rv.setTextViewText(R.id.btnToggleLight, if (isGerman) "LICHT AUS" else "LIGHT OFF")
            rv.setTextColor(R.id.btnToggleLight, Color.parseColor("#FF2A55"))
        }

        val cycleIntent = Intent(this, RayoActionReceiver::class.java).apply {
            action = ACTION_CYCLE_PROFILE
        }
        val pCycle = PendingIntent.getBroadcast(
            this,
            102,
            cycleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        rv.setOnClickPendingIntent(R.id.btnCycleProfile, pCycle)
        val profilePrefix = if (isGerman) "PROFIL" else "PROFILE"
        rv.setTextViewText(R.id.btnCycleProfile, "$profilePrefix ${bleManager.currentMode + 1}")

        val batText = if (bleManager.batteryPercent >= 0) "${bleManager.batteryPercent}%" else "--%"
        rv.setTextViewText(R.id.tvBatteryField, batText)

        emitter.updateView(rv)
    }

    // --- SCANNER FOR NATIVE KAROO SENSORS MENU ---

    override fun startScan(emitter: Emitter<Device>) {
        val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter() ?: return
        val scanner = bluetoothAdapter.bluetoothLeScanner ?: return

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val foundMacs = mutableSetOf<String>()
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedMac = prefs.getString(PREF_MAC, null)

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                val dev = result?.device ?: return
                val address = dev.address ?: return
                val scanRecord = result.scanRecord
                val devName = try { dev.name ?: "" } catch (e: SecurityException) { "" }
                val recordName = scanRecord?.deviceName ?: ""
                val effectiveName = if (devName.isNotBlank()) devName else recordName

                val hasNusUuid = scanRecord?.serviceUuids?.any {
                    it.uuid == BleManager.NUS_SERVICE_UUID
                } == true

                val matchesName = effectiveName.contains("RAYO", ignoreCase = true) || effectiveName.contains("CLIQ", ignoreCase = true)
                val matchesMac = !savedMac.isNullOrEmpty() && address.equals(savedMac, ignoreCase = true)

                if ((matchesName || hasNusUuid || matchesMac) && foundMacs.add(address)) {
                    val displayName = if (effectiveName.isNotBlank()) effectiveName else "Rayo Light"
                    emitter.onNext(
                        Device(
                            extension = extension,
                            uid = address,
                            dataTypes = listOf(
                                DataType.dataTypeId(extension, TYPE_RAYO_CONTROL),
                                DataType.dataTypeId(extension, TYPE_RAYO_BATTERY)
                            ),
                            displayName = displayName
                        )
                    )
                }
            }

            override fun onScanFailed(errorCode: Int) {}
        }

        try {
            scanner.startScan(null, settings, callback)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting scan in Karoo sensor menu", e)
        }

        emitter.setCancellable {
            try {
                scanner.stopScan(callback)
            } catch (ignored: Exception) {}
        }
    }

    private fun batteryStatusFromPercentage(percent: Int): BatteryStatus {
        return when {
            percent > 80 -> BatteryStatus.GOOD
            percent > 30 -> BatteryStatus.OK
            percent > 15 -> BatteryStatus.LOW
            percent > 0 -> BatteryStatus.CRITICAL
            else -> BatteryStatus.INVALID
        }
    }

    override fun connectDevice(uid: String, emitter: Emitter<DeviceEvent>) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(PREF_MAC, uid).apply()

        val deviceListener = object : BleManager.Listener {
            override fun onConnectionStateChange(connected: Boolean, deviceName: String?, mac: String?) {
                if (connected) {
                    emitter.onNext(OnConnectionStatus(ConnectionStatus.CONNECTED))
                    if (bleManager.batteryPercent >= 0) {
                        emitter.onNext(OnBatteryStatus(batteryStatusFromPercentage(bleManager.batteryPercent)))
                    }
                } else {
                    emitter.onNext(OnConnectionStatus(ConnectionStatus.SEARCHING))
                }
            }

            override fun onBatteryUpdate(percent: Int) {
                emitter.onNext(OnBatteryStatus(batteryStatusFromPercentage(percent)))
                emitter.onNext(
                    OnDataPoint(
                        DataPoint(
                            dataTypeId = DataType.dataTypeId(extension, TYPE_RAYO_BATTERY),
                            values = mapOf(DataType.Field.SINGLE to percent.toDouble()),
                            sourceId = uid
                        )
                    )
                )
            }

            override fun onLightStateUpdate(isOn: Boolean) {}

            override fun onActiveModeUpdate(mode: Int) {}
        }

        bleManager.addListener(deviceListener)
        connectBle(uid)

        if (bleManager.isConnected) {
            emitter.onNext(OnConnectionStatus(ConnectionStatus.CONNECTED))
            if (bleManager.batteryPercent >= 0) {
                emitter.onNext(OnBatteryStatus(batteryStatusFromPercentage(bleManager.batteryPercent)))
            }
        } else {
            emitter.onNext(OnConnectionStatus(ConnectionStatus.SEARCHING))
        }

        emitter.setCancellable {
            bleManager.removeListener(deviceListener)
        }
    }

    override fun onDestroy() {
        stopRadarSimulation()
        accelListener?.let { sensorManager?.unregisterListener(it) }
        radarConsumerId?.let { karooSystem.removeConsumer(it) }
        speedConsumerId?.let { karooSystem.removeConsumer(it) }
        scope.cancel()
        bleManager.disconnect()
        karooSystem.disconnect()
        instance = null
        super.onDestroy()
    }
}

// --- DATA TYPE IMPLEMENTATIONS ---

class RayoControlDataType(
    private val extensionContext: RayoExtension,
    extension: String,
) : DataTypeImpl(extension, RayoExtension.TYPE_RAYO_CONTROL) {
    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        emitter.onNext(UpdateGraphicConfig(showHeader = false))
        extensionContext.registerRayoViewEmitter(emitter)

        if (!extensionContext.bleManager.isConnected) {
            extensionContext.connectBle()
        }

        emitter.setCancellable {
            extensionContext.unregisterRayoViewEmitter(emitter)
        }
    }
}

class RayoBatteryDataType(
    private val extensionContext: RayoExtension,
    extension: String,
) : DataTypeImpl(extension, RayoExtension.TYPE_RAYO_BATTERY) {
    override fun startStream(emitter: Emitter<StreamState>) {
        extensionContext.registerRayoBatteryEmitter(emitter)
        emitter.setCancellable {
            extensionContext.unregisterRayoBatteryEmitter(emitter)
        }
    }
}
