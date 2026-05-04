package com.example.osmandcellularsurround.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "cell_towers",
    indices = [
        Index(value = ["lat", "lon"]), // Index for spatial bounding box query
        Index(value = ["mcc", "mnc", "cid"], unique = true) // Index for quick identification, LAC not strictly required for tower ID
    ]
)
data class CellTower(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val mcc: Int,
    val mnc: Int,
    val lac: Int,
    val cid: Long,
    val lat: Double,
    val lon: Double
)
