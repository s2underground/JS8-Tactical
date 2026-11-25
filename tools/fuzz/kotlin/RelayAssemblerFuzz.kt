package com.js8call.example.fuzz

import com.code_intelligence.jazzer.api.FuzzedDataProvider
import com.js8call.example.service.RelayAction
import com.js8call.example.service.RelayAssembler
import com.js8call.example.util.CallsignValidator

/**
 * Relays as heard off the air, frame by frame: any station can send any sequence of frames at
 * any offset and time, and every completed relay may make us transmit.
 *
 * Checks: nothing throws; memory stays bounded (no relay text past the cap, at most one relay
 * per offset in the passband); a forward is always "NEXT> …" to a single station, never to us,
 * with the sender who handed it to us named; a reply path is callsigns only, never us.
 */
object RelayAssemblerFuzz {
    private const val MAX_CHARS = 200

    @JvmStatic
    fun fuzzerTestOneInput(d: FuzzedDataProvider) {
        val a = RelayAssembler(timeoutMs = 90_000, maxChars = MAX_CHARS)
        val me = d.pickValue(arrayOf("KN4CRD", "KN4CRD/P", "EA8/KN4CRD", "K1A"))
        var now = 0L
        repeat(d.consumeInt(1, 40)) {
            now += d.consumeLong(0, 120_000)
            val text = when (d.consumeInt(0, 3)) {
                0 -> "${d.consumeString(10)}: ${d.pickValue(arrayOf(me, "KN4CRD", "@RACES", "W1ABC"))}> ${d.consumeString(40)}"
                else -> d.consumeString(40)
            }
            val freq = d.consumeFloat()
            val relay = a.onFrame(text, d.consumeInt(-30, 30), freq, d.consumeInt(0, 7), now, me, d.consumeInt(0, 4))
            check(a.pendingCount() <= 400) { "too many relays pending: ${a.pendingCount()}" }
            if (relay != null) {
                check(relay.text.isNotEmpty() && relay.text.length <= MAX_CHARS) { "relay text out of bounds: ${relay.text.length}" }
                when (val action = RelayAssembler.decide(relay, me)) {
                    is RelayAction.Forward -> {
                        val target = action.text.substringBefore('>')
                        check(action.text.contains('>') && target.isNotBlank() && !target.contains('@') && !target.contains(' ')) {
                            "forward not to one station: '${action.text}'"
                        }
                        if (relay.from.isNotBlank()) check(action.text.endsWith(" *DE* ${relay.from}")) { "forward lost its sender: '${action.text}'" }
                        check(!CallsignValidator.matches(me, target)) { "forward to ourselves: '${action.text}'" }
                    }
                    is RelayAction.Deliver -> {
                        check(action.path.split('>').all { CallsignValidator.isAmateurCallsign(it) && !CallsignValidator.matches(me, it) }) {
                            "bad path '${action.path}' from='${relay.from}' payload='${action.payload}'"
                        }
                        check(action.payload.isNotBlank()) { "blank delivery" }
                    }
                    null -> Unit
                }
            }
        }
    }
}
