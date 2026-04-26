package com.example.osmandcellularsurround.api

import android.content.Context
import okhttp3.Cache
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

object HttpClientProvider {
    @Volatile
    private var instance: OkHttpClient? = null

    fun getClient(context: Context): OkHttpClient {
        return instance ?: synchronized(this) {
            val cacheSize = (50 * 1024 * 1024).toLong() // 50 MB
            val cacheDir = File(context.applicationContext.cacheDir, "http-cache")
            val cache = Cache(cacheDir, cacheSize)

            val newInstance = OkHttpClient.Builder()
                .cache(cache)
                .addInterceptor { chain ->
                    var request = chain.request()

                    // Force the cache if network is down or we just want to aggressively cache
                    // To simulate caching for 30 days to conserve quota:
                    request = request.newBuilder()
                        .header("Cache-Control", "public, max-age=" + 60 * 60 * 24 * 30)
                        .build()

                    var response = chain.proceed(request)

                    // Also force the response to be cached for 30 days
                    response.newBuilder()
                        .header("Cache-Control", "public, max-age=" + 60 * 60 * 24 * 30)
                        .build()
                }
                .build()

            instance = newInstance
            newInstance
        }
    }
}
