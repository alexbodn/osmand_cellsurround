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

        FileOutputStream(file).bufferedWriter().use { writer ->
            writer.write("<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>\n")
            writer.write("<gpx version=\"1.1\" creator=\"OsmAnd Cellular Surround\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")

            // Main connected tower (highlighted in description or color)
            writer.write("  <wpt lat=\"${mainTower.lat}\" lon=\"${mainTower.lon}\">\n")
            writer.write("    <time>$timeString</time>\n")
            writer.write("    <name>Connected: ${mainTower.mcc}-${mainTower.mnc}-${mainTower.lac}-${mainTower.cid}</name>\n")
            writer.write("    <desc>Currently Connected Cell Tower</desc>\n")
            writer.write("    <extensions>\n")
            writer.write("      <color>#FF0000</color>\n")
            writer.write("      <icon>radio_tower</icon>\n")
            writer.write("    </extensions>\n")
            writer.write("  </wpt>\n")

            // Surrounding towers
            writer.write("  <trk>\n")
            writer.write("    <name>Surrounding Towers</name>\n")
            writer.write("    <trkseg>\n")
            for (tower in surroundingTowers) {
                // Don't duplicate the main tower
                if (tower.mcc == mainTower.mcc && tower.mnc == mainTower.mnc && tower.lac == mainTower.lac && tower.cid == mainTower.cid) continue

                writer.write("      <trkpt lat=\"${tower.lat}\" lon=\"${tower.lon}\">\n")
                writer.write("        <name>${tower.mcc}-${tower.mnc}-${tower.lac}-${tower.cid}</name>\n")
                writer.write("        <extensions>\n")
                writer.write("          <icon>radio_tower</icon>\n")
                writer.write("        </extensions>\n")
                writer.write("      </trkpt>\n")
            }
            writer.write("    </trkseg>\n")
            writer.write("  </trk>\n")

            writer.write("</gpx>")
        }

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        context.grantUriPermission("net.osmand.plus", uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        context.grantUriPermission("net.osmand", uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)

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
