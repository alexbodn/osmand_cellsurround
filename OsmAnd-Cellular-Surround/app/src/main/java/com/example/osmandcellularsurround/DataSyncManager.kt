package com.example.osmandcellularsurround

import android.content.Context
import com.example.osmandcellularsurround.api.OpenCellidApi
import com.example.osmandcellularsurround.api.OpenCellidDownloader
import com.example.osmandcellularsurround.db.AppDatabase
import com.example.osmandcellularsurround.db.CellTower
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DataSyncManager(private val context: Context) {
    private val dao = AppDatabase.getDatabase(context).cellTowerDao()

    suspend fun ensureCellTowerExistsAndGet(apiKey: String, radio: String, mcc: Int, mnc: Int, lac: Int, cid: Long, logger: (String) -> Unit): CellTower? {
        return withContext(Dispatchers.IO) {
            // 1. Check if we need to download the MCC DB first
            // We need this even if the exact tower is in DB, because we need surrounding towers for the map.
            val mccCount = dao.countTowersByMcc(mcc)
            logger("DB Query: countTowersByMcc($mcc) -> $mccCount")
            if (mccCount == 0) {
                logger("No data for MCC $mcc. Downloading OpenCelliD CSV...")
                val success = OpenCellidDownloader.downloadAndImportMcc(context, apiKey, mcc, logger)
                logger("Download result: $success")
            }

            // 2. Check local DB for specific tower
            logger("DB Query: getCellTower(mcc=$mcc, mnc=$mnc, cid=$cid)")
            var tower = dao.getCellTower(mcc, mnc, cid)
            if (tower != null) {
                logger("DB Result: Found locally at lat=${tower.lat} lon=${tower.lon}")
                return@withContext tower
            }
            logger("DB Result: Tower not found locally.")

            // 3. Fallback: single API request for the current tower if we STILL don't have it
            // (either the MCC download failed or it's a very new tower not in the DB dump)
            logger("API Fallback: Fetching exact tower location...")
            val location = OpenCellidApi.getCellLocation(apiKey, radio, mcc, mnc, lac, cid, logger)
            if (location != null) {
                logger("API Result: lat=${location.first}, lon=${location.second}. Caching in DB.")
                tower = CellTower(mcc = mcc, mnc = mnc, lac = lac, cid = cid, lat = location.first, lon = location.second)
                dao.insert(tower)
            } else {
                logger("API Result: Null/Failed.")
            }

            return@withContext tower
        }
    }
}
