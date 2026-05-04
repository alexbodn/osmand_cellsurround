package com.example.osmandcellularsurround

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.example.osmandcellularsurround.db.CellTower
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object GpxGenerator {

    fun generateGpx(context: Context, mainTower: CellTower, surroundingTowers: List<CellTower>): Uri {
        val fileName = "cellular_surround.gpx"
        val file = File(context.cacheDir, fileName)

        if (file.exists()) {
            file.delete()
        }

        val timeString = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(Date())

        val gpxStr = StringBuilder()

        gpxStr.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\" ?>\n")
        gpxStr.append("<gpx version=\"1.1\" creator=\"OsmAnd Cellular Surround\" xmlns=\"http://www.topografix.com/GPX/1/1\" xmlns:osmand=\"https://osmand.net/docs/technical/osmand-file-formats/osmand-gpx\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xsi:schemaLocation=\"http://www.topografix.com/GPX/1/1 https://www.topografix.com/GPX/1/1/gpx.xsd\">\n")

        gpxStr.append("  <metadata>\n")
        gpxStr.append("    <name>cellular_surround</name>\n")
        gpxStr.append("  </metadata>\n")

        // Main connected tower (highlighted in description or color)
        gpxStr.append("  <wpt lat=\"${mainTower.lat}\" lon=\"${mainTower.lon}\">\n")
        gpxStr.append("    <time>$timeString</time>\n")
        gpxStr.append("    <desc>Connected: ${mainTower.mcc}-${mainTower.mnc}-${mainTower.lac}-${mainTower.cid}</desc>\n")
        gpxStr.append("    <type>main_tower</type>\n")
        gpxStr.append("  </wpt>\n")

        // Surrounding towers
        for (tower in surroundingTowers) {
            // Don't duplicate the main tower
            if (tower.mcc == mainTower.mcc && tower.mnc == mainTower.mnc && tower.cid == mainTower.cid) continue

            gpxStr.append("  <wpt lat=\"${tower.lat}\" lon=\"${tower.lon}\">\n")
            gpxStr.append("    <desc>${tower.mcc}-${tower.mnc}-${tower.lac}-${tower.cid}</desc>\n")
            gpxStr.append("    <type>surrounding_tower</type>\n")
            gpxStr.append("  </wpt>\n")
        }

        gpxStr.append("  <extensions>\n")
        gpxStr.append("    <osmand:show_start_finish>false</osmand:show_start_finish>\n")
        gpxStr.append("    <osmand:show_arrows>true</osmand:show_arrows>\n")
        gpxStr.append("    <osmand:color>#FF0000</osmand:color>\n")
        gpxStr.append("    <osmand:points_groups>\n")
        gpxStr.append("      <group name=\"main_tower\" icon=\"radio_tower\" background=\"circle\" color=\"#00FF00\" />\n")
        gpxStr.append("      <group name=\"surrounding_tower\" icon=\"radio_tower\" background=\"circle\" color=\"#0000FF\" />\n")
        gpxStr.append("    </osmand:points_groups>\n")
        gpxStr.append("  </extensions>\n")

        gpxStr.append("</gpx>")

        FileOutputStream(file).use {
            it.write(gpxStr.toString().toByteArray())
        }

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

        try {
            context.grantUriPermission("net.osmand.plus", uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } catch (e: Exception) {
            // Package might not be installed
        }

        try {
            context.grantUriPermission("net.osmand", uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } catch (e: Exception) {
            // Package might not be installed
        }

        return uri
    }

    // Calculates bounding box approx `radiusKm` around a center point
    fun calculateBoundingBox(lat: Double, lon: Double, radiusKm: Double): DoubleArray {
        // 1 degree of latitude is ~111km
        val latOffset = radiusKm / 111.0
        // longitude offset depends on latitude
        val lonOffset = (radiusKm / 111.0) / Math.cos(Math.toRadians(lat))

        return doubleArrayOf(
            lat - latOffset, // minLat
            lat + latOffset, // maxLat
            lon - lonOffset, // minLon
            lon + lonOffset  // maxLon
        )
    }
}
