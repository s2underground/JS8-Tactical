package com.js8call.example

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper

/**
 * In-app broadcasts between the engine service and the screens.
 *
 * Replaces androidx LocalBroadcastManager (deprecated) with the same behaviour: intents never
 * leave the app process, receivers are matched by their IntentFilter, and delivery happens later
 * on the main thread, in the order the intents were sent. A receiver unregistered before delivery
 * doesn't get it.
 */
object AppBroadcasts {

    private const val LOG_TAG = "AppBroadcasts"

    @Volatile private var appContext: Context? = null
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    private val dispatcher = BroadcastDispatcher<BroadcastReceiver, IntentFilter, Intent>(
        post = { mainHandler.post(it) },
        matches = { filter, intent ->
            val type = appContext?.let { intent.resolveTypeIfNeeded(it.contentResolver) }
            filter.match(intent.action, type, intent.scheme, intent.data, intent.categories, LOG_TAG) >= 0
        },
        deliver = { receiver, intent -> appContext?.let { receiver.onReceive(it, intent) } },
    )

    fun register(context: Context, receiver: BroadcastReceiver, filter: IntentFilter) {
        appContext = context.applicationContext
        dispatcher.register(receiver, filter)
    }

    fun unregister(receiver: BroadcastReceiver) = dispatcher.unregister(receiver)

    /** Returns true if at least one receiver will get the intent. */
    fun send(context: Context, intent: Intent): Boolean {
        if (appContext == null) appContext = context.applicationContext
        return dispatcher.send(intent)
    }
}

/**
 * The bookkeeping behind [AppBroadcasts], free of Android types so it can be unit-tested.
 * [post] schedules a delivery (the main thread in the app); [matches] decides whether a filter
 * accepts a message; [deliver] hands it to a receiver.
 */
internal class BroadcastDispatcher<R : Any, F : Any, M : Any>(
    private val post: (Runnable) -> Unit,
    private val matches: (F, M) -> Boolean,
    private val deliver: (R, M) -> Unit,
) {
    private class Registration<R, F>(val receiver: R, val filter: F) {
        @Volatile var active = true
    }

    private val registrations = mutableListOf<Registration<R, F>>()

    fun register(receiver: R, filter: F) {
        synchronized(registrations) { registrations += Registration(receiver, filter) }
    }

    fun unregister(receiver: R) {
        synchronized(registrations) {
            val it = registrations.iterator()
            while (it.hasNext()) {
                val r = it.next()
                if (r.receiver === receiver) {
                    r.active = false
                    it.remove()
                }
            }
        }
    }

    fun send(message: M): Boolean {
        val targets = synchronized(registrations) { registrations.filter { matches(it.filter, message) } }
        if (targets.isEmpty()) return false
        post(Runnable { targets.forEach { if (it.active) deliver(it.receiver, message) } })
        return true
    }
}
