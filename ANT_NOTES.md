# Karoo 3 ANT+ Bike Light Control — Technical Notes

This file preserves the findings from reverse-engineering and testing Hammerhead Karoo 3 `SensorService` (`io.hammerhead.sensorservice`) for ANT+ bike light control (tested on Lezyne StVZO Radar & Taillight `514597-35-113` / `514597-40-113`).

## 1. Official SDK (`karoo-ext`)
- Checked `hammerheadnav/karoo-ext` up to `1.1.9` (2026-03-17):
  - `DeviceEvent.kt` only exposes `OnConnectionStatus`, `OnBatteryStatus`, `OnManufacturerInfo`, and `OnDataPoint`.
  - `KarooEffect.kt` `RequestAnt(resourceId)` only turns on the ANT radio for `android_antlib` apps.
  - There is **no** official `LightMode` or ANT+ bike light control API in `karoo-ext`.

## 2. Why `LightONKaroo` / `KarooFireFly` fail on current Karoo 3 firmware
- Both `LightONKaroo` (`v0.1.6-R4`, Oct 2026) and `KarooFireFly` call:
  `context.createPackageContext("io.hammerhead.sensorservice", Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY)`
- On current Karoo 3 firmware, SELinux blocks `untrusted_app` from reading `/system/priv-app/hxsensorservice/hxsensorservice.apk`:
  `avc: denied { read } for name="hxsensorservice.apk" ... scontext=u:r:untrusted_app:s0:... tcontext=u:object_r:kos_app_file:s0 tclass=file permissive=0`
- `adb shell` is also denied (`Permission denied`).

## 3. SensorService AIDL Findings (Karoo 3)
- `SensorService` (`io.hammerhead.sensorservice.service.SensorService`):
  - TX code `18` returns the `LightCommandConnectionAIDL` binder (`io.hammerhead.sensorservice.LightCommandConnectionAIDL`).
- `LightCommandConnectionAIDL`:
  - `TX 1` (`registerForLightParameters(String, Device, IParcelableListener)`): **Works** when `Device` + `DeviceInfo` are marshalled manually without `createPackageContext` (`compZero_0_0`: `DeviceInfo.lastBatteryValue = 0`, `manufacturerInfo = null`, `componentInfo = 0`, `supportedDataTypes = 0`, etc.). Note: `supportedDataTypes = ["DataTypeBikeLight"]` threw `Parcel.readValue: Unmarshalling unknown type code 1268`.
  - `TX 3` (`setLightMode`, handled by `n9.a.onTransact`):
    - Signature confirmed as `(String, Device, Bundle)` (passing `null` Bundle throws `Parameter specified as non-null is null: method n9.a.onTransact, parameter bundle`).
    - Passing `Bundle` with `putParcelable("value", LightMode)` or `putString(...)` across 35+ key/arg0 combinations throws `NullPointerException: name == null` (`Enum.valueOf(null)`) inside `SensorService`.
- `io.hammerhead.datamodels.timeseriesData.models.LightMode`:
  - `STEADY4` = StVZO High, `STEADY5` = StVZO Low/Spar, `OFF` = Off.

## 4. Next Possibility
- Ask Hammerhead Support / `karoo-ext` team for an official bike light control API in `karoo-ext`, since `hxsensorservice.apk` is now locked down by SELinux (`kos_app_file`).
