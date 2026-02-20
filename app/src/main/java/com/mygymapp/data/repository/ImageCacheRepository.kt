package com.mygymapp.data.repository

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ImageCacheRepository @Inject constructor(
    @ApplicationContext private val context: Context,
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
            val bytes = URL(directUrl).readBytes()
            cached.writeBytes(bytes)
            cached
        } catch (_: Exception) {
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
        val driveMatch = Regex("drive\\.google\\.com/file/d/([^/]+)").find(url)
        if (driveMatch != null) {
            val fileId = driveMatch.groupValues[1]
            return "https://drive.google.com/uc?export=download&id=$fileId"
        }

        // Google Drive open link
        val driveOpen = Regex("drive\\.google\\.com/open\\?id=([^&]+)").find(url)
        if (driveOpen != null) {
            val fileId = driveOpen.groupValues[1]
            return "https://drive.google.com/uc?export=download&id=$fileId"
        }

        return url
    }

    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }.take(16)
    }
}
