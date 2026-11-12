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
import com.rayo.karoo.ant.KarooLightControl
import com.rayo.karoo.ant.LightMode
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
import io.hammerhead.karooext.models.SavedDevices
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
        const val ACTION_TOGGLE_ANT_LIGHT = "com.rayo.karoo.ACTION_TOGGLE_ANT_LIGHT"
        const val ACTION_CYCLE_ANT_MODE = "com.rayo.karoo.ACTION_CYCLE_ANT_MODE"

        // SharedPreferences Keys
        const val PREFS_NAME = "rayo_karoo_prefs"
        const val PREF_MAC = "paired_mac"
        const val PREF_AUTO_START = "auto_start_enabled"
        const val PREF_DEFAULT_PROFILE = "default_profile"

        // ANT+ / Lezyne settings
        const val PREF_ANT_DEFAULT_MODE = "ant_default_mode"
        const val PREF_STVO_MODE = "stvo_mode_enabled"
        const val PREF_STVO_WARN_FLASH = "stvo_warn_flash_enabled"
        const val PREF_RADAR_THREAT = "radar_threat_enabled"
        const val PREF_RADAR_THREAT_YELLOW = "radar_threat_yellow"
        const val PREF_RADAR_THREAT_RED = "radar_threat_red"
        const val PREF_RADAR_HOLD_SEC = "radar_hold_sec"
        const val PREF_SIMULATE_RADAR = "simulate_radar"

        // G-Brake settings
        const val PREF_BRAKE_ENABLED = "brake_enabled"
        const val PREF_BRAKE_THRESHOLD = "brake_threshold"
        const val PREF_BRAKE_MODE = "brake_mode"
        const val PREF_BRAKE_HOLD_SEC = "brake_hold_sec"

        // Speed Adaptive settings
        const val PREF_SPEED_ADAPTIVE_ENABLED = "speed_adaptive_enabled"
        const val PREF_SPEED_THRESHOLD = "speed_threshold"
        const val PREF_SPEED_MODE = "speed_mode"

        // Pause & Alert settings
        const val PREF_PAUSE_BEHAVIOR = "pause_behavior"
        const val PREF_IN_RIDE_ALERTS = "in_ride_alerts"

        // Extension Data Types
        const val TYPE_RAYO_CONTROL = "rayo_control"
        const val TYPE_RAYO_BATTERY = "rayo_battery"
        const val TYPE_ANT_CONTROL = "ant_light_control"
        const val TYPE_ANT_BATTERY = "ant_light_battery"

        private const val DEVICE_TYPE_BIKE_LIGHT = 35
        private const val BIKE_LIGHT_DATA_TYPE = "TYPE_BIKE_LIGHT_ID"
    }

    lateinit var bleManager: BleManager
        private set

    lateinit var karooLightControl: KarooLightControl
        private set

    lateinit var karooSystem: KarooSystemService
        private set

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Emitters for Rayo Data Fields
    private val activeRayoViewEmitters = mutableListOf<ViewEmitter>()
    private val activeRayoBatteryEmitters = mutableListOf<Emitter<StreamState>>()

    // Emitters for ANT+ / Lezyne Data Fields
    private val activeAntViewEmitters = mutableListOf<ViewEmitter>()
    private val activeAntBatteryEmitters = mutableListOf<Emitter<StreamState>>()

    // ANT+ Device Cache
    data class AntDeviceInfo(
        val id: String,
        val name: String,
        val manufacturer: String? = null,
        val batteryPercent: Int? = null,
        val fromRadar: Boolean = false
    )
    val discoveredAntLights = mutableListOf<AntDeviceInfo>()

    val primaryAntLight: AntDeviceInfo?
        get() = synchronized(discoveredAntLights) { discoveredAntLights.firstOrNull() }

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

    private var preThreatMode: LightMode? = null
    private var prePauseMode: LightMode? = null
    private var threatHoldJob: Job? = null
    private var brakeHoldJob: Job? = null
    private var radarSimulatorJob: Job? = null
    private var stvoFlashJob: Job? = null

    var onSimulationStep: ((threatLevel: Int, label: String) -> Unit)? = null

    fun isStvoModeActive(): Boolean {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.contains(PREF_STVO_MODE)) {
            return prefs.getBoolean(PREF_STVO_MODE, true)
        }
        val light = primaryAntLight ?: return true
        val supported = karooLightControl.supportedModes.value[light.id]
        if (!supported.isNullOrEmpty()) {
            val hasFlash = supported.any { it.contains("FLASH", ignoreCase = true) }
            return !hasFlash
        }
        return true
    }

    private var radarConsumerId: String? = null
    private var speedConsumerId: String? = null

    // G-Sensor Accelerometer
    private var sensorManager: SensorManager? = null
    private var accelListener: SensorEventListener? = null

    override val types by lazy {
        listOf(
            RayoControlDataType(this, extension),
            RayoBatteryDataType(this, extension),
            AntLightControlDataType(this, extension),
            AntLightBatteryDataType(this, extension)
        )
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        karooSystem = KarooSystemService(this)
        karooLightControl = KarooLightControl(applicationContext)

        // Listen to state changes from ANT+ lights
        karooLightControl.onStateChanged = {
            scope.launch {
                updateAllAntDataFields()
                updateAntBatteryStreams()
            }
        }

        // Connect to Karoo SensorService for ANT+ lights
        karooLightControl.bind()

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

                        // Restore or start Lezyne / ANT+ lights
                        karooLightControl.bind()
                        val restoreModeName = prePauseMode?.karooName
                            ?: prefs.getString(PREF_ANT_DEFAULT_MODE, LightMode.DAY_FLASH.karooName)
                            ?: LightMode.DAY_FLASH.karooName

                        prePauseMode = null
                        setAntLightsMode(restoreModeName)
                        showInRideAlert("Lichter: An", "Fahrt aktiv")
                    }
                }
                is RideState.Paused -> {
                    // Ride paused
                    val pauseBehavior = prefs.getString(PREF_PAUSE_BEHAVIOR, "NONE") ?: "NONE"
                    val primary = primaryAntLight
                    if (primary != null) {
                        val current = karooLightControl.actualModes.value[primary.id]
                        prePauseMode = LightMode.fromKarooName(current)
                    }

                    when (pauseBehavior) {
                        "LOW" -> {
                            setAntLightsMode(LightMode.STEADY_LOW.karooName)
                            showInRideAlert("Lichter: Sparmodus", "Fahrt pausiert")
                        }
                        "OFF" -> {
                            setAntLightsMode(LightMode.OFF.karooName)
                            if (bleManager.isConnected) bleManager.setLight(false)
                            showInRideAlert("Lichter: Aus", "Fahrt pausiert")
                        }
                        else -> {}
                    }
                }
                is RideState.Idle -> {
                    // Ride ended! Turn off all lights
                    prePauseMode = null
                    if (bleManager.isConnected) {
                        bleManager.setLight(false)
                    }
                    setAntLightsMode(LightMode.OFF.karooName)
                    showInRideAlert("Lichter: Aus", "Fahrt beendet")
                    try {
                        karooSystem.dispatch(ReleaseBluetooth(extension))
                    } catch (ignored: Exception) {}
                }
                else -> {}
            }
        }

        // 2. SavedDevices Consumer (Auto-discover paired Lezyne / ANT+ bike lights)
        karooSystem.addConsumer<SavedDevices> { savedDevices ->
            fun translateBattery(name: String?): Int? = when (name) {
                "GOOD", "NEW" -> 80
                "OK" -> 50
                "LOW" -> 20
                "CRITICAL" -> 5
                else -> if (name != null) 100 else null
            }

            val otherBatteriesBySuffix = savedDevices.devices.filter { device ->
                val parts = device.id.split("-")
                device.enabled && (parts.size < 2 || parts[1].toIntOrNull() != DEVICE_TYPE_BIKE_LIGHT)
            }.mapNotNull { device ->
                val batteryPercent = translateBattery(device.details?.lastBattery?.name)
                if (batteryPercent != null) device.id.split("-").last() to batteryPercent else null
            }.toMap()

            val lights = savedDevices.devices.filter { device ->
                val parts = device.id.split("-")
                val isLightType = parts.size >= 2 && parts[1].toIntOrNull() == DEVICE_TYPE_BIKE_LIGHT
                val hasLightDataType = device.supportedDataTypes.any { it.contains("Light", ignoreCase = true) }
                device.enabled && (isLightType || hasLightDataType)
            }

            synchronized(discoveredAntLights) {
                discoveredAntLights.clear()
                for (device in lights) {
                    val directBattery = translateBattery(device.details?.lastBattery?.name)
                    var batteryPercent = directBattery
                    var fromRadar = false

                    if (batteryPercent == null) {
                        val suffix = device.id.split("-").last()
                        val radarBattery = otherBatteriesBySuffix[suffix]
                        if (radarBattery != null) {
                            batteryPercent = radarBattery
                            fromRadar = true
                        }
                    }

                    val info = AntDeviceInfo(
                        id = device.id,
                        name = device.name,
                        manufacturer = device.details?.manufacturer,
                        batteryPercent = batteryPercent,
                        fromRadar = fromRadar
                    )
                    discoveredAntLights.add(info)
                    karooLightControl.registerConnectionState(device.id)
                    karooLightControl.registerForLightParameters(device.id)
                }
            }
            updateAllAntDataFields()
            updateAntBatteryStreams()
        }

        // 3. Radar Threat Stream
        startRadarMonitoring()

        // 4. GPS Speed Stream for Speed-Adaptive Lighting
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
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val brakeMode = prefs.getString(PREF_BRAKE_MODE, LightMode.STEADY_HIGH.karooName)
            ?: LightMode.STEADY_HIGH.karooName

        setAntLightsMode(brakeMode)
        updateAllAntDataFields()
    }

    private fun clearBrakeLight() {
        if (isRadarThreatActive) return
        restoreNormalAntMode()
        updateAllAntDataFields()
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
        val speedMode = prefs.getString(PREF_SPEED_MODE, LightMode.STEADY_HIGH.karooName)
            ?: LightMode.STEADY_HIGH.karooName

        if (currentSpeedKmh >= thresholdKmh && !isSpeedAdaptiveActive) {
            isSpeedAdaptiveActive = true
            setAntLightsMode(speedMode)
            updateAllAntDataFields()
        } else if (currentSpeedKmh < (thresholdKmh - 3) && isSpeedAdaptiveActive) {
            isSpeedAdaptiveActive = false
            restoreNormalAntMode()
            updateAllAntDataFields()
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
        val stvoWarnFlashEnabled = prefs.getBoolean(PREF_STVO_WARN_FLASH, true)
        val oldThreat = currentThreatLevel
        currentThreatLevel = threatLevel
        val isGerman = Locale.getDefault().language.startsWith("de")

        if (threatLevel > 0) {
            threatHoldJob?.cancel()
            threatHoldJob = null

            if (!isRadarThreatActive) {
                isRadarThreatActive = true

                val primary = primaryAntLight
                if (primary != null) {
                    val currentKarooMode = karooLightControl.actualModes.value[primary.id]
                    preThreatMode = LightMode.fromKarooName(currentKarooMode)
                }
            }

            // In-Ride Notification Popup on screen
            if (oldThreat != threatLevel) {
                if (threatLevel >= 2) {
                    showInRideAlert(
                        if (isGerman) "🚨 Schnelles Fahrzeug!" else "🚨 Fast Vehicle!",
                        if (isGerman) "Radar-Alarm (Rot)" else "Radar Alert (Red)"
                    )
                } else {
                    showInRideAlert(
                        if (isGerman) "🚗 Fahrzeug nähert sich!" else "🚗 Vehicle Approaching!",
                        if (isGerman) "Radar-Warnung (Gelb)" else "Radar Warning (Yellow)"
                    )
                }
            }

            if (threatReactionEnabled) {
                if (isStvoModeActive() || stvoWarnFlashEnabled) {
                    triggerStvoWarningFlash(threatLevel)
                } else {
                    val threatModeName = if (threatLevel >= 2) {
                        prefs.getString(PREF_RADAR_THREAT_RED, LightMode.FAST_FLASH.karooName)
                            ?: LightMode.FAST_FLASH.karooName
                    } else {
                        prefs.getString(PREF_RADAR_THREAT_YELLOW, LightMode.DAY_FLASH.karooName)
                            ?: LightMode.DAY_FLASH.karooName
                    }
                    setAntLightsMode(threatModeName)
                }
            }
            updateAllAntDataFields()
        } else {
            stvoFlashJob?.cancel()
            stvoFlashJob = null

            if (!isRadarThreatActive && threatHoldJob == null) return
            if (!isRadarThreatActive) return

            threatHoldJob?.cancel()
            val holdSec = prefs.getInt(PREF_RADAR_HOLD_SEC, 3)
            threatHoldJob = scope.launch {
                delay(holdSec * 1000L)
                if (!isRadarThreatActive) return@launch

                isRadarThreatActive = false
                currentThreatLevel = 0
                if (threatReactionEnabled) {
                    restoreNormalAntMode()
                }
                threatHoldJob = null
                updateAllAntDataFields()
            }
        }
    }

    private fun triggerStvoWarningFlash(threatLevel: Int) {
        val light = primaryAntLight ?: return
        val highModeName = karooLightControl.getEffectiveHighMode(light.id)
        val lowModeName = "OFF" // Pulsing High <-> OFF produces an unmistakable warning flash!

        stvoFlashJob?.cancel()
        stvoFlashJob = scope.launch {
            val pulses = if (threatLevel >= 2) 6 else 4
            val delayMs = if (threatLevel >= 2) 280L else 350L

            for (i in 1..pulses) {
                if (!isActive || !isRadarThreatActive) break
                karooLightControl.setMode(light.id, highModeName)
                delay(delayMs)
                if (!isActive || !isRadarThreatActive) break
                karooLightControl.setMode(light.id, lowModeName)
                delay(delayMs)
            }

            // Once warning pulse finishes, keep light ON High if car is still present
            if (isRadarThreatActive) {
                karooLightControl.setMode(light.id, highModeName)
            } else {
                restoreNormalAntMode()
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

    // --- HELPER: SET & RESTORE MODES ---

    private fun setAntLightsMode(modeName: String) {
        synchronized(discoveredAntLights) {
            for (light in discoveredAntLights) {
                karooLightControl.setMode(light.id, modeName)
            }
        }
    }

    private fun restoreNormalAntMode() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val primary = primaryAntLight
        val targetMode = if (isSpeedAdaptiveActive) {
            prefs.getString(PREF_SPEED_MODE, LightMode.STEADY_HIGH.karooName)
                ?: LightMode.STEADY_HIGH.karooName
        } else if (isStvoModeActive() && primary != null) {
            preThreatMode?.karooName ?: karooLightControl.getEffectiveHighMode(primary.id)
        } else {
            preThreatMode?.karooName
                ?: prefs.getString(PREF_ANT_DEFAULT_MODE, LightMode.DAY_FLASH.karooName)
                ?: LightMode.DAY_FLASH.karooName
        }
        setAntLightsMode(targetMode)
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
            "toggle-lights", "toggle-ant-light" -> {
                toggleAntLight()
            }
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
        karooLightControl.bind()

        intent?.action?.let { action ->
            when (action) {
                ACTION_TOGGLE_LIGHT -> toggleLight()
                ACTION_CYCLE_PROFILE -> cycleProfile()
                ACTION_TOGGLE_ANT_LIGHT -> toggleAntLight()
                ACTION_CYCLE_ANT_MODE -> cycleAntMode()
            }
        }

        return START_STICKY
    }

    // --- ANT+ LIGHT IN-RIDE ACTIONS ---

    fun cycleAntMode() {
        val light = primaryAntLight ?: return
        val currentKarooMode = karooLightControl.actualModes.value[light.id]
        val currentMode = LightMode.fromKarooName(currentKarooMode)
        val isGerman = Locale.getDefault().language.startsWith("de")

        val targetMode: LightMode
        val targetModeName: String

        if (isStvoModeActive()) {
            val highModeName = karooLightControl.getEffectiveHighMode(light.id)
            val lowModeName = karooLightControl.getEffectiveLowMode(light.id)

            if (currentKarooMode == highModeName || currentMode == LightMode.STEADY_HIGH) {
                targetModeName = lowModeName
                targetMode = LightMode.STEADY_LOW
            } else {
                targetModeName = highModeName
                targetMode = LightMode.STEADY_HIGH
            }
        } else {
            val supportedSet = karooLightControl.supportedModes.value[light.id]
            val cycleList: List<LightMode> = if (!supportedSet.isNullOrEmpty()) {
                val list = supportedSet.map { LightMode.fromKarooName(it) }.filter { it != LightMode.OFF }.distinct()
                if (list.size > 1) list else LightMode.CYCLE_MODES
            } else {
                LightMode.CYCLE_MODES
            }
            val currentIndex = cycleList.indexOf(currentMode)
            targetMode = if (currentIndex == -1 || currentIndex >= cycleList.size - 1) {
                cycleList.first()
            } else {
                cycleList[currentIndex + 1]
            }
            targetModeName = targetMode.karooName
        }

        preThreatMode = targetMode
        karooLightControl.setMode(light.id, targetModeName)
        updateAllAntDataFields()

        val lightDisplayName = if (isStvoModeActive()) {
            if (targetMode == LightMode.STEADY_HIGH) (if (isGerman) "Dauerlicht Hell" else "Steady High")
            else (if (isGerman) "Dauerlicht Spar" else "Steady Low")
        } else {
            targetMode.getLocalizedName(isGerman)
        }
        showInRideAlert("Radar: $lightDisplayName", if (isGerman) "Modus gewechselt" else "Mode switched")
    }

    fun toggleAntLight() {
        val light = primaryAntLight ?: return
        val currentKarooMode = karooLightControl.actualModes.value[light.id]
        val currentMode = LightMode.fromKarooName(currentKarooMode)
        val isGerman = Locale.getDefault().language.startsWith("de")

        val targetMode: LightMode
        val targetModeName: String

        if (currentMode == LightMode.OFF || currentKarooMode == "OFF") {
            if (isStvoModeActive()) {
                targetModeName = karooLightControl.getEffectiveHighMode(light.id)
                targetMode = LightMode.STEADY_HIGH
            } else {
                val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val defName = prefs.getString(PREF_ANT_DEFAULT_MODE, LightMode.DAY_FLASH.karooName)
                targetMode = LightMode.fromKarooName(defName)
                targetModeName = targetMode.karooName
            }
        } else {
            preThreatMode = currentMode
            targetMode = LightMode.OFF
            targetModeName = "OFF"
        }

        karooLightControl.setMode(light.id, targetModeName)
        updateAllAntDataFields()
        val lightDisplayName = if (isStvoModeActive() && targetMode != LightMode.OFF) {
            if (targetMode == LightMode.STEADY_HIGH) (if (isGerman) "Dauerlicht Hell" else "Steady High")
            else (if (isGerman) "Dauerlicht Spar" else "Steady Low")
        } else {
            targetMode.getLocalizedName(isGerman)
        }
        showInRideAlert("Radar: $lightDisplayName", if (targetMode == LightMode.OFF) (if (isGerman) "Ausgeschaltet" else "Turned Off") else (if (isGerman) "Eingeschaltet" else "Turned On"))
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
        val isGerman = Locale.getDefault().language.startsWith("de")
        if (!bleManager.isConnected) {
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val savedMac = prefs.getString(PREF_MAC, null)
            try {
                karooSystem.dispatch(RequestBluetooth(extension))
            } catch (ignored: Exception) {}
            bleManager.connect(savedMac, autoTurnOnAfterConnect = true)
            updateAllRayoDataFields()
            showInRideAlert("Rayo: " + (if (isGerman) "Verbinde..." else "Connecting..."), if (isGerman) "Suche Rücklicht" else "Searching light")
            return
        }
        val newState = !bleManager.isLightOn
        bleManager.setLight(newState)
        updateAllRayoDataFields()
        showInRideAlert("Rayo: " + (if (newState) "Licht AN" else "Licht AUS"), "Bluetooth")
    }

    fun cycleProfile() {
        val isGerman = Locale.getDefault().language.startsWith("de")
        if (!bleManager.isConnected) {
            connectBle()
            showInRideAlert("Rayo: " + (if (isGerman) "Nicht verbunden" else "Not connected"), if (isGerman) "Verbinde..." else "Connecting...")
            return
        }
        val next = (bleManager.currentMode + 1) % 4
        bleManager.selectProfile(next)
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt(PREF_DEFAULT_PROFILE, next).apply()
        updateAllRayoDataFields()
        val profilePrefix = if (isGerman) "Profil" else "Profile"
        showInRideAlert("Rayo: $profilePrefix ${next + 1}", "Bluetooth")
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

    fun registerAntViewEmitter(emitter: ViewEmitter) {
        synchronized(activeAntViewEmitters) { activeAntViewEmitters.add(emitter) }
        emitAntDataField(emitter)
    }

    fun unregisterAntViewEmitter(emitter: ViewEmitter) {
        synchronized(activeAntViewEmitters) { activeAntViewEmitters.remove(emitter) }
    }

    fun registerAntBatteryEmitter(emitter: Emitter<StreamState>) {
        synchronized(activeAntBatteryEmitters) { activeAntBatteryEmitters.add(emitter) }
        emitAntBatteryStream(emitter)
    }

    fun unregisterAntBatteryEmitter(emitter: Emitter<StreamState>) {
        synchronized(activeAntBatteryEmitters) { activeAntBatteryEmitters.remove(emitter) }
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

    fun updateAllAntDataFields() {
        synchronized(activeAntViewEmitters) {
            for (emitter in activeAntViewEmitters) {
                emitAntDataField(emitter)
            }
        }
    }

    fun updateAntBatteryStreams() {
        synchronized(activeAntBatteryEmitters) {
            for (emitter in activeAntBatteryEmitters) {
                emitAntBatteryStream(emitter)
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

    private fun emitAntBatteryStream(emitter: Emitter<StreamState>) {
        val light = primaryAntLight
        val connState = light?.let { karooLightControl.connectionStates.value[it.id] }
        val isConnected = connState == "CONNECTED" || light != null

        if (light == null || !isConnected) {
            emitter.onNext(StreamState.Searching)
        } else if (light.batteryPercent != null) {
            emitter.onNext(
                StreamState.Streaming(
                    DataPoint(
                        dataTypeId = DataType.dataTypeId(extension, TYPE_ANT_BATTERY),
                        values = mapOf(DataType.Field.SINGLE to light.batteryPercent.toDouble())
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

    // --- LEZYNE / ANT+ IN-RIDE VIEW EMISSION ---

    private fun emitAntDataField(emitter: ViewEmitter) {
        val isGerman = Locale.getDefault().language.startsWith("de")
        val rv = RemoteViews(packageName, R.layout.view_ant_light_datafield)

        val light = primaryAntLight
        val lightName = light?.name?.uppercase() ?: if (isGerman) "LEZYNE RADAR" else "LEZYNE RADAR"
        rv.setTextViewText(R.id.tvAntLightName, lightName)

        // Status Badge Logic (Priority: Braking > Radar Threat > Speed Adaptive > Radar Clear)
        if (isBrakingActive) {
            rv.setTextViewText(R.id.tvAntRadarStatus, if (isGerman) "BREMSE!" else "BRAKE!")
            rv.setTextColor(R.id.tvAntRadarStatus, Color.parseColor("#FF2A55"))
        } else if (isRadarThreatActive) {
            if (currentThreatLevel >= 2) {
                rv.setTextViewText(R.id.tvAntRadarStatus, if (isGerman) "FAHRZEUG (SCHNELL)!" else "FAST CAR!")
                rv.setTextColor(R.id.tvAntRadarStatus, Color.parseColor("#FF2A55"))
            } else {
                rv.setTextViewText(R.id.tvAntRadarStatus, if (isGerman) "FAHRZEUG!" else "VEHICLE!")
                rv.setTextColor(R.id.tvAntRadarStatus, Color.parseColor("#FFAA00"))
            }
        } else if (isSpeedAdaptiveActive) {
            rv.setTextViewText(R.id.tvAntRadarStatus, if (isGerman) "TEMPO-BOOST" else "SPEED BOOST")
            rv.setTextColor(R.id.tvAntRadarStatus, Color.parseColor("#3875F6"))
        } else {
            rv.setTextViewText(R.id.tvAntRadarStatus, if (isGerman) "RADAR FREI" else "RADAR CLEAR")
            rv.setTextColor(R.id.tvAntRadarStatus, Color.parseColor("#00E676"))
        }

        // Battery
        val batVal = light?.batteryPercent
        val batText = if (batVal != null) "$batVal%" else "--%"
        rv.setTextViewText(R.id.tvAntBatteryField, batText)

        // Mode cycle button
        val cycleIntent = Intent(this, RayoActionReceiver::class.java).apply {
            action = ACTION_CYCLE_ANT_MODE
        }
        val pCycle = PendingIntent.getBroadcast(
            this,
            201,
            cycleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        rv.setOnClickPendingIntent(R.id.btnAntCycleMode, pCycle)

        // Power toggle button
        val toggleIntent = Intent(this, RayoActionReceiver::class.java).apply {
            action = ACTION_TOGGLE_ANT_LIGHT
        }
        val pToggle = PendingIntent.getBroadcast(
            this,
            202,
            toggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        rv.setOnClickPendingIntent(R.id.btnAntToggleLight, pToggle)

        if (light == null) {
            rv.setTextViewText(R.id.btnAntCycleMode, if (isGerman) "KEIN LICHT GEFUNDEN" else "NO LIGHT FOUND")
            rv.setTextColor(R.id.btnAntCycleMode, Color.parseColor("#9E9EA7"))
            rv.setTextViewText(R.id.btnAntToggleLight, if (isGerman) "IN SENSOREN KOPPELN" else "PAIR IN SENSORS")
        } else {
            val currentKarooMode = karooLightControl.actualModes.value[light.id]
            val modeEnum = LightMode.fromKarooName(currentKarooMode)
            val modeLabel = if (isStvoModeActive()) {
                if (currentKarooMode == "OFF" || modeEnum == LightMode.OFF) {
                    if (isGerman) "AUS" else "OFF"
                } else if (currentKarooMode == karooLightControl.getEffectiveLowMode(light.id) || modeEnum == LightMode.STEADY_LOW) {
                    if (isGerman) "DAUERLICHT SPAR" else "STEADY LOW"
                } else {
                    if (isGerman) "DAUERLICHT HELL" else "STEADY HIGH"
                }
            } else {
                modeEnum.getLocalizedName(isGerman).uppercase()
            }

            rv.setTextViewText(R.id.btnAntCycleMode, modeLabel)

            if (modeEnum == LightMode.OFF) {
                rv.setTextColor(R.id.btnAntCycleMode, Color.parseColor("#FF2A55"))
                rv.setTextViewText(R.id.btnAntToggleLight, if (isGerman) "LICHT AN" else "LIGHT ON")
                rv.setTextColor(R.id.btnAntToggleLight, Color.parseColor("#00E676"))
            } else {
                rv.setTextColor(R.id.btnAntCycleMode, Color.parseColor("#00E676"))
                rv.setTextViewText(R.id.btnAntToggleLight, if (isGerman) "LICHT AUS" else "LIGHT OFF")
                rv.setTextColor(R.id.btnAntToggleLight, Color.parseColor("#FF2A55"))
            }
        }

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
        karooLightControl.unbind()
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

class AntLightControlDataType(
    private val extensionContext: RayoExtension,
    extension: String,
) : DataTypeImpl(extension, RayoExtension.TYPE_ANT_CONTROL) {
    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        emitter.onNext(UpdateGraphicConfig(showHeader = false))
        extensionContext.registerAntViewEmitter(emitter)
        emitter.setCancellable {
            extensionContext.unregisterAntViewEmitter(emitter)
        }
    }
}

class AntLightBatteryDataType(
    private val extensionContext: RayoExtension,
    extension: String,
) : DataTypeImpl(extension, RayoExtension.TYPE_ANT_BATTERY) {
    override fun startStream(emitter: Emitter<StreamState>) {
        extensionContext.registerAntBatteryEmitter(emitter)
        emitter.setCancellable {
            extensionContext.unregisterAntBatteryEmitter(emitter)
        }
    }
}
