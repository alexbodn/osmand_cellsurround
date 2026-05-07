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
import androidx.sqlite.db.SimpleSQLiteQuery
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
    private val KEY_SQL = "saved_sql"

    // To hold latest values for SQL execution
    private var currentMinLat: Double? = null
    private var currentMaxLat: Double? = null
    private var currentMinLon: Double? = null
    private var currentMaxLon: Double? = null

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
                        binding.scrollViewConfig.visibility = View.GONE
                    }
                    1 -> {
                        binding.scrollViewStatus.visibility = View.GONE
                        binding.scrollViewConfig.visibility = View.VISIBLE
                    }
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })


        // Load saved preferences
        val savedKey = sharedPrefs.getString(KEY_API_KEY, "")
        binding.etApiKey.setText(savedKey)
        val savedSql = sharedPrefs.getString(KEY_SQL, "")
        if (!savedSql.isNullOrEmpty()) {
            binding.etSql.setText(savedSql)
        } else if (binding.etSql.text.toString().isEmpty()) {
            // Set default SQL if empty and no saved SQL
            binding.etSql.setText("SELECT * FROM cell_towers WHERE case when :minLat is not null then lat BETWEEN :minLat AND :maxLat AND lon BETWEEN :minLon AND :maxLon else lac=:lac end")
        }

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

        binding.btnReloadCellInfo.setOnClickListener {
            if (hasPermissions()) {
                val cellInfo = TelephonyHelper.getCurrentCellInfo(this)
                if (cellInfo != null) {
                    val infoStr = "${cellInfo.radio},${cellInfo.mcc},${cellInfo.mnc},${cellInfo.lac},${cellInfo.cid}"
                    binding.etCellInfo.setText(infoStr)
                    Toast.makeText(this, "Reloaded cell info", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Could not read cell info", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "Permissions required", Toast.LENGTH_SHORT).show()
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

        binding.btnSaveSql.setOnClickListener {
            val sql = binding.etSql.text.toString().trim()
            sharedPrefs.edit().putString(KEY_SQL, sql).apply()
            Toast.makeText(this, "SQL Saved", Toast.LENGTH_SHORT).show()
        }

        val openFileLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri?.let {
                try {
                    contentResolver.openInputStream(it)?.use { inputStream ->
                        val text = inputStream.bufferedReader().use { reader -> reader.readText() }
                        binding.etSql.setText(text)
                    }
                } catch (e: Exception) {
                    Toast.makeText(this, "Failed to read file: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }

        binding.btnOpenFile.setOnClickListener {
            openFileLauncher.launch("text/*")
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

    private fun buildParameterizedSql(sql: String): String {
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

        return sql
            .replace(":radio", "'$parsedRadio'")
            .replace(":mcc", parsedMcc.ifEmpty { "0" })
            .replace(":mnc", parsedMnc.ifEmpty { "0" })
            .replace(":lac", parsedLac.ifEmpty { "0" })
            .replace(":cid", parsedCid.ifEmpty { "0" })
            .replace(":minLat", currentMinLat?.toString() ?: "null")
            .replace(":maxLat", currentMaxLat?.toString() ?: "null")
            .replace(":minLon", currentMinLon?.toString() ?: "null")
            .replace(":maxLon", currentMaxLon?.toString() ?: "null")
    }

    private fun runSql(sql: String) {
        appendSqlResult("--- Running SQL ---", clear = true)

        val finalSql = buildParameterizedSql(sql)
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

                    if (count >= 100 || cursor.moveToNext()) {
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

            var effectiveMainTower = mainTower

            val radiusPosition = binding.spinnerRadius.selectedItemPosition
            // Save it just in case they didn't hit Save Key
            sharedPrefs.edit().putInt(KEY_RADIUS, radiusPosition).apply()

            val radiusValues = arrayOf(0.5, 1.0, 1.5, 2.5, 4.0, 5.0, 7.0, 10.0, 15.0, 20.0)
            val radiusKm = if (radiusPosition in radiusValues.indices) radiusValues[radiusPosition] else 4.0

            val dao = AppDatabase.getDatabase(this@MainActivity).cellTowerDao()

            var fallbackCenterLat: Double? = null
            var fallbackCenterLon: Double? = null

            if (effectiveMainTower == null) {
                val msgFailed = "Failed to resolve exact location for CID:$parsedCid. Falling back to center of LAC:$parsedLac..."
                appendLog(msgFailed)

                val lacTowers = dao.getAllTowersInLac(parsedMcc, parsedMnc, parsedLac)
                if (lacTowers.isNotEmpty()) {
                    var sumLat = 0.0
                    var sumLon = 0.0
                    for (t in lacTowers) {
                        sumLat += t.lat
                        sumLon += t.lon
                    }
                    fallbackCenterLat = sumLat / lacTowers.size
                    fallbackCenterLon = sumLon / lacTowers.size
                    appendLog("Calculated LAC center from ${lacTowers.size} towers: ($fallbackCenterLat, $fallbackCenterLon)")

                    val boundingBox = GpxGenerator.calculateBoundingBox(fallbackCenterLat, fallbackCenterLon, radiusKm)
                    currentMinLat = boundingBox[0]
                    currentMaxLat = boundingBox[1]
                    currentMinLon = boundingBox[2]
                    currentMaxLon = boundingBox[3]
                } else {
                    appendLog("No towers found in LAC:$parsedLac. Bounds remain null.")
                    currentMinLat = null
                    currentMaxLat = null
                    currentMinLon = null
                    currentMaxLon = null
                }
            } else {
                val msgRadius = "Finding surrounding towers (${radiusKm}km radius)..."
                appendLog(msgRadius)
                val boundingBox = GpxGenerator.calculateBoundingBox(effectiveMainTower.lat, effectiveMainTower.lon, radiusKm)
                currentMinLat = boundingBox[0]
                currentMaxLat = boundingBox[1]
                currentMinLon = boundingBox[2]
                currentMaxLon = boundingBox[3]
            }

            val sqlEditorContent = binding.etSql.text.toString().trim()
            val surroundingTowers = if (sqlEditorContent.isNotEmpty()) {
                // Determine if we need to order the query
                var finalSql = buildParameterizedSql(sqlEditorContent)
                if (!finalSql.contains("ORDER BY", ignoreCase = true)) {
                    finalSql += " ORDER BY lat, lon"
                }
                appendLog("DB Query (via SQL Editor): $finalSql")
                dao.getTowersViaSql(SimpleSQLiteQuery(finalSql))
            } else if (currentMinLat != null && currentMaxLat != null && currentMinLon != null && currentMaxLon != null) {
                appendLog("DB Query: getTowersInBoundingBox($currentMinLat, $currentMaxLat, $currentMinLon, $currentMaxLon)")
                dao.getTowersInBoundingBox(currentMinLat!!, currentMaxLat!!, currentMinLon!!, currentMaxLon!!)
            } else {
                appendLog("No valid bounding box and no custom SQL provided. Aborting scan.")
                emptyList()
            }

            if (surroundingTowers.isEmpty()) {
                val msgNoTowers = "No towers found."
                appendLog(msgNoTowers)
                Toast.makeText(this@MainActivity, msgNoTowers, Toast.LENGTH_SHORT).show()
                binding.btnScan.isEnabled = true
                return@launch
            }

            val msgGpx = "Generating GPX track with ${surroundingTowers.size} surrounding towers..."
            appendLog(msgGpx)

            val gpxUri = withContext(Dispatchers.IO) {
                GpxGenerator.generateGpx(this@MainActivity, effectiveMainTower, surroundingTowers)
            }

            val msgSend = "Sending to OsmAnd..."
            appendLog(msgSend)

            val connected = osmandHelper.connect()
            if (connected) {
                // Zoom level heuristic: +1 zoom zooms in by 2x. We add 1.0 to fit the bounds tighter.
                val zoomDouble = 16.0 - (Math.log(radiusKm / 0.5) / Math.log(2.0))
                val zoomLevel = Math.max(2, Math.min(20, Math.round(zoomDouble).toInt()))

                // Determine map center
                val mapCenterLat: Double
                val mapCenterLon: Double

                if (effectiveMainTower != null) {
                    mapCenterLat = effectiveMainTower.lat
                    mapCenterLon = effectiveMainTower.lon
                } else if (fallbackCenterLat != null && fallbackCenterLon != null) {
                    mapCenterLat = fallbackCenterLat
                    mapCenterLon = fallbackCenterLon
                } else {
                    // Ultimate fallback to average of returned towers
                    var sumLat = 0.0
                    var sumLon = 0.0
                    for (t in surroundingTowers) {
                        sumLat += t.lat
                        sumLon += t.lon
                    }
                    mapCenterLat = sumLat / surroundingTowers.size
                    mapCenterLon = sumLon / surroundingTowers.size
                    appendLog("Map center defaulting to average of results: ($mapCenterLat, $mapCenterLon)")
                }

                withContext(Dispatchers.Main) {
                    osmandHelper.showSurroundings(gpxUri, mapCenterLat, mapCenterLon, zoomLevel) { logMsg ->
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
