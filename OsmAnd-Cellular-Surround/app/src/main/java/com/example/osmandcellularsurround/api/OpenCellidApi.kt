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
    suspend fun getCellLocation(apiKey: String, mcc: Int, mnc: Int, lac: Int, cid: Long): Pair<Double, Double>? {
        return withContext(Dispatchers.IO) {
            try {
                // You can also use the direct opencellid url if unwired labs process.php requires different structure.
                // Based on standard unwired labs structure:
                val json = """
                    {
                        "token": "$apiKey",
                        "radio": "gsm",
                        "mcc": $mcc,
                        "mnc": $mnc,
                        "cells": [{
                            "lac": $lac,
                            "cid": $cid
                        }]
                    }
                """.trimIndent()

                val body = okhttp3.RequestBody.create("application/json".toMediaTypeOrNull(), json)
                val request = Request.Builder()
                    .url("https://us1.unwiredlabs.com/v2/process.php")
                    .post(body)
                    .build()

                val response = client.newCall(request).execute()
                val responseBody = response.body?.string()

                if (response.isSuccessful && responseBody != null) {
                    val root = JSONObject(responseBody)
                    if (root.optString("status") == "ok") {
                        val lat = root.getDouble("lat")
                        val lon = root.getDouble("lon")
                        return@withContext Pair(lat, lon)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            null
        }
    }
}
