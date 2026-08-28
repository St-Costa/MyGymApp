package com.mygymapp.data.repository

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FileManager private constructor(
    private val context: Context?,
    private val explicitRoot: File?,
) {
    @Inject constructor(@ApplicationContext context: Context) : this(context, null)

    /**
     * Test seam: this project's unit-test suite has no Robolectric/Context, so file-based
     * repositories are tested by handing [FileManager] a temp dir directly. Not used in
     * production (Hilt always calls the `@Inject` constructor above).
     */
    constructor(root: File) : this(null, root)

    val root: File by lazy {
        (explicitRoot ?: File(context!!.filesDir, "gymdata")).also { it.mkdirs() }
    }

    fun getDir(name: String): File {
        return File(root, name).also { it.mkdirs() }
    }

    fun getHistoryDir(year: Int, month: Int): File {
        val monthStr = month.toString().padStart(2, '0')
        return File(root, "history/$year/$monthStr").also { it.mkdirs() }
    }
}
