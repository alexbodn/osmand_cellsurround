package com.example.osmandcellularsurround

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.coroutineScope
import com.example.osmandcellularsurround.databinding.ActivityMainBinding
import com.example.osmandcellularsurround.db.AppDatabase
import com.example.osmandcellularsurround.api.OpenCellidApi
import com.example.osmandcellularsurround.api.OpenCellidDownloader
import kotlinx.coroutines.Dispatchers
import android.widget.ArrayAdapter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {



    private lateinit var binding: ActivityMainBinding
    private lateinit var sharedPrefs: SharedPreferences
    private lateinit var osmandHelper: OsmAndHelper
    private lateinit var dataSyncManager: DataSyncManager

    private val PREFS_NAME = "OsmAndCellularPrefs"
    private val KEY_API_KEY = "api_key"
    private val KEY_RADIUS = "scan_radius"

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true &&
            permissions[Manifest.permission.READ_PHONE_STATE] == true) {
            performScan()
        } else {
            Toast.makeText(this, "Permissions required to scan cell networks", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        OpenCellidApi.init(this)
        OpenCellidDownloader.init(this)
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sharedPrefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        osmandHelper = OsmAndHelper(this)
        dataSyncManager = DataSyncManager(this)

        // Setup radius spinner
        ArrayAdapter.createFromResource(
            this,
            R.array.radius_options,
            android.R.layout.simple_spinner_item
        ).also { adapter ->
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            binding.spinnerRadius.adapter = adapter
        }

        // Load saved preferences
        val savedKey = sharedPrefs.getString(KEY_API_KEY, "")
        binding.etApiKey.setText(savedKey)
        val savedRadius = sharedPrefs.getInt(KEY_RADIUS, 0)
        binding.spinnerRadius.setSelection(savedRadius)

        binding.btnSaveApiKey.setOnClickListener {
            val key = binding.etApiKey.text.toString().trim()
            val radiusPosition = binding.spinnerRadius.selectedItemPosition
            if (key.isNotEmpty()) {
                sharedPrefs.edit()
                    .putString(KEY_API_KEY, key)
                    .putInt(KEY_RADIUS, radiusPosition)
                    .apply()
                Toast.makeText(this, "Preferences Saved", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnScan.setOnClickListener {
            if (binding.etApiKey.text.toString().trim().isEmpty()) {
                Toast.makeText(this, "Please enter and save an API Key first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (hasPermissions()) {
                performScan()
            } else {
                requestPermissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                        Manifest.permission.READ_PHONE_STATE
                    )
                )
            }
        }
    }

    private fun hasPermissions(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
               ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
    }

    private fun performScan() {
        val apiKey = sharedPrefs.getString(KEY_API_KEY, "") ?: return

        binding.tvStatus.text = "Status: Scanning current cell..."
        binding.btnScan.isEnabled = false

        lifecycleScope.launch {
            val cellInfo = TelephonyHelper.getCurrentCellInfo(this@MainActivity)
            if (cellInfo == null) {
                binding.tvStatus.text = "Status: Could not determine current cell (Check SIM / Signal)"
                binding.btnScan.isEnabled = true
                return@launch
            }

            binding.tvStatus.text = "Status: Connected to MCC:${cellInfo.mcc} MNC:${cellInfo.mnc} LAC:${cellInfo.lac} CID:${cellInfo.cid}. Resolving location..."

            val mainTower = dataSyncManager.ensureCellTowerExistsAndGet(
                apiKey,
                cellInfo.mcc,
                cellInfo.mnc,
                cellInfo.lac,
                cellInfo.cid
            )

            if (mainTower == null) {
                binding.tvStatus.text = "Status: Failed to resolve location for current cell tower."
                binding.btnScan.isEnabled = true
                return@launch
            }

            val radiusPosition = sharedPrefs.getInt(KEY_RADIUS, 0)
            val radiusKm = if (radiusPosition == 1) 20.0 else 4.0

            binding.tvStatus.text = "Status: Finding surrounding towers (${radiusKm.toInt()}km radius)..."

            val boundingBox = GpxGenerator.calculateBoundingBox(mainTower.lat, mainTower.lon, radiusKm)
            val minLat = boundingBox[0]
            val maxLat = boundingBox[1]
            val minLon = boundingBox[2]
            val maxLon = boundingBox[3]

            val dao = AppDatabase.getDatabase(this@MainActivity).cellTowerDao()
            val surroundingTowers = dao.getTowersInBoundingBox(minLat, maxLat, minLon, maxLon)

            binding.tvStatus.text = "Status: Generating GPX track with ${surroundingTowers.size} towers..."

            val gpxUri = GpxGenerator.generateGpx(this@MainActivity, mainTower, surroundingTowers)

            binding.tvStatus.text = "Status: Sending to OsmAnd..."

            val connected = osmandHelper.connect()
            if (connected) {
                withContext(Dispatchers.Main) {
                    osmandHelper.showSurroundings(gpxUri, mainTower.lat, mainTower.lon)
                    binding.tvStatus.text = "Status: Done. Check OsmAnd."
                }
            } else {
                binding.tvStatus.text = "Status: Failed to connect to OsmAnd. Is it installed?"
            }

            binding.btnScan.isEnabled = true
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        osmandHelper.disconnect()
    }
}
