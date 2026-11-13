/*
 * Based on Karoo SensorService IPC implementation from LightONKaroo
 * https://github.com/JaJaJim/LightONKaroo
 *
 * Copyright (c) 2026 Dennis Straßer (derstrassi)
 * Copyright (c) 2026 Peter Weber (JaJaJim)
 * Copyright (c) 2026 Tiago Fontes Dias (tiagodias00)
 *
 * Licensed under the MIT License.
 */

package com.rayo.karoo.ant

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.*
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class KarooLightControl(private val context: Context) {

    companion object {
        private const val TAG = "KarooLightControl"
        private const val SENSOR_DESCRIPTOR = "io.hammerhead.sensorservice.SensorServiceAIDL"
        private const val LIGHT_CMD_DESCRIPTOR = "io.hammerhead.sensorservice.LightCommandConnectionAIDL"
        private const val LISTENER_DESCRIPTOR = "io.hammerhead.aidlrx.IParcelableListener"
        private const val TX_REGISTER_LIGHT_PARAMS = 1
        private const val TX_SET_LIGHT_MODE = 3
    }

    private var sensorBinder: IBinder? = null
    private var lightCmdBinder: IBinder? = null
    private var lightModeParcelableCreator: ((String) -> Parcelable)? = { modeName ->
        io.hammerhead.datamodels.timeseriesData.models.LightMode.fromName(modeName)
    }
    private var deviceCreator: ((String) -> Parcelable)? = null
    private var isBound = false
    private var bindRequested = false

    private val _connectionStates = MutableStateFlow<Map<String, String>>(emptyMap())
    val connectionStates: StateFlow<Map<String, String>> = _connectionStates

    private val _supportedModes = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
    val supportedModes: StateFlow<Map<String, Set<String>>> = _supportedModes

    private val _actualModes = MutableStateFlow<Map<String, String>>(emptyMap())
    val actualModes: StateFlow<Map<String, String>> = _actualModes

    private val lastCommandedMode = ConcurrentHashMap<String, String>()
    private val registeredListeners = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val pendingRegistrations = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val pendingLightParamRegistrations = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    var onStateChanged: (() -> Unit)? = null

    // Sequential command queue to prevent IPC binder flood
    private data class QueuedCommand(val deviceId: String, val modeName: String)
    private val commandChannel = Channel<QueuedCommand>(Channel.UNLIMITED)
    private val queueScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val serviceExecutor = Executors.newSingleThreadExecutor()

    init {
        queueScope.launch {
            for (cmd in commandChannel) {
                try {
                    executeSetLightMode(cmd.deviceId, cmd.modeName)
                    delay(250L)
                } catch (e: Exception) {
                    Log.e(TAG, "Error executing queued light command", e)
                }
            }
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            sensorBinder = service
            isBound = true
            Log.d(TAG, "Connected to Karoo SensorService")
            if (service != null) {
                serviceExecutor.execute {
                    getLightCommandBinder(service)
                    loadLightModeClass()
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            sensorBinder = null
            lightCmdBinder = null
            isBound = false
            bindRequested = false
            registeredListeners.clear()
            Log.d(TAG, "Disconnected from SensorService")
        }
    }

    private fun getLightCommandBinder(service: IBinder) {
        val descriptor = service.interfaceDescriptor ?: SENSOR_DESCRIPTOR
        for (code in 1..25) {
            val d = Parcel.obtain()
            val r = Parcel.obtain()
            try {
                d.writeInterfaceToken(descriptor)
                val success = service.transact(code, d, r, 0)
                if (success) {
                    r.readException()
                    val b = r.readStrongBinder()
                    if (b != null) {
                        val desc = b.interfaceDescriptor
                        if (desc?.contains("Light", ignoreCase = true) == true) {
                            lightCmdBinder = b
                            Log.d(TAG, "Matched LightCommand binder at TX code $code: $desc")
                            break
                        }
                    }
                }
            } catch (_: Exception) {
            } finally {
                d.recycle()
                r.recycle()
            }
        }
    }

    private fun loadLightModeClass() {
        if (lightModeParcelableCreator == null) {
            lightModeParcelableCreator = { modeName ->
                io.hammerhead.datamodels.timeseriesData.models.LightMode.fromName(modeName)
            }
        }

        try {
            val sensorCtx = try {
                context.createPackageContext(
                    "io.hammerhead.sensorservice",
                    Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY
                )
            } catch (e: Exception) {
                null
            }

            if (sensorCtx != null) {
                val classLoader = sensorCtx.classLoader
                val cls = classLoader.loadClass(
                    "io.hammerhead.datamodels.timeseriesData.models.LightMode"
                )
                @Suppress("UNCHECKED_CAST")
                val enumClass = cls as Class<out Enum<*>>
                Log.d(TAG, "Loaded LightMode class from system: ${cls.name}")
                Log.d(TAG, "Available Karoo LightModes: " + cls.enumConstants?.joinToString())

                lightModeParcelableCreator = { modeName ->
                    java.lang.Enum.valueOf(enumClass, modeName) as Parcelable
                }

                val deviceClass = classLoader.loadClass(
                    "io.hammerhead.datamodels.timeseriesData.models.Device"
                )
                val defaultMarkerClass = classLoader.loadClass(
                    "kotlin.jvm.internal.DefaultConstructorMarker"
                )
                val deviceConstructor = deviceClass.constructors.find { c ->
                    (c.parameterTypes.lastOrNull() == defaultMarkerClass) &&
                            (c.parameterTypes[c.parameterTypes.size - 2] == Int::class.javaPrimitiveType)
                }
                if (deviceConstructor != null) {
                    val deviceInfoConstructor = classLoader.loadClass(
                        "io.hammerhead.datamodels.timeseriesData.models.DeviceInfo"
                    ).getDeclaredConstructor()

                    deviceCreator = { uid ->
                        val deviceInfo = deviceInfoConstructor.newInstance()
                        val params = arrayOfNulls<Any>(deviceConstructor.parameterTypes.size)
                        params[0] = uid
                        params[1] = deviceInfo
                        params[4] = false
                        params[5] = true
                        params[params.size - 2] = 0x1FFFC
                        params[params.size - 1] = null
                        deviceConstructor.newInstance(*params) as Parcelable
                    }
                    Log.d(TAG, "Successfully created Karoo Device reflection creator")
                }
            } else {
                Log.i(TAG, "Using built-in AIDL LightMode parcelable mirror (cross-process reflection restricted)")
            }

            pendingRegistrations.toList().forEach { registerConnectionState(it) }
            pendingRegistrations.clear()
            pendingLightParamRegistrations.toList().forEach { registerForLightParameters(it) }
            pendingLightParamRegistrations.clear()
            onStateChanged?.invoke()
        } catch (e: Exception) {
            Log.w(TAG, "Dynamic class loading notice: ${e.message}; using built-in LightMode mirror")
        }
    }

    fun bind() {
        if (bindRequested) return
        val intent = Intent().apply {
            component = ComponentName(
                "io.hammerhead.sensorservice",
                "io.hammerhead.sensorservice.service.SensorService"
            )
        }
        try {
            context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
            bindRequested = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind SensorService", e)
        }
    }

    fun unbind() {
        if (!bindRequested) return
        try { context.unbindService(serviceConnection) } catch (_: Exception) {}
        bindRequested = false
        isBound = false
        sensorBinder = null
        lightCmdBinder = null
        registeredListeners.clear()
        pendingRegistrations.clear()
        pendingLightParamRegistrations.clear()
    }

    fun getEffectiveHighMode(deviceId: String): String {
        val supported = _supportedModes.value[deviceId]
        if (!supported.isNullOrEmpty()) {
            if ("STEADY4" in supported) return "STEADY4"
            if ("STEADY_HIGH" in supported) return "STEADY_HIGH"
            if ("STEADY1" in supported) return "STEADY1"
            val nonOff = supported.filter { it != "OFF" }
            if (nonOff.isNotEmpty()) return nonOff.first()
        }
        return "STEADY4"
    }

    fun getEffectiveLowMode(deviceId: String): String {
        val supported = _supportedModes.value[deviceId]
        if (!supported.isNullOrEmpty()) {
            if ("STEADY5" in supported) return "STEADY5"
            if ("STEADY2" in supported) return "STEADY2"
            if ("STEADY3" in supported) return "STEADY3"
            if ("STEADY_LOW" in supported) return "STEADY_LOW"
            val nonOff = supported.filter { it != "OFF" }
            if (nonOff.size > 1) return nonOff.last()
        }
        return "STEADY5"
    }

    fun setMode(deviceId: String, modeName: String) {
        lastCommandedMode[deviceId] = modeName
        _actualModes.update { it + (deviceId to modeName) }
        commandChannel.trySend(QueuedCommand(deviceId, modeName))
        onStateChanged?.invoke()
    }

    private fun executeSetLightMode(deviceId: String, modeName: String): Boolean {
        val binder = lightCmdBinder ?: return false
        val creator = lightModeParcelableCreator ?: return false

        val lightMode = try {
            creator(modeName)
        } catch (e: Exception) {
            Log.e(TAG, "Invalid mode: $modeName", e)
            return false
        }

        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            val descriptor = binder.interfaceDescriptor ?: LIGHT_CMD_DESCRIPTOR
            data.writeInterfaceToken(descriptor)
            data.writeString(deviceId)
            val device = deviceCreator?.invoke(deviceId)
            if (device != null) {
                data.writeInt(1)
                device.writeToParcel(data, 0)
            } else {
                data.writeInt(0)
            }
            val bundle = Bundle()
            bundle.classLoader = lightMode.javaClass.classLoader
            bundle.putParcelable("value", lightMode)
            data.writeInt(1)
            bundle.writeToParcel(data, 0)

            binder.transact(TX_SET_LIGHT_MODE, data, reply, 0)
            reply.readException()
            Log.d(TAG, "executeSetLightMode($deviceId, $modeName) succeeded!")
            lastCommandedMode[deviceId] = modeName
            _actualModes.update { it + (deviceId to modeName) }
            onStateChanged?.invoke()
            return true
        } catch (e: Exception) {
            Log.e(TAG, "executeSetLightMode($deviceId, $modeName) failed", e)
            return false
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    fun registerConnectionState(deviceId: String) {
        val binder = sensorBinder
        val creator = deviceCreator
        if (binder == null || creator == null) {
            return
        }
        val device = creator(deviceId)
        val listenerId = "light-conn-$deviceId"
        if (listenerId in registeredListeners) return

        val iface = IInterface { null }
        val listenerBinder = object : Binder() {
            init { attachInterface(iface, LISTENER_DESCRIPTOR) }
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code in 1..16777215) data.enforceInterface(LISTENER_DESCRIPTOR)
                when (code) {
                    1 -> {
                        data.readString()
                        data.readString()
                        val bytes = data.createByteArray()
                        data.readInt()
                        if (bytes != null && bytes.size >= 4) {
                            val parcel = Parcel.obtain()
                            try {
                                parcel.unmarshall(bytes, 0, bytes.size)
                                parcel.setDataPosition(0)
                                val stateName = when (parcel.readInt()) {
                                    0 -> "CONNECTED"
                                    1 -> "SEARCHING"
                                    2 -> "DISABLED"
                                    3 -> "DISCONNECTED"
                                    4 -> "NOT_AVAILABLE"
                                    else -> "UNKNOWN"
                                }
                                _connectionStates.update { it + (deviceId to stateName) }
                                onStateChanged?.invoke()
                            } finally {
                                parcel.recycle()
                            }
                        }
                        reply?.writeNoException()
                    }
                    2 -> reply?.writeNoException()
                    3 -> reply?.writeNoException()
                    else -> return super.onTransact(code, data, reply, flags)
                }
                return true
            }
        }

        val callData = Parcel.obtain()
        val callReply = Parcel.obtain()
        try {
            val descriptor = binder.interfaceDescriptor ?: SENSOR_DESCRIPTOR
            callData.writeInterfaceToken(descriptor)
            callData.writeString(listenerId)
            callData.writeInt(1)
            device.writeToParcel(callData, 0)
            callData.writeStrongBinder(listenerBinder)
            binder.transact(6, callData, callReply, 0)
            callReply.readException()
            registeredListeners.add(listenerId)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register connection state for $deviceId", e)
        } finally {
            callData.recycle()
            callReply.recycle()
        }
    }

    fun registerForLightParameters(deviceId: String) {
        val binder = lightCmdBinder
        val creator = deviceCreator
        if (binder == null || creator == null) {
            return
        }
        val device = creator(deviceId)
        val listenerId = "light-params-$deviceId"
        if (listenerId in registeredListeners) return

        val iface = IInterface { null }
        val listenerBinder = object : Binder() {
            init { attachInterface(iface, LISTENER_DESCRIPTOR) }
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code in 1..16777215) data.enforceInterface(LISTENER_DESCRIPTOR)
                when (code) {
                    1 -> {
                        data.readString()
                        data.readString()
                        val bytes = data.createByteArray()
                        data.readInt()
                        if (bytes != null) {
                            parseLightParameters(deviceId, bytes)
                        }
                        reply?.writeNoException()
                    }
                    2 -> reply?.writeNoException()
                    3 -> reply?.writeNoException()
                    else -> return super.onTransact(code, data, reply, flags)
                }
                return true
            }
        }

        val callData = Parcel.obtain()
        val callReply = Parcel.obtain()
        try {
            val descriptor = binder.interfaceDescriptor ?: LIGHT_CMD_DESCRIPTOR
            callData.writeInterfaceToken(descriptor)
            callData.writeString(listenerId)
            callData.writeInt(1)
            device.writeToParcel(callData, 0)
            callData.writeStrongBinder(listenerBinder)
            binder.transact(TX_REGISTER_LIGHT_PARAMS, callData, callReply, 0)
            callReply.readException()
            registeredListeners.add(listenerId)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register light parameters for $deviceId", e)
        } finally {
            callData.recycle()
            callReply.recycle()
        }
    }

    private fun parseLightParameters(deviceId: String, bytes: ByteArray) {
        try {
            val parcel = Parcel.obtain()
            try {
                parcel.unmarshall(bytes, 0, bytes.size)
                parcel.setDataPosition(0)

                val modeName = parcel.readString()
                val locationName = parcel.readString()
                val modesCount = parcel.readInt()
                val modes = mutableSetOf<String>()
                repeat(modesCount) {
                    val name = parcel.readString()
                    if (name != null && name != "UNKNOWN") {
                        modes.add(name)
                    }
                }

                if (modes.isNotEmpty()) {
                    modes.add("OFF")
                    _supportedModes.update { it + (deviceId to modes) }
                    if (modeName != null) {
                        _actualModes.update { it + (deviceId to modeName) }
                    }
                    Log.d(TAG, "Parsed LightParameters for $deviceId: activeMode=$modeName, supportedModes=$modes")
                    onStateChanged?.invoke()
                }
            } finally {
                parcel.recycle()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse LightParameters for $deviceId", e)
        }
    }
}
