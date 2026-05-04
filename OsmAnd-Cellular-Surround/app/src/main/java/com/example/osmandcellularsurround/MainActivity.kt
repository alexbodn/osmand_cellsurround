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

    // To hold latest values for SQL execution
    private var currentMinLat: Double = 0.0
    private var currentMaxLat: Double = 0.0
    private var currentMinLon: Double = 0.0
    private var currentMaxLon: Double = 0.0

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

    override fun onResume() {
        super.onResume()
        if (hasPermissions()) {
            val cellInfo = TelephonyHelper.getCurrentCellInfo(this)
            if (cellInfo != null) {
                // Populate if it's currently empty
                if (binding.etCellInfo.text.toString().trim().isEmpty()) {
                    val infoStr = "${cellInfo.radio},${cellInfo.mcc},${cellInfo.mnc},${cellInfo.lac},${cellInfo.cid}"
                    binding.etCellInfo.setText(infoStr)
                }
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

        var parsedRadio = ""
        var parsedMcc = ""
        var parsedMnc = ""
        var parsedLac = ""
        var parsedCid = ""

        val cellInfoStr = binding.etCellInfo.text.toString().trim()
        val parts = cellInfoStr.split(",")
        if (parts.size == 5) {
            parsedRadio = parts[0].trim()
            parsedMcc = parts[1].trim()
            parsedMnc = parts[2].trim()
            parsedLac = parts[3].trim()
            parsedCid = parts[4].trim()
        }

        val finalSql = sql
            .replace(":radio", "'$parsedRadio'")
            .replace(":mcc", parsedMcc.ifEmpty { "0" })
            .replace(":mnc", parsedMnc.ifEmpty { "0" })
            .replace(":lac", parsedLac.ifEmpty { "0" })
            .replace(":cid", parsedCid.ifEmpty { "0" })
            .replace(":minLat", currentMinLat.toString())
            .replace(":maxLat", currentMaxLat.toString())
            .replace(":minLon", currentMinLon.toString())
            .replace(":maxLon", currentMaxLon.toString())

        appendSqlResult("Query: $finalSql")

        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    val db = AppDatabase.getDatabase(this@MainActivity).openHelper.readableDatabase
                    val cursor = db.query(finalSql)
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

        val cellInfoStr = binding.etCellInfo.text.toString().trim()
        if (cellInfoStr.isEmpty()) {
            Toast.makeText(this, "Please enter cellular info in the text field.", Toast.LENGTH_SHORT).show()
            return
        }

        val parts = cellInfoStr.split(",")
        if (parts.size != 5) {
            Toast.makeText(this, "Invalid cellular info format. Use: radio,mcc,mnc,lac,cid", Toast.LENGTH_LONG).show()
            return
        }

        val parsedRadio = parts[0].trim()
        val parsedMcc = parts[1].trim().toIntOrNull()
        val parsedMnc = parts[2].trim().toIntOrNull()
        val parsedLac = parts[3].trim().toIntOrNull()
        val parsedCid = parts[4].trim().toLongOrNull()

        if (parsedMcc == null || parsedMnc == null || parsedLac == null || parsedCid == null) {
            Toast.makeText(this, "Invalid cellular info numbers.", Toast.LENGTH_LONG).show()
            return
        }

        binding.tvStatus.text = ""
        appendLog("Status: Scanning edited cell data...")
        Toast.makeText(this, "Scanning edited cell data...", Toast.LENGTH_SHORT).show()
        binding.btnScan.isEnabled = false

        lifecycleScope.launch {



            val msgConnected = "Resolving location for $parsedRadio MCC:$parsedMcc MNC:$parsedMnc LAC:$parsedLac CID:$parsedCid..."
            appendLog(msgConnected)
            Toast.makeText(this@MainActivity, "Resolving location...", Toast.LENGTH_SHORT).show()

            val mainTower = dataSyncManager.ensureCellTowerExistsAndGet(
                apiKey,
                parsedRadio,
                parsedMcc,
                parsedMnc,
                parsedLac,
                parsedCid
            ) { logMsg ->
                appendLog(logMsg)
            }

            if (mainTower == null) {
                val msgFailed = "Failed to resolve location for MCC:$parsedMcc MNC:$parsedMnc LAC:$parsedLac CID:$parsedCid. Please consider donating data to OpenCelliD!"
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

            currentMinLat = minLat
            currentMaxLat = maxLat
            currentMinLon = minLon
            currentMaxLon = maxLon

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
                // Zoom level heuristic: +1 zoom zooms in by 2x. We add 1.0 to fit the bounds tighter.
                val zoomDouble = 16.0 - (Math.log(radiusKm / 0.5) / Math.log(2.0))
                val zoomLevel = Math.max(2, Math.min(20, Math.round(zoomDouble).toInt()))

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
