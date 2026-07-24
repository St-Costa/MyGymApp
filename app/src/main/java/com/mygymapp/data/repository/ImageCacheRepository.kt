package com.mygymapp.data.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

private val GOOGLE_DRIVE_FILE_ID = Regex("drive\\.google\\.com/file/d/([^/]+)")
private val GOOGLE_DRIVE_OPEN_ID = Regex("drive\\.google\\.com/open\\?id=([^&]+)")

@Singleton
class ImageCacheRepository @Inject constructor(
    private val fileManager: FileManager,
) {
    private val cacheDir: File by lazy {
        File(fileManager.root, "cache/images").also { it.mkdirs() }
    }

    suspend fun getCachedImagePath(imageUrl: String): File? = withContext(Dispatchers.IO) {
        if (imageUrl.isBlank()) return@withContext null

        val hash = sha256(imageUrl)
        val cached = File(cacheDir, "$hash.img")
        if (cached.exists()) return@withContext cached

        try {
            val directUrl = convertToDirectUrl(imageUrl)
            val conn = (URL(directUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 15_000
            }
            try {
                conn.inputStream.use { input ->
                    cached.outputStream().use { output -> input.copyTo(output) }
                }
            } finally {
                conn.disconnect()
            }
            cached
        } catch (_: Exception) {
            cached.delete()
            null
        }
    }

    fun getCachedFile(imageUrl: String): File? {
        if (imageUrl.isBlank()) return null
        val hash = sha256(imageUrl)
        val cached = File(cacheDir, "$hash.img")
        return if (cached.exists()) cached else null
    }

    private fun convertToDirectUrl(url: String): String {
        // Google Drive: convert sharing link to direct download
        GOOGLE_DRIVE_FILE_ID.find(url)?.let { match ->
            return "https://drive.google.com/uc?export=download&id=${match.groupValues[1]}"
        }
        // Google Drive open link
        GOOGLE_DRIVE_OPEN_ID.find(url)?.let { match ->
            return "https://drive.google.com/uc?export=download&id=${match.groupValues[1]}"
        }
        return url
    }

    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }.take(16)
    }
}
