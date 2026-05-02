package com.example.osmandcellularsurround

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.IBinder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.osmand.aidlapi.IOsmAndAidlInterface
import net.osmand.aidlapi.gpx.ImportGpxParams
import net.osmand.aidlapi.gpx.ShowGpxParams
import net.osmand.aidlapi.gpx.RemoveGpxParams
import net.osmand.aidlapi.map.SetMapLocationParams
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class OsmAndHelper(private val context: Context) {

    private var osmandService: IOsmAndAidlInterface? = null
    private var isBound = false

    private var connection: ServiceConnection? = null

    suspend fun connect(): Boolean {
        if (osmandService != null && isBound) return true

        return suspendCoroutine { continuation ->
            val tempConnection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                    osmandService = IOsmAndAidlInterface.Stub.asInterface(service)
                    continuation.resume(true)
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    osmandService = null
                    isBound = false
                }
            }
            connection = tempConnection

            val intent = Intent("net.osmand.aidl.OsmandAidlServiceV2")
            intent.setPackage("net.osmand.plus")
            isBound = context.bindService(intent, tempConnection, Context.BIND_AUTO_CREATE)

            if (!isBound) {
                intent.setPackage("net.osmand")
                isBound = context.bindService(intent, tempConnection, Context.BIND_AUTO_CREATE)
            }

            if (!isBound) {
                continuation.resume(false)
            }
        }
    }

    fun disconnect() {
        if (isBound && connection != null) {
            try {
                context.unbindService(connection!!)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            isBound = false
        }
        osmandService = null
        connection = null
    }

    suspend fun showSurroundings(gpxUri: Uri, lat: Double, lon: Double, logger: (String) -> Unit) {
        val aidl = osmandService ?: return

        withContext(Dispatchers.IO) {
            try {
                // Remove the old GPX track to force an update
                val removeParams = RemoveGpxParams("cellular_surround.gpx")
                val removeSuccess = aidl.removeGpx(removeParams)
                logger("OsmAndHelper: Removed old GPX: $removeSuccess")

                // To avoid intent-firing confirmations, use the official aidl file imports.
                val importParams = ImportGpxParams(gpxUri, "cellular_surround.gpx", "red", true)
                val importSuccess = aidl.importGpx(importParams)
                logger("OsmAndHelper: Imported new GPX: $importSuccess")

                if (importSuccess) {
                    val showParams = ShowGpxParams("cellular_surround.gpx")
                    val showSuccess = aidl.showGpx(showParams)
                    logger("OsmAndHelper: Showed GPX: $showSuccess")
                }

                val locationParams = SetMapLocationParams(lat, lon, 15, 0f, true)
                val locSuccess = aidl.setMapLocation(locationParams)
                logger("OsmAndHelper: Set Map Location: $locSuccess")

                val packageManager = context.packageManager
                val launchIntent = packageManager.getLaunchIntentForPackage("net.osmand.plus")
                    ?: packageManager.getLaunchIntentForPackage("net.osmand")
                if (launchIntent != null) {
                    context.startActivity(launchIntent)
                }
            } catch (e: Exception) {
                logger("OsmAndHelper: Exception during AIDL communication: ${e.message}")
                e.printStackTrace()
            }
        }
    }
}
