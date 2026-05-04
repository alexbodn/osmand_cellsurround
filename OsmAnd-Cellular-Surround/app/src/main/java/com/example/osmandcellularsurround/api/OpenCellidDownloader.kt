package com.example.osmandcellularsurround.api

import android.content.Context
import com.example.osmandcellularsurround.db.AppDatabase
import com.example.osmandcellularsurround.db.CsvParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.InputStream
import java.util.zip.GZIPInputStream

object OpenCellidDownloader {
    private lateinit var client: OkHttpClient

    fun init(context: android.content.Context) {
        client = HttpClientProvider.getClient(context)
    }

    // Example URL. OpenCelliD usually provides MCC specific downloads if logged in or structured via link.
    // For the sake of this app, we will use the standard public link format if available,
    // or simulate downloading the country database.
    suspend fun downloadAndImportMcc(context: Context, apiKey: String, mcc: Int, logger: (String) -> Unit): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                // Official OpenCelliD database link format for MCC
                val url = "https://opencellid.org/ocid/downloads?token=$apiKey&type=mcc&file=$mcc.csv.gz"

                val maskedKey = if (apiKey.length > 4) apiKey.substring(0, 4) + "****" else "****"
                val logUrl = "https://opencellid.org/ocid/downloads?token=$maskedKey&type=mcc&file=$mcc.csv.gz"
                logger("Downloader: Requesting GET $logUrl")

                val request = Request.Builder().url(url).build()
                val response = client.newCall(request).execute()

                logger("Downloader: HTTP Code: ${response.code}")

                if (response.isSuccessful && response.body != null) {
                    logger("Downloader: Parsing Gzip stream...")
                    val inputStream: InputStream = response.body!!.byteStream()
                    val gzipInputStream = GZIPInputStream(inputStream)

                    val dao = AppDatabase.getDatabase(context).cellTowerDao()
                    CsvParser.parseAndInsert(gzipInputStream, dao, logger)
                    return@withContext true
                } else {
                    logger("Downloader: Failed with body ${response.body?.string() ?: ""}")
                }
            } catch (e: Exception) {
                logger("Downloader: Exception: ${e.message}")
                e.printStackTrace()
            }
            false
        }
    }
}
