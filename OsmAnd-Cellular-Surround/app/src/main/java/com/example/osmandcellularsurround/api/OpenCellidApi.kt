package com.example.osmandcellularsurround.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import org.json.JSONObject

object OpenCellidApi {
    private lateinit var client: OkHttpClient

    fun init(context: android.content.Context) {
        client = HttpClientProvider.getClient(context)
    }

    // Fallback to Unwired Labs API to get a single cell
    suspend fun getCellLocation(apiKey: String, radio: String, mcc: Int, mnc: Int, lac: Int, cid: Long, logger: (String) -> Unit): Pair<Double, Double>? {
        return withContext(Dispatchers.IO) {
            try {
                // You can also use the direct opencellid url if unwired labs process.php requires different structure.
                // Based on standard unwired labs structure:
                val json = """
                    {
                        "token": "$apiKey",
                        "radio": "$radio",
                        "mcc": $mcc,
                        "mnc": $mnc,
                        "cells": [{
                            "cid": $cid
                        }]
                    }
                """.trimIndent()

                val maskedKey = if (apiKey.length > 4) apiKey.substring(0, 4) + "****" else "****"
                val logJson = json.replace(apiKey, maskedKey)

                logger("API Request Body: $logJson")
                val body = okhttp3.RequestBody.create("application/json".toMediaTypeOrNull(), json)
                val request = Request.Builder()
                    .url("https://us1.unwiredlabs.com/v2/process.php")
                    .post(body)
                    .build()

                val response = client.newCall(request).execute()
                val responseBody = response.body?.string()

                logger("API Response Code: ${response.code}")
                logger("API Response Body: ${responseBody ?: "null"}")

                if (response.isSuccessful && responseBody != null) {
                    val root = JSONObject(responseBody)
                    if (root.optString("status") == "ok") {
                        val lat = root.getDouble("lat")
                        val lon = root.getDouble("lon")
                        return@withContext Pair(lat, lon)
                    }
                }
            } catch (e: Exception) {
                logger("API Exception: ${e.message}")
                e.printStackTrace()
            }
            null
        }
    }
}
