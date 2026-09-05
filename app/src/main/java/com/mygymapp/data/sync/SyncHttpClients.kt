package com.mygymapp.data.sync

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Shared connection pools for all sync APIs; preserves the existing timeout profiles. */
object SyncHttpClients {
    private val clientMetadataInterceptor = okhttp3.Interceptor { chain ->
        val builder = chain.request().newBuilder()
        SyncRequestContext.currentHeaders().forEach { (name, value) -> builder.header(name, value) }
        chain.proceed(builder.build())
    }

    val health: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    val standard: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(clientMetadataInterceptor)
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    val bulk: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(clientMetadataInterceptor)
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    val restore: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(clientMetadataInterceptor)
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    val ecg: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(clientMetadataInterceptor)
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
}
