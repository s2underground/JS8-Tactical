package com.js8call.example.fuzz

import com.code_intelligence.jazzer.api.FuzzedDataProvider
import com.js8call.example.storeforward.Held
import com.js8call.example.storeforward.HeldLogic
import com.js8call.example.storeforward.SfParse

/**
 * Store and forward: what this station hands out when another station asks over the air
 * (QUERY MSGS, QUERY MSG n, heartbeat ACKs), and the parsers for the replies it hears.
 *
 * Besides "never crash", checks the access rule: a message addressed to one station is only
 * ever handed to that station (same base callsign), and every "YES MSG ID n" offer points at a
 * message the asking station is allowed to fetch.
 */
object StoreForwardFuzz {
    private val CALLS = listOf("K1ABC", "K1ABC/P", "W2/K1ABC", "N0CALL", "KN4XYZ", "VE3/KN4XYZ/M", "@TEAM", "@ALLCALL", "@HB")

    private fun call(d: FuzzedDataProvider): String =
        if (d.consumeBoolean()) d.pickValue(CALLS) else d.consumeAsciiString(14)

    @JvmStatic
    fun fuzzerTestOneInput(d: FuzzedDataProvider) {
        val now = d.consumeLong(0, 4_000_000_000_000L)
        // Ids are unique and increasing, as HeldStore hands them out.
        var nextId = 0
        val all = List(d.consumeInt(0, 8)) {
            nextId += d.consumeInt(1, 3)
            Held(
                id = nextId,
                to = call(d),
                from = call(d),
                text = d.consumeAsciiString(40),
                receivedMs = d.consumeLong(0, now),
                kind = if (d.consumeBoolean()) Held.Kind.RELAY else Held.Kind.POSTED,
                expiresMs = if (d.consumeBoolean()) d.consumeLong(0, now * 2 + 1) else null,
                deliveredTo = List(d.consumeInt(0, 3)) { call(d) }.toSet(),
            )
        }
        val who = call(d)
        val to = call(d)
        val toMe = d.consumeBoolean()
        val askedId = d.consumeInt(-1, nextId + 2)

        HeldLogic.answerQueryMsgs(all, who, to, toMe, now)?.let { reply ->
            Regex("""YES MSG ID (\d+)""").find(reply)?.let { m ->
                check(HeldLogic.fetchable(all, m.groupValues[1].toInt(), who, now) != null) {
                    "offered a message $who can't fetch: $reply"
                }
            }
        }
        HeldLogic.answerQueryMsg(all, askedId, who, now)?.let { (_, h) ->
            check(h.isGroup || Held.base(h.to) == Held.base(who)) { "handed ${h.to}'s message to $who" }
            check(!h.expired(now)) { "handed out an expired message" }
        }
        HeldLogic.heartbeatExtra(all, who, to, now)

        val text = d.consumeRemainingAsString()
        SfParse.offer(text, who)
        SfParse.offerTail(text)
        SfParse.delivered(text)
        SfParse.msgTo(text)
        SfParse.queryMsgId(text)
        Held.base(text)
    }
}
