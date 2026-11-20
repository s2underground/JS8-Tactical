package com.js8call.example.reports

import android.content.Context
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import java.io.File
import java.util.UUID

/** Saved reports on the device (one small text file), shared by the Reports tab and the auto-sender. */
class ReportStore private constructor(context: Context) {

    private val file = File(context.filesDir, "report_drafts.txt")
    private val lock = Any()
    private var drafts: List<ReportDrafts.Draft> = load()
    private val _all = MutableLiveData(drafts)
    val all: LiveData<List<ReportDrafts.Draft>> = _all

    fun list(): List<ReportDrafts.Draft> = synchronized(lock) { drafts }
    fun get(id: String): ReportDrafts.Draft? = list().firstOrNull { it.id == id }

    fun create(kind: String = ReportDrafts.FREE_TEXT, to: String = "", now: Long = System.currentTimeMillis()): ReportDrafts.Draft {
        val d = ReportDrafts.Draft(id = UUID.randomUUID().toString(), kind = kind, to = to, createdMs = now, updatedMs = now)
        put(d)
        return d
    }

    fun put(d: ReportDrafts.Draft) = change { list ->
        val i = list.indexOfFirst { it.id == d.id }
        if (i < 0) list + d else list.toMutableList().also { it[i] = d }
    }

    fun delete(id: String) = change { list -> list.filterNot { it.id == id } }

    /** Take the scheduled reports due at [now], moving their schedules on, in one step. */
    fun takeDue(now: Long): ReportDrafts.Due {
        var due = ReportDrafts.Due(emptyList(), emptyList(), emptyList())
        change { list -> ReportDrafts.due(list, now).also { due = it }.updated }
        return due
    }

    private fun change(f: (List<ReportDrafts.Draft>) -> List<ReportDrafts.Draft>) {
        val next = synchronized(lock) {
            val n = f(drafts)
            if (n == drafts) return
            drafts = n
            save(n)
            n
        }
        _all.postValue(next)
    }

    private fun load(): List<ReportDrafts.Draft> = try {
        if (file.exists()) ReportDrafts.decode(file.readText()) else emptyList()
    } catch (e: Exception) {
        Log.e(TAG, "Couldn't read saved reports", e)
        emptyList()
    }

    private fun save(list: List<ReportDrafts.Draft>) {
        try {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(ReportDrafts.encode(list))
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        } catch (e: Exception) {
            Log.e(TAG, "Couldn't save reports", e)
        }
    }

    companion object {
        private const val TAG = "ReportStore"
        @Volatile private var instance: ReportStore? = null
        fun of(context: Context): ReportStore =
            instance ?: synchronized(this) { instance ?: ReportStore(context.applicationContext).also { instance = it } }
    }
}
