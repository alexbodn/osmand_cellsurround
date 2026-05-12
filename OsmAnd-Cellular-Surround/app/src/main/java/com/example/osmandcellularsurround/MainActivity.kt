package com.example.osmandcellularsurround

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import android.view.View
import android.text.method.LinkMovementMethod
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
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
    private val KEY_SQL = "saved_sql" // now legacy or used for console if needed
    private val KEY_TOWERS_SQL = "towers_sql"

    // To hold latest values for SQL execution
    private var currentMinLat: Double? = null
    private var currentMaxLat: Double? = null
    private var currentMinLon: Double? = null
    private var currentMaxLon: Double? = null

    private var locationManager: LocationManager? = null

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
        // Setup Location tracking
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        // Setup Links in Background Tab
        binding.tvDocumentationLink.text = HtmlCompat.fromHtml("<a href=\"https://wiki.opencellid.org/wiki/API\">Read OpenCelliD Documentation</a>", HtmlCompat.FROM_HTML_MODE_COMPACT)
        binding.tvDocumentationLink.movementMethod = LinkMovementMethod.getInstance()

        binding.tvUserProfileLink.text = HtmlCompat.fromHtml("<a href=\"https://opencellid.org\">View your OpenCelliD Profile &amp; History</a>", HtmlCompat.FROM_HTML_MODE_COMPACT)
        binding.tvUserProfileLink.movementMethod = LinkMovementMethod.getInstance()



        binding.btnOsmAndPlugins.setOnClickListener {
            val launchIntent = packageManager.getLaunchIntentForPackage("net.osmand.plus")
                ?: packageManager.getLaunchIntentForPackage("net.osmand")
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                startActivity(launchIntent)
            }
        }

        binding.tvCredit.text = HtmlCompat.fromHtml(getString(R.string.opencellid_attribution), HtmlCompat.FROM_HTML_MODE_COMPACT)
        binding.tvCredit.movementMethod = LinkMovementMethod.getInstance()

        binding.tvOsmAndCredit.text = HtmlCompat.fromHtml(getString(R.string.osmand_attribution), HtmlCompat.FROM_HTML_MODE_COMPACT)
        binding.tvOsmAndCredit.movementMethod = LinkMovementMethod.getInstance()

        binding.btnOsmAndPlugins.setOnClickListener {
            val launchIntent = packageManager.getLaunchIntentForPackage("net.osmand.plus")
                ?: packageManager.getLaunchIntentForPackage("net.osmand")
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                startActivity(launchIntent)
            }
        }

        // Setup TabLayout
        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                when (tab?.position) {
                    0 -> {
                        binding.scrollViewBackground.visibility = View.VISIBLE
                        binding.scrollViewConfig.visibility = View.GONE
                        binding.scrollViewStatus.visibility = View.GONE
                    }
                    1 -> {
                        binding.scrollViewBackground.visibility = View.GONE
                        binding.scrollViewConfig.visibility = View.VISIBLE
                        binding.scrollViewStatus.visibility = View.GONE
                    }
                    2 -> {
                        binding.scrollViewBackground.visibility = View.GONE
                        binding.scrollViewConfig.visibility = View.GONE
                        binding.scrollViewStatus.visibility = View.VISIBLE
                    }
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        // Select initial tab
        binding.tabLayout.getTabAt(0)?.select()

        // Load saved preferences
        val savedKey = sharedPrefs.getString(KEY_API_KEY, "")
        binding.etApiKey.setText(savedKey)

        val defaultTowersSql = "SELECT lat, lon, mcc || '-' || mnc || '-' || lac || '-' || cid AS desc FROM cell_towers WHERE lat BETWEEN :minLat AND :maxLat AND lon BETWEEN :minLon AND :maxLon"

        // Load Towers SQL, migrating from old KEY_SQL if KEY_TOWERS_SQL is missing
        val oldSavedSql = sharedPrefs.getString(KEY_SQL, "")
        val savedTowersSql = sharedPrefs.getString(KEY_TOWERS_SQL, oldSavedSql)

        if (!savedTowersSql.isNullOrEmpty()) {
            binding.etTowersSql.setText(savedTowersSql)
        } else if (binding.etTowersSql.text.toString().isEmpty()) {
            // Set default SQL if empty and no saved SQL
            binding.etTowersSql.setText(defaultTowersSql)
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

        binding.btnCopySqlResult.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("OsmAnd Cellular SQL Result", binding.tvSqlResult.text)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "SQL Result copied to clipboard", Toast.LENGTH_SHORT).show()
        }

        binding.btnDonate.setOnClickListener {
            val apiKey = sharedPrefs.getString(KEY_API_KEY, "") ?: ""
            if (apiKey.isEmpty()) {
                Toast.makeText(this, "Please save an API key first.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (!hasPermissions()) {
                Toast.makeText(this, "Location and Phone permissions required to donate data.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val cellInfo = TelephonyHelper.getCurrentCellInfo(this)
            if (cellInfo == null) {
                Toast.makeText(this, "Cannot read live cell info.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            try {
                // Fetch location on-demand
                val lastKnown = locationManager?.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                if (lastKnown != null && lastKnown.hasAccuracy() && lastKnown.accuracy < 20f &&
                    (System.currentTimeMillis() - lastKnown.time < 30000)) {
                    donateLiveMeasurement(apiKey, cellInfo, lastKnown)
                } else {
                    Toast.makeText(this, "Waiting for reliable GPS (<20m)...", Toast.LENGTH_SHORT).show()
                    binding.btnDonate.isEnabled = false

                    val listener = object : LocationListener {
                        override fun onLocationChanged(location: Location) {
                            if (location.hasAccuracy() && location.accuracy < 20f) {
                                locationManager?.removeUpdates(this)
                                donateLiveMeasurement(apiKey, cellInfo, location)
                                binding.btnDonate.isEnabled = true
                            }
                        }
                        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                        override fun onProviderEnabled(provider: String) {}
                        override fun onProviderDisabled(provider: String) {
                            Toast.makeText(this@MainActivity, "GPS provider disabled", Toast.LENGTH_SHORT).show()
                            locationManager?.removeUpdates(this)
                            binding.btnDonate.isEnabled = true
                        }
                    }
                    locationManager?.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener)

                    // Stop listening after 10 seconds if no accurate fix
                    binding.btnDonate.postDelayed({
                        locationManager?.removeUpdates(listener)
                        if (!binding.btnDonate.isEnabled) {
                            Toast.makeText(this@MainActivity, "Failed to get reliable GPS.", Toast.LENGTH_SHORT).show()
                            binding.btnDonate.isEnabled = true
                        }
                    }, 10000)
                }
            } catch (e: SecurityException) {
                Toast.makeText(this, "Location permissions denied.", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnRunSql.setOnClickListener {
            val sql = binding.etSql.text.toString().trim()
            if (sql.isNotEmpty()) {
                runSql(sql)
            }
        }

        binding.btnDefaultTowersSql.setOnClickListener {
            val oldSavedSqlLocal = sharedPrefs.getString(KEY_SQL, "")
            val savedTowersSqlLocal = sharedPrefs.getString(KEY_TOWERS_SQL, oldSavedSqlLocal)
            if (!savedTowersSqlLocal.isNullOrEmpty()) {
                binding.etTowersSql.setText(savedTowersSqlLocal)
            } else {
                binding.etTowersSql.setText(defaultTowersSql)
            }
        }

        binding.btnBaseTowersSql.setOnClickListener {
            binding.etTowersSql.setText(defaultTowersSql)
        }

        binding.btnSaveTowersSql.setOnClickListener {
            val sql = binding.etTowersSql.text.toString().trim()
            sharedPrefs.edit().putString(KEY_TOWERS_SQL, sql).apply()
            Toast.makeText(this, "Towers SQL Saved", Toast.LENGTH_SHORT).show()
        }

        val openFileLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let {
                lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            contentResolver.openInputStream(it)?.use { inputStream ->
                                val text = inputStream.bufferedReader().use { reader -> reader.readText() }
                                withContext(Dispatchers.Main) {
                                    binding.etSql.setText(text)
                                    Toast.makeText(this@MainActivity, "File loaded", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@MainActivity, "Failed to read file: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }

        val runFileLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let {
                lifecycleScope.launch {
                    try {
                        appendSqlResult("--- Running SQL File ---", clear = true)
                        withContext(Dispatchers.IO) {
                            contentResolver.openInputStream(it)?.use { inputStream ->
                                inputStream.bufferedReader().useLines { lines ->
                                    val db = AppDatabase.getDatabase(this@MainActivity).openHelper.writableDatabase
                                    var count = 0
                                    var currentStatement = java.lang.StringBuilder()
                                    db.beginTransaction()
                                    try {
                                        for (line in lines) {
                                            val trimmed = line.trim()
                                            if (trimmed.isEmpty() || trimmed.startsWith("--")) continue
                                            currentStatement.append(line).append(" ")
                                            if (trimmed.endsWith(";")) {
                                                db.execSQL(currentStatement.toString())
                                                currentStatement.clear()
                                                count++
                                                if (count % 100 == 0) {
                                                    withContext(Dispatchers.Main) {
                                                        binding.tvSqlResult.text = "Executing... $count statements done."
                                                    }
                                                }
                                            }
                                        }
                                        db.setTransactionSuccessful()
                                        withContext(Dispatchers.Main) {
                                            appendSqlResult("Successfully executed $count statements from file.", clear = false)
                                        }
                                    } finally {
                                        db.endTransaction()
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@MainActivity, "Failed to run file: ${e.message}", Toast.LENGTH_SHORT).show()
                            appendSqlResult("SQL File Error: ${e.message}")
                        }
                    }
                }
            }
        }

        val saveFileLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/sql")) { uri ->
            uri?.let {
                lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            contentResolver.openOutputStream(it)?.use { outputStream ->
                                val text = binding.etSql.text.toString()
                                outputStream.write(text.toByteArray())
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(this@MainActivity, "File saved", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@MainActivity, "Failed to save file: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }

        val touchListener = android.view.View.OnTouchListener { view, event ->
            when (event.action and android.view.MotionEvent.ACTION_MASK) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    view.parent.requestDisallowInterceptTouchEvent(true)
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    // if at boundaries and trying to scroll further, allow parent intercept
                    val y = event.y
                    if (!view.canScrollVertically(-1) && event.historySize > 0 && y > event.getHistoricalY(0)) {
                        view.parent.requestDisallowInterceptTouchEvent(false)
                    } else if (!view.canScrollVertically(1) && event.historySize > 0 && y < event.getHistoricalY(0)) {
                        view.parent.requestDisallowInterceptTouchEvent(false)
                    } else {
                        view.parent.requestDisallowInterceptTouchEvent(true)
                    }
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    view.parent.requestDisallowInterceptTouchEvent(false)
                }
            }
            false
        }
        binding.etTowersSql.setOnTouchListener(touchListener)
        binding.etSql.setOnTouchListener(touchListener)

        binding.btnSaveFile.setOnClickListener {
            saveFileLauncher.launch("query.sql")
        }

        binding.btnOpenFile.setOnClickListener {
            // Support generic text files or unknown types often assigned to .sql
            openFileLauncher.launch(arrayOf("text/*", "application/sql", "application/x-sql", "text/sql", "application/octet-stream"))
        }

        binding.btnRunFile.setOnClickListener {
            runFileLauncher.launch(arrayOf("text/*", "application/sql", "application/x-sql", "text/sql", "application/octet-stream"))
        }
    }

    override fun onResume() {
        super.onResume()

        lifecycleScope.launch {
            osmandHelper.connect()
        }

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

    private fun donateLiveMeasurement(apiKey: String, cellInfo: TelephonyHelper.CellData, location: Location) {
        appendLog("Status: Donating live measurement to OpenCelliD...")

        lifecycleScope.launch {
            val success = OpenCellidApi.donateData(
                apiKey,
                cellInfo.radio,
                cellInfo.mcc,
                cellInfo.mnc,
                cellInfo.lac,
                cellInfo.cid,
                location.latitude,
                location.longitude
            ) { msg ->
                appendLog(msg)
            }

            if (success) {
                Toast.makeText(this@MainActivity, "Data donated successfully", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@MainActivity, "Failed to donate data", Toast.LENGTH_SHORT).show()
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

    private fun runSql(sql: String, showQuery: Boolean = binding.cbShowQuery.isChecked) {
        appendSqlResult("--- Running SQL ---", clear = true)

        val finalSql = buildParameterizedSql(sql)
        if (showQuery) {
            appendSqlResult("Query: $finalSql")
        }

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

            val dao = AppDatabase.getDatabase(this@MainActivity).cellTowerDao()

            var fallbackCenterLat: Double? = null
            var fallbackCenterLon: Double? = null
            val sqlEditorContent = binding.etTowersSql.text.toString().trim()

            var surroundingTowers: List<com.example.osmandcellularsurround.db.CellTowerResult> = emptyList()
            var currentTryRadiusPosition = radiusPosition
            var actualRadiusKm = 4.0

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
                } else {
                    appendLog("No towers found in LAC:$parsedLac. Bounds remain null.")
                }
            }

            while (surroundingTowers.isEmpty() && currentTryRadiusPosition < radiusValues.size) {
                actualRadiusKm = radiusValues[currentTryRadiusPosition]

                if (effectiveMainTower == null) {
                    if (fallbackCenterLat != null && fallbackCenterLon != null) {
                        appendLog("Finding surrounding towers (${actualRadiusKm}km radius around fallback center)...")
                        val boundingBox = GpxGenerator.calculateBoundingBox(fallbackCenterLat, fallbackCenterLon, actualRadiusKm)
                        currentMinLat = boundingBox[0]
                        currentMaxLat = boundingBox[1]
                        currentMinLon = boundingBox[2]
                        currentMaxLon = boundingBox[3]
                    } else {
                        currentMinLat = null
                        currentMaxLat = null
                        currentMinLon = null
                        currentMaxLon = null
                    }
                } else {
                    val msgRadius = "Finding surrounding towers (${actualRadiusKm}km radius)..."
                    appendLog(msgRadius)
                    val boundingBox = GpxGenerator.calculateBoundingBox(effectiveMainTower.lat, effectiveMainTower.lon, actualRadiusKm)
                    currentMinLat = boundingBox[0]
                    currentMaxLat = boundingBox[1]
                    currentMinLon = boundingBox[2]
                    currentMaxLon = boundingBox[3]
                }

                surroundingTowers = if (sqlEditorContent.isNotEmpty()) {
                    var finalSql = buildParameterizedSql(sqlEditorContent)
                    if (!finalSql.contains("ORDER BY", ignoreCase = true)) {
                        finalSql += " ORDER BY lat, lon"
                    }
                    appendLog("DB Query (via Towers SQL): $finalSql")
                    dao.getTowersViaSql(SimpleSQLiteQuery(finalSql))
                } else if (currentMinLat != null && currentMaxLat != null && currentMinLon != null && currentMaxLon != null) {
                    appendLog("DB Query: getTowersInBoundingBox($currentMinLat, $currentMaxLat, $currentMinLon, $currentMaxLon)")
                    dao.getTowersInBoundingBox(currentMinLat!!, currentMaxLat!!, currentMinLon!!, currentMaxLon!!)
                } else {
                    appendLog("No valid bounding box and no custom SQL provided. Aborting scan.")
                    emptyList()
                }
                surroundingTowers = if (sqlEditorContent.isNotEmpty()) {
                    var finalSql = buildParameterizedSql(sqlEditorContent)
                    if (!finalSql.contains("ORDER BY", ignoreCase = true)) {
                        finalSql += " ORDER BY lat, lon"
                    }
                    appendLog("DB Query (via Towers SQL): $finalSql")
                    dao.getTowersViaSql(SimpleSQLiteQuery(finalSql))
                } else if (currentMinLat != null && currentMaxLat != null && currentMinLon != null && currentMaxLon != null) {
                    appendLog("DB Query: getTowersInBoundingBox($currentMinLat, $currentMaxLat, $currentMinLon, $currentMaxLon)")
                    dao.getTowersInBoundingBox(currentMinLat!!, currentMaxLat!!, currentMinLon!!, currentMaxLon!!)
                } else {
                    appendLog("No valid bounding box and no custom SQL provided. Aborting scan.")
                    emptyList()
                }
                
                if (surroundingTowers.isEmpty()) {
                    appendLog("No towers found at ${actualRadiusKm}km radius. Expanding radius...")
                    currentTryRadiusPosition++
                }
            
            }

            val radiusKm = actualRadiusKm // Use final actual radius for zoom calculations downstream

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
                    val showSuccess = osmandHelper.showSurroundings(gpxUri, mapCenterLat, mapCenterLon, zoomLevel) { logMsg ->
                        appendLog(logMsg)
                    }
                    if (showSuccess) {
                        val msgDone = "Done. Check OsmAnd."
                        appendLog(msgDone)
                        Toast.makeText(this@MainActivity, msgDone, Toast.LENGTH_SHORT).show()
                    } else {
                        val msgNoConn = "Failed to show on map. Please ensure OsmAnd is installed and the Cellular Surround plugin is enabled."
                        appendLog(msgNoConn)
                        android.app.AlertDialog.Builder(this@MainActivity)
                            .setTitle("Action Required")
                            .setMessage("Please install OsmAnd if it is not installed, and enable the Cellular Surround plugin in OsmAnd's plugin menu first.")
                            .setPositiveButton("Open OsmAnd") { _, _ ->
                                val launchIntent = packageManager.getLaunchIntentForPackage("net.osmand.plus")
                                    ?: packageManager.getLaunchIntentForPackage("net.osmand")
                                if (launchIntent != null) {
                                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                                    startActivity(launchIntent)
                                } else {
                                    try {
                                        val playStoreIntent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("market://details?id=net.osmand.plus"))
                                        playStoreIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                        startActivity(playStoreIntent)
                                    } catch (e: Exception) {
                                        val webIntent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://play.google.com/store/apps/details?id=net.osmand.plus"))
                                        startActivity(webIntent)
                                    }
                                }
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                        // Retain normal text
                    }
                }
            } else {
                withContext(Dispatchers.Main) {
                    val msgNoConn = "Failed to connect to OsmAnd."
                    appendLog(msgNoConn)
                    android.app.AlertDialog.Builder(this@MainActivity)
                        .setTitle("Action Required")
                        .setMessage("Please install OsmAnd if it is not installed, and enable the Cellular Surround plugin in OsmAnd's plugin menu first.")
                        .setPositiveButton("Open OsmAnd") { _, _ ->
                            val launchIntent = packageManager.getLaunchIntentForPackage("net.osmand.plus")
                                ?: packageManager.getLaunchIntentForPackage("net.osmand")
                            if (launchIntent != null) {
                                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                                startActivity(launchIntent)
                            } else {
                                try {
                                    val playStoreIntent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("market://details?id=net.osmand.plus"))
                                    playStoreIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                    startActivity(playStoreIntent)
                                } catch (e: Exception) {
                                    val webIntent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://play.google.com/store/apps/details?id=net.osmand.plus"))
                                    startActivity(webIntent)
                                }
                            }
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }

            binding.btnScan.isEnabled = true
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        osmandHelper.disconnect()
    }
}
