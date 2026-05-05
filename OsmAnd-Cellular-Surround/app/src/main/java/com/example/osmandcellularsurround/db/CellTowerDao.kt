package com.example.osmandcellularsurround.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.sqlite.db.SupportSQLiteQuery

@Dao
interface CellTowerDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(cellTower: CellTower)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(cellTowers: List<CellTower>)

    @Query("SELECT * FROM cell_towers WHERE mcc = :mcc AND mnc = :mnc AND cid = :cid LIMIT 1")
    suspend fun getCellTower(mcc: Int, mnc: Int, cid: Long): CellTower?

    @Query("SELECT * FROM cell_towers WHERE mcc = :mcc AND mnc = :mnc AND lac = :lac LIMIT 1")
    suspend fun getAnyTowerInLac(mcc: Int, mnc: Int, lac: Int): CellTower?

    @Query("SELECT * FROM cell_towers WHERE mcc = :mcc AND mnc = :mnc AND lac = :lac")
    suspend fun getAllTowersInLac(mcc: Int, mnc: Int, lac: Int): List<CellTower>

    @Query("SELECT * FROM cell_towers WHERE lat BETWEEN :minLat AND :maxLat AND lon BETWEEN :minLon AND :maxLon")
    suspend fun getTowersInBoundingBox(minLat: Double, maxLat: Double, minLon: Double, maxLon: Double): List<CellTower>

    @Query("SELECT COUNT(*) FROM cell_towers WHERE mcc = :mcc")
    suspend fun countTowersByMcc(mcc: Int): Int

    @RawQuery
    suspend fun getTowersViaSql(query: SupportSQLiteQuery): List<CellTower>
}
