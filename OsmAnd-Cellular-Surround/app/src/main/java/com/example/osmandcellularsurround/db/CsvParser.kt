package com.example.osmandcellularsurround.db

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.BufferedReader
import java.io.InputStreamReader

object CsvParser {
    suspend fun parseAndInsert(inputStream: InputStream, dao: CellTowerDao, logger: (String) -> Unit) {
        withContext(Dispatchers.IO) {
            val reader = BufferedReader(InputStreamReader(inputStream))
            // Skip header if OpenCelliD CSV has one, or just process.
            // Format is typically: radio,mcc,net,area,cell,unit,lon,lat,range,samples,changeable,created,updated,averageSignal
            // Sometimes it has no header.

            var line: String? = reader.readLine()
            val towers = mutableListOf<CellTower>()
            var totalCount = 0

            while (line != null) {
                if (line.startsWith("radio")) {
                    line = reader.readLine()
                    continue
                }

                val parts = line.split(",")
                if (parts.size >= 8) {
                    try {
                        val mcc = parts[1].toInt()
                        val mnc = parts[2].toInt()
                        val lac = parts[3].toInt()
                        val cid = parts[4].toLong()
                        val lon = parts[6].toDouble()
                        val lat = parts[7].toDouble()

                        towers.add(CellTower(mcc = mcc, mnc = mnc, lac = lac, cid = cid, lat = lat, lon = lon))

                        if (towers.size >= 5000) {
                            dao.insertAll(towers)
                            totalCount += towers.size
                            towers.clear()
                        }
                    } catch (e: Exception) {
                        // Skip malformed lines
                    }
                }
                line = reader.readLine()
            }

            if (towers.isNotEmpty()) {
                dao.insertAll(towers)
                totalCount += towers.size
            }
            reader.close()
            logger("CSV Parser: Inserted $totalCount towers.")
        }
    }
}
