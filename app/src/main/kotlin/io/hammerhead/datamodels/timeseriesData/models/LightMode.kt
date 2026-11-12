package io.hammerhead.datamodels.timeseriesData.models

import android.os.Parcel
import android.os.Parcelable

/**
 * Mirror of Karoo's internal LightMode enum.
 * Matches exact package name and class name so that Android IPC Parcel
 * serialization (Bundle.putParcelable("value", mode)) produces the exact
 * AIDL type expected by io.hammerhead.sensorservice.LightCommandConnectionAIDL.
 */
enum class LightMode : Parcelable {
    OFF,
    STEADY1,
    STEADY2,
    STEADY3,
    STEADY4,
    STEADY5,
    FLASH1,
    FLASH2,
    FLASH3,
    FLASH4,
    FLASH5,
    DAY_FLASH,
    NIGHT_FLASH,
    AUTO;

    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeString(name)
    }

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<LightMode> = object : Parcelable.Creator<LightMode> {
            override fun createFromParcel(source: Parcel): LightMode {
                val modeName = source.readString() ?: OFF.name
                return fromName(modeName)
            }

            override fun newArray(size: Int): Array<LightMode?> = arrayOfNulls(size)
        }

        fun fromName(name: String): LightMode {
            val upper = name.uppercase().trim()
            return when {
                upper == "OFF" || upper == "AUS" -> OFF
                upper == "STEADY1" || upper == "STEADY4" || upper == "STEADY_HIGH" || upper == "HIGH" || upper == "HELL" -> STEADY1
                upper == "STEADY5" || upper == "STEADY2" || upper == "STEADY3" || upper == "STEADY_LOW" || upper == "LOW" || upper == "SPAR" -> STEADY5
                upper == "DAY_FLASH" || upper == "DAY_PULSE" || upper == "FLASH1" -> DAY_FLASH
                upper == "NIGHT_FLASH" || upper == "FLASH2" -> NIGHT_FLASH
                upper == "AUTO" || upper == "SMART" -> AUTO
                else -> {
                    try {
                        valueOf(upper)
                    } catch (_: Exception) {
                        STEADY1
                    }
                }
            }
        }
    }
}
