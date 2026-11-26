package com.js8call.example.ui.map

import android.content.Context
import android.util.Log
import androidx.preference.PreferenceManager
import com.js8call.example.BuildConfig
import com.js8call.example.util.LicenceGridTable
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel

/**
 * Approximate grid squares for US stations that never send one, from the FCC licence data
 * (issue #55, suggested by doodlecomms). The table ships in the app as assets/callgrid.bin;
 * on first use it is unpacked once to app storage and read in place (memory-mapped), so it
 * costs no heap. Lookups return null until it is ready, when the setting is off, or if the
 * app was built without the table.
 *
 * Shown on the map and in the station list as approximate. Never transmitted, never sent to
 * ATAK, never used as propagation evidence.
 */
object LicenceGrids {
    const val PREF = "map_licence_grids"
    private const val ASSET = "callgrid.bin"
    private const val TAG = "LicenceGrids"

    @Volatile private var table: LicenceGridTable? = null
    @Volatile private var started = false

    fun enabled(context: Context): Boolean =
        PreferenceManager.getDefaultSharedPreferences(context).getBoolean(PREF, true)

    /** The approximate grid for [call], or null. Starts loading the table on first call. */
    fun lookup(context: Context, call: String): String? {
        if (!enabled(context)) return null
        val t = table
        if (t == null) {
            load(context.applicationContext)
            return null
        }
        return t.lookup(call)
    }

    /** yyyymmdd of the licence data, once loaded. */
    fun dataDate(): Int? = table?.dataDate

    @Synchronized
    private fun load(app: Context) {
        if (started) return
        started = true
        Thread({
            try {
                // The asset can only change with an app update, so the unpacked copy is keyed by version.
                val dir = File(app.filesDir, "callgrid").apply { mkdirs() }
                val file = File(dir, "callgrid-${BuildConfig.VERSION_CODE}.bin")
                if (!file.exists()) {
                    dir.listFiles()?.forEach { it.delete() }
                    val tmp = File(dir, "unpacking.tmp")
                    app.assets.open(ASSET).use { input -> tmp.outputStream().use { input.copyTo(it) } }
                    if (!tmp.renameTo(file)) error("rename failed")
                }
                val mapped = RandomAccessFile(file, "r").use { raf ->
                    raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length())
                }
                table = LicenceGridTable.parse(mapped)
                Log.i(TAG, if (table != null) "licence grids: ${table?.count} calls, data ${table?.dataDate}" else "licence grids: file not readable")
            } catch (e: java.io.FileNotFoundException) {
                Log.i(TAG, "licence grids: not in this build")
            } catch (e: Exception) {
                Log.w(TAG, "licence grids unavailable", e)
            }
        }, "licence-grids").apply { isDaemon = true }.start()
    }
}
