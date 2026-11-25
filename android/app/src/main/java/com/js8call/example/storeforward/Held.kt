package com.js8call.example.storeforward

import java.util.Locale

/**
 * Store-and-forward, the same way desktop JS8Call does it (mainwindow.cpp / Inbox.cpp), so the two
 * work together:
 *
 *  - "A: ME MSG TO:B text" asks this station to hold a message for B (desktop: type STORE).
 *  - "B: ME QUERY MSGS" (or @ALLCALL / @GROUP QUERY MSGS) asks what is held; the answer is
 *    "B YES MSG ID n", or "B NO" when asked directly and there is nothing.
 *  - "B: ME QUERY MSG n" fetches one; the answer is "B MSG text FROM A" (+ " NEXT MSG ID m").
 *  - A heartbeat ACK carries " MSG ID n" when a message is waiting for that station.
 *  - A message to a @GROUP can be fetched by anyone; each station gets it once.
 *
 * JS8-Tactical adds posted reports: the operator's own reports, offered to a group or callsign
 * until they expire. On the air they are ordinary group (or directed) stored messages.
 */
data class Held(
    val id: Int,
    /** Callsign (base) or @GROUP the message is for. */
    val to: String,
    /** Who wrote it (the original sender). */
    val from: String,
    val text: String,
    val receivedMs: Long,
    val kind: Kind,
    /** Stop offering after this; null = until removed. */
    val expiresMs: Long? = null,
    /** Stations it has been sent to. */
    val deliveredTo: Set<String> = emptySet(),
    val sends: Int = 0,
    val lastSentMs: Long? = null,
    /** JS8 speed to send it at; null = the current speed. */
    val submode: Int? = null,
    /** The report it was posted from. */
    val reportId: String? = null,
    /** Who handed it to us, when that wasn't the author. */
    val via: String? = null,
) {
    enum class Kind { RELAY, POSTED }

    val isGroup: Boolean get() = to.startsWith("@")

    /** An individual message counts as delivered once its addressee has had it. */
    val delivered: Boolean get() = !isGroup && Held.base(to) in deliveredTo.map { Held.base(it) }

    fun expired(now: Long): Boolean = expiresMs != null && now >= expiresMs

    companion object {
        /** Desktop JS8Call offers group messages for 48 hours. */
        const val GROUP_FLOOR_MS = 48 * 3600_000L

        private const val DAY_MS = 24 * 3600_000L
        /** A message held for one station is kept 30 days, or 7 days once that station has had it. */
        const val RELAY_KEEP_MS = 30 * DAY_MS
        const val RELAY_KEEP_DELIVERED_MS = 7 * DAY_MS
        /**
         * Most messages held for other stations at once. Anyone can ask us to hold one (MSG TO:),
         * so without a ceiling the list, and the file rewritten on every change, could grow
         * without end. The oldest relayed holds give way first; the operator's own posts never do.
         */
        const val MAX_RELAY_HOLDS = 200

        /** Whether [h] is still kept at [now]: expired posts, and old relayed messages, are not. */
        fun kept(h: Held, now: Long): Boolean {
            if (h.expiresMs != null && now - h.expiresMs > DAY_MS) return false
            if (h.kind != Kind.RELAY) return true
            return when {
                h.isGroup -> now - h.receivedMs <= GROUP_FLOOR_MS + DAY_MS
                h.delivered -> now - (h.lastSentMs ?: h.receivedMs) <= RELAY_KEEP_DELIVERED_MS
                else -> now - h.receivedMs <= RELAY_KEEP_MS
            }
        }

        /** [held] with room for one more relayed hold: the oldest relayed ones past the ceiling go. */
        fun makeRoomForRelay(held: List<Held>, max: Int = MAX_RELAY_HOLDS): List<Held> {
            val relays = held.filter { it.kind == Kind.RELAY }
            val excess = relays.size - (max - 1)
            if (excess <= 0) return held
            val drop = relays.sortedBy { it.receivedMs }.take(excess).map { it.id }.toSet()
            return held.filterNot { it.kind == Kind.RELAY && it.id in drop }
        }

        /** The callsign without /P, /M, W2/ and similar (desktop: Radio::base_callsign). */
        fun base(call: String): String {
            val c = call.trim().uppercase(Locale.ROOT)
            if (c.startsWith("@") || '/' !in c) return c
            return c.split('/').filter { p -> p.any { it.isDigit() } && p.any { it.isLetter() } }
                .maxByOrNull { it.length } ?: c
        }
    }
}

/** Which held message to offer or hand over; pure, so it is unit tested. */
object HeldLogic {

    private fun live(h: Held, now: Long): Boolean {
        if (h.expired(now)) return false
        // Relayed group messages follow desktop's 48-hour window; posted ones carry their own expiry.
        if (h.isGroup && h.kind == Held.Kind.RELAY && now - h.receivedMs > Held.GROUP_FLOOR_MS) return false
        return true
    }

    private fun forStation(h: Held, who: String) = !h.isGroup && Held.base(h.to) == Held.base(who)

    private fun hadIt(h: Held, who: String) = h.deliveredTo.any { Held.base(it) == Held.base(who) }

    /** The first undelivered message addressed to [who] (desktop: getNextMessageIdForCallsign). */
    fun nextFor(all: List<Held>, who: String, now: Long, after: Int = 0): Held? =
        all.filter { it.id > after && live(it, now) && forStation(it, who) && !it.delivered }.minByOrNull { it.id }

    /** The first message to [group] that [who] hasn't had (desktop: getNextGroupMessageIdForCallsign). */
    fun nextForGroup(all: List<Held>, group: String, who: String, now: Long, after: Int = 0): Held? =
        all.filter { it.id > after && live(it, now) && it.to.equals(group, true) && !hadIt(it, who) }.minByOrNull { it.id }

    /** Any group message [who] hasn't had, for a station that asks this station directly. */
    fun nextAnyGroup(all: List<Held>, who: String, now: Long, after: Int = 0): Held? =
        all.filter { it.id > after && live(it, now) && it.isGroup && !hadIt(it, who) }.minByOrNull { it.id }

    /** Message [id] if [who] may fetch it: addressed to them, or to a group (desktop: QUERY MSG). */
    fun fetchable(all: List<Held>, id: Int, who: String, now: Long): Held? {
        val h = all.firstOrNull { it.id == id } ?: return null
        if (h.expired(now)) return null
        return if (h.isGroup || forStation(h, who)) h else null
    }

    /** What to say to a QUERY MSGS from [who] sent to [to] (this station, @ALLCALL or a group); null = stay quiet. */
    fun answerQueryMsgs(all: List<Held>, who: String, to: String, toMe: Boolean, now: Long): String? {
        val mine = nextFor(all, who, now)
        val pick = mine ?: when {
            toMe -> nextAnyGroup(all, who, now)
            to.startsWith("@") -> nextForGroup(all, to, who, now)
            else -> null
        }
        return when {
            pick != null -> "$who YES MSG ID ${pick.id}"
            toMe -> "$who NO"
            else -> null
        }
    }

    /** The reply to QUERY MSG [id] from [who], and the message it hands over. */
    fun answerQueryMsg(all: List<Held>, id: Int, who: String, now: Long): Pair<String, Held>? {
        val h = fetchable(all, id, who, now) ?: return null
        val next = nextFor(all, who, now, after = id)
            ?: if (h.isGroup) nextForGroup(all, h.to, who, now, after = id) else null
        val body = "$who MSG ${h.text} FROM ${h.from}"
        return (if (next != null) "$body NEXT MSG ID ${next.id}" else body) to h
    }

    /** " MSG ID n" to add to a heartbeat ACK for [who], when something is waiting (empty if not). */
    fun heartbeatExtra(all: List<Held>, who: String, hbTo: String, now: Long): String {
        val h = nextFor(all, who, now) ?: if (hbTo.startsWith("@") && !hbTo.equals("@HB", true) && !hbTo.equals("@ALLCALL", true))
            nextForGroup(all, hbTo, who, now) else null
        return if (h != null) " MSG ID ${h.id}" else ""
    }
}

/** Reading the replies that point at a message waiting for us. */
object SfParse {
    data class Offer(val station: String, val id: Int)

    private val MSG_ID = Regex("""\bMSG ID (\d{1,6})\b""")

    /** A complete "STATION: ME … MSG ID n" (YES MSG ID, HEARTBEAT SNR … MSG ID, NEXT MSG ID). */
    fun offer(text: String, me: String): Offer? {
        val t = text.trim().uppercase(Locale.ROOT)
        val m = Regex("""^([A-Z0-9/]+):\s+([A-Z0-9/@]+)\s+(.*)$""").find(t) ?: return null
        val (from, to, rest) = m.destructured
        if (Held.base(to) != Held.base(me)) return null
        val id = MSG_ID.find(rest)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        return Offer(from, id)
    }

    /** A data frame that finishes an offer started by a directed YES / HEARTBEAT SNR frame. */
    fun offerTail(text: String): Int? = MSG_ID.find(text.uppercase(Locale.ROOT))?.groupValues?.get(1)?.toIntOrNull()

    data class Delivered(val text: String, val author: String?, val next: Int?)

    /** "hello FROM K9DGY NEXT MSG ID 6" → the text, who wrote it, and the next id. */
    fun delivered(text: String): Delivered {
        var t = text.trim()
        val next = Regex("""\s+NEXT MSG ID (\d{1,6})$""").find(t)
        val nextId = next?.groupValues?.get(1)?.toIntOrNull()
        if (next != null) t = t.substring(0, next.range.first).trim()
        val from = Regex("""\s+FROM ([A-Z0-9/]{3,})$""").find(t)
        val author = from?.groupValues?.get(1)?.takeIf { c -> c.any { it.isDigit() } }
        if (author != null) t = t.substring(0, from.range.first).trim()
        return Delivered(t, author, nextId)
    }

    /** "K9DGY hello there" (the payload of MSG TO:) → the addressee and the text. */
    fun msgTo(payload: String): Pair<String, String>? {
        val p = payload.trim().removePrefix(":").trim()
        val sp = p.indexOf(' ')
        if (sp <= 0) return null
        val to = p.substring(0, sp).uppercase(Locale.ROOT)
        val text = p.substring(sp + 1).trim()
        if (text.isBlank() || !(to.startsWith("@") || to.any { it.isDigit() })) return null
        return to to text
    }

    /** "MSG 5" (the payload of QUERY) → 5. */
    fun queryMsgId(payload: String): Int? =
        Regex("""^MSG\s+(\d{1,6})\b""").find(payload.trim().uppercase(Locale.ROOT))?.groupValues?.get(1)?.toIntOrNull()
}
