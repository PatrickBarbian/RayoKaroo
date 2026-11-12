package io.hammerhead.datamodels.timeseriesData.models

import android.os.Parcel
import android.os.Parcelable

/**
 * Mirror of Karoo's internal DeviceInfo class.
 */
class DeviceInfo : Parcelable {
    constructor()

    constructor(parcel: Parcel)

    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        // Empty default DeviceInfo
    }

    companion object CREATOR : Parcelable.Creator<DeviceInfo> {
        override fun createFromParcel(parcel: Parcel): DeviceInfo = DeviceInfo(parcel)
        override fun newArray(size: Int): Array<DeviceInfo?> = arrayOfNulls(size)
    }
}
