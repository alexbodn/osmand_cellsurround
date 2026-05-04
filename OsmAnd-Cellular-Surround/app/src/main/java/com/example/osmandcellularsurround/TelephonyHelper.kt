package com.example.osmandcellularsurround

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoWcdma
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat

object TelephonyHelper {

    data class CellData(val radio: String, val mcc: Int, val mnc: Int, val lac: Int, val cid: Long)

    fun getCurrentCellInfo(context: Context): CellData? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return null
        }

        val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

        val allCellInfo = try {
            telephonyManager.allCellInfo
        } catch (e: SecurityException) {
            null
        } ?: return null

        for (cellInfo in allCellInfo) {
            if (cellInfo.isRegistered) {
                if (cellInfo is CellInfoGsm) {
                    val identity = cellInfo.cellIdentity
                    if (identity.mcc != Integer.MAX_VALUE && identity.mccString != null) {
                        return CellData(
                            "gsm",
                            identity.mccString?.toIntOrNull() ?: identity.mcc,
                            identity.mncString?.toIntOrNull() ?: identity.mnc,
                            identity.lac,
                            identity.cid.toLong()
                        )
                    }
                } else if (cellInfo is CellInfoLte) {
                    val identity = cellInfo.cellIdentity
                    if (identity.mcc != Integer.MAX_VALUE && identity.mccString != null) {
                        return CellData(
                            "lte",
                            identity.mccString?.toIntOrNull() ?: identity.mcc,
                            identity.mncString?.toIntOrNull() ?: identity.mnc,
                            identity.tac,
                            identity.ci.toLong()
                        )
                    }
                } else if (cellInfo is CellInfoWcdma) {
                    val identity = cellInfo.cellIdentity
                    if (identity.mcc != Integer.MAX_VALUE && identity.mccString != null) {
                        return CellData(
                            "umts",
                            identity.mccString?.toIntOrNull() ?: identity.mcc,
                            identity.mncString?.toIntOrNull() ?: identity.mnc,
                            identity.lac,
                            identity.cid.toLong()
                        )
                    }
                }
            }
        }
        return null
    }
}
