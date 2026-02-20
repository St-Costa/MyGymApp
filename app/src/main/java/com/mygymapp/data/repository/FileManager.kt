package com.mygymapp.data.repository

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FileManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    val root: File by lazy {
        File(context.filesDir, "gymdata").also { it.mkdirs() }
    }

    fun getDir(name: String): File {
        return File(root, name).also { it.mkdirs() }
    }

    fun getHistoryDir(year: Int, month: Int): File {
        val monthStr = month.toString().padStart(2, '0')
        return File(root, "history/$year/$monthStr").also { it.mkdirs() }
    }
}
