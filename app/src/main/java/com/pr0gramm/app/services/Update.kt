package com.pr0gramm.app.services

import android.os.Parcel
import android.os.Parcelable
import com.pr0gramm.app.parcel.creator
import com.squareup.moshi.JsonClass

/**
 * Update
 */
@JsonClass(generateAdapter = true)
data class Update(val version: Int, val apk: String, val changelog: String) : Parcelable {
    fun versionStr(): String {
        return String.format("1.%d.%d", version / 10, version % 10)
    }

    override fun describeContents(): Int {
        return 0
    }

    override fun writeToParcel(dest: Parcel, f: Int) {
        dest.writeInt(version)
        dest.writeString(apk)
        dest.writeString(changelog)
    }

    companion object {
        @JvmField
        val CREATOR = creator {
            Update(it.readInt(), it.readString(), it.readString())
        }
    }
}
