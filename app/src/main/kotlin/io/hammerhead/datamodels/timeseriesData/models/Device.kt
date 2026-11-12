package io.hammerhead.datamodels.timeseriesData.models

import android.os.Parcel
import android.os.Parcelable

/**
 * Mirror of Karoo's internal Device class.
 */
class Device(
    val uid: String,
    val deviceInfo: DeviceInfo = DeviceInfo()
) : Parcelable {

    constructor(parcel: Parcel) : this(
        uid = parcel.readString() ?: "",
        deviceInfo = if (parcel.readInt() != 0) {
            DeviceInfo.CREATOR.createFromParcel(parcel)
        } else {
            DeviceInfo()
        }
    )

    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeString(uid)
        dest.writeInt(1)
        deviceInfo.writeToParcel(dest, flags)
    }

    companion object CREATOR : Parcelable.Creator<Device> {
        override fun createFromParcel(parcel: Parcel): Device = Device(parcel)
        override fun newArray(size: Int): Array<Device?> = arrayOfNulls(size)
    }
}
