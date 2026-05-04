package com.example.osmandcellularsurround

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import android.view.View
import android.text.method.LinkMovementMethod
import androidx.core.text.HtmlCompat
import com.google.android.material.tabs.TabLayout
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.coroutineScope
import com.example.osmandcellularsurround.databinding.ActivityMainBinding
import com.example.osmandcellularsurround.db.AppDatabase
import com.example.osmandcellularsurround.api.OpenCellidApi
import com.example.osmandcellularsurround.api.OpenCellidDownloader
import kotlinx.coroutines.Dispatchers
import android.widget.ArrayAdapter
import android.content.ClipboardManager
import android.content.ClipData
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

        // Setup API Key label with clickable link
        binding.tvApiKeyLabel.text = HtmlCompat.fromHtml(getString(R.string.api_key_label), HtmlCompat.FROM_HTML_MODE_COMPACT)
        binding.tvApiKeyLabel.movementMethod = LinkMovementMethod.getInstance()


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
        // Setup TabLayout
        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                when (tab?.position) {
                    0 -> {
                        binding.scrollViewStatus.visibility = View.VISIBLE
                        binding.llSqlContainer.visibility = View.GONE
                        binding.btnCopyLog.text = "Copy Log"
                    }
                    1 -> {
                        binding.scrollViewStatus.visibility = View.GONE
                        binding.llSqlContainer.visibility = View.VISIBLE
                        binding.btnCopyLog.text = "Copy SQL Result"
                    }
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })


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

        binding.btnCopyLog.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val isSqlTab = binding.tabLayout.selectedTabPosition == 1
            val textToCopy = if (isSqlTab) binding.tvSqlResult.text else binding.tvStatus.text
            val label = if (isSqlTab) "OsmAnd Cellular SQL Result" else "OsmAnd Cellular Log"
            val clip = ClipData.newPlainText(label, textToCopy)
            clipboard.setPrimaryClip(clip)
            val toastMsg = if (isSqlTab) "SQL Result copied to clipboard" else "Log copied to clipboard"
            Toast.makeText(this, toastMsg, Toast.LENGTH_SHORT).show()
        }

        binding.btnRunSql.setOnClickListener {
            val sql = binding.etSql.text.toString().trim()
            if (sql.isNotEmpty()) {
                runSql(sql)
            }
        }
    }

    private fun appendSqlResult(msg: String, clear: Boolean = false) {
        runOnUiThread {
            if (clear) {
                binding.tvSqlResult.text = msg
            } else {
                val current = binding.tvSqlResult.text.toString()
                binding.tvSqlResult.text = "$current\n$msg"
            }
            binding.scrollViewSqlResult.post {
                binding.scrollViewSqlResult.fullScroll(View.FOCUS_DOWN)
            }
        }
    }

    private fun runSql(sql: String) {
        appendSqlResult("--- Running SQL ---", clear = true)
        appendSqlResult("Query: $sql")

        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    val db = AppDatabase.getDatabase(this@MainActivity).openHelper.readableDatabase
                    val cursor = db.query(sql)
                    val columns = cursor.columnNames
                    appendSqlResult(columns.joinToString(" | "))

                    var count = 0
                    while (cursor.moveToNext() && count < 100) { // limit output so we don't crash the textview
                        val row = StringBuilder()
                        for (i in columns.indices) {
                            val value = try {
                                cursor.getString(i)
                            } catch (e: Exception) {
                                "blob"
                            }
                            row.append(value).append(" | ")
                        }
                        appendSqlResult(row.toString())
                        count++
                    }
                    if (cursor.moveToNext()) {
                        appendSqlResult("... (results truncated to 100 rows)")
                    }
                    cursor.close()
                    appendSqlResult("--- End SQL ---")
                } catch (e: Exception) {
                    appendSqlResult("SQL Error: ${e.message}")
                }
            }
        }
    }

    private fun hasPermissions(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
               ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
    }

    private fun appendLog(msg: String) {
        runOnUiThread {
            val current = binding.tvStatus.text.toString()
            binding.tvStatus.text = "$current\n$msg"
            binding.scrollViewStatus.post {
                binding.scrollViewStatus.fullScroll(android.view.View.FOCUS_DOWN)
            }
        }
    }

    private fun performScan() {
        val apiKey = sharedPrefs.getString(KEY_API_KEY, "") ?: return

        binding.tvStatus.text = ""
        appendLog("Status: Scanning current cell...")
        Toast.makeText(this, "Scanning current cell...", Toast.LENGTH_SHORT).show()
        binding.btnScan.isEnabled = false

        lifecycleScope.launch {



            val cellInfo = TelephonyHelper.getCurrentCellInfo(this@MainActivity)
            if (cellInfo == null) {
                val msg = "Could not determine current cell (Check SIM / Signal)"
                appendLog(msg)
                Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
                binding.btnScan.isEnabled = true
                return@launch
            }


            val msgConnected = "Connected to ${cellInfo.radio} MCC:${cellInfo.mcc} MNC:${cellInfo.mnc} LAC:${cellInfo.lac} CID:${cellInfo.cid}. Resolving location..."
            appendLog(msgConnected)
            Toast.makeText(this@MainActivity, "Resolving location...", Toast.LENGTH_SHORT).show()

            val mainTower = dataSyncManager.ensureCellTowerExistsAndGet(
                apiKey,
                cellInfo.radio,
                cellInfo.mcc,
                cellInfo.mnc,
                cellInfo.lac,
                cellInfo.cid
            ) { logMsg ->
                appendLog(logMsg)
            }

            if (mainTower == null) {
                val msgFailed = "Failed to resolve location for MCC:${cellInfo.mcc} MNC:${cellInfo.mnc} LAC:${cellInfo.lac} CID:${cellInfo.cid}. Please consider donating data to OpenCelliD!"
                appendLog(msgFailed)
                Toast.makeText(this@MainActivity, msgFailed, Toast.LENGTH_LONG).show()
                binding.btnScan.isEnabled = true
                return@launch
            }

            val radiusPosition = binding.spinnerRadius.selectedItemPosition
            // Save it just in case they didn't hit Save Key
            sharedPrefs.edit().putInt(KEY_RADIUS, radiusPosition).apply()

            val radiusValues = arrayOf(0.5, 1.0, 1.5, 2.5, 4.0, 5.0, 7.0, 10.0, 15.0, 20.0)
            val radiusKm = if (radiusPosition in radiusValues.indices) radiusValues[radiusPosition] else 4.0

            val msgRadius = "Finding surrounding towers (${radiusKm}km radius)..."
            appendLog(msgRadius)

            val boundingBox = GpxGenerator.calculateBoundingBox(mainTower.lat, mainTower.lon, radiusKm)
            val minLat = boundingBox[0]
            val maxLat = boundingBox[1]
            val minLon = boundingBox[2]
            val maxLon = boundingBox[3]

            val dao = AppDatabase.getDatabase(this@MainActivity).cellTowerDao()
            appendLog("DB Query: getTowersInBoundingBox($minLat, $maxLat, $minLon, $maxLon)")
            val surroundingTowers = dao.getTowersInBoundingBox(minLat, maxLat, minLon, maxLon)

            val msgGpx = "Generating GPX track with ${surroundingTowers.size} surrounding towers..."
            appendLog(msgGpx)

            val gpxUri = withContext(Dispatchers.IO) {
                GpxGenerator.generateGpx(this@MainActivity, mainTower, surroundingTowers)
            }

            val msgSend = "Sending to OsmAnd..."
            appendLog(msgSend)

            val connected = osmandHelper.connect()
            if (connected) {
                // Calculate zoom level. 15 is roughly 0.5km. log2(15 / radius) is a decent heuristic.
                // 0.5km -> ~15, 1.0km -> ~14, 2.0km -> ~13, 4.0km -> ~12, 10km -> ~10.7 (round down or up), etc.
                val zoomDouble = 15.0 - (Math.log(radiusKm / 0.5) / Math.log(2.0))
                val zoomLevel = Math.max(2, Math.min(20, zoomDouble.toInt()))

                withContext(Dispatchers.Main) {
                    osmandHelper.showSurroundings(gpxUri, mainTower.lat, mainTower.lon, zoomLevel) { logMsg ->
                        appendLog(logMsg)
                    }
                    val msgDone = "Done. Check OsmAnd."
                    appendLog(msgDone)
                    Toast.makeText(this@MainActivity, msgDone, Toast.LENGTH_SHORT).show()
                }
            } else {
                val msgNoConn = "Failed to connect to OsmAnd. Is it installed?"
                appendLog(msgNoConn)
                Toast.makeText(this@MainActivity, msgNoConn, Toast.LENGTH_LONG).show()
            }

            binding.btnScan.isEnabled = true
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        osmandHelper.disconnect()
    }
}
