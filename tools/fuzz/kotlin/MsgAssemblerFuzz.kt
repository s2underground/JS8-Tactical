package com.js8call.example.fuzz

import com.code_intelligence.jazzer.api.FuzzedDataProvider
import com.js8call.example.service.MsgAssembler
import com.js8call.example.service.RelayProtocol
import com.js8call.example.util.CallsignValidator
import com.js8call.example.util.Js8Commands

/**
 * Stored-message commands (MSG, MSG TO:, QUERY) as heard off the air, frame by frame: any station
 * can send any sequence of frames, and an intact one may be acknowledged or answered on the air.
 *
 * Checks: nothing throws; memory stays bounded; a message is only for a target we accept, and a
 * QUERY only for us; "intact" means exactly that: no gaps and a checksum that matches the text;
 * a text we sign with a checksum always comes back intact and unchanged.
 */
object MsgAssemblerFuzz {
    private const val MAX_CHARS = 300

    @JvmStatic
    fun fuzzerTestOneInput(d: FuzzedDataProvider) {
        // Round trip: a message we sign comes back as written, intact.
        val body = d.consumeString(40).trim()
        if (body.isNotEmpty() && body.none { it == '♢' || it == '▪' || it == '■' }) {
            val (text, intact) = MsgAssembler.clean("$body ${RelayProtocol.checksum16(body)}")
            check(intact && text == body) { "own checksum failed: '$body' → '$text' $intact" }
        }

        val a = MsgAssembler(maxChars = MAX_CHARS)
        val accepts: (String, String) -> Boolean = { cmd, to -> to == "KN4CRD" || (cmd != Js8Commands.CMD_QUERY && to == "@RACES") }
        var now = 0L
        repeat(d.consumeInt(1, 40)) {
            now += d.consumeLong(0, 120_000)
            val text = when (d.consumeInt(0, 3)) {
                0 -> "${d.consumeString(10)}: ${d.pickValue(arrayOf("KN4CRD", "@RACES", "W1ABC"))} " +
                    "${d.pickValue(arrayOf("MSG", "MSG TO:", "QUERY", "ACK"))} ${d.consumeString(30)}"
                else -> d.consumeString(30)
            }
            val msg = a.onFrame(text, d.consumeInt(-30, 30), d.consumeFloat(), d.consumeInt(0, 7), now, d.consumeInt(0, 4),
                d.pickValue(arrayOf(0L, 4_000L, 6_000L, 10_000L, 15_000L, 30_000L)), accepts)
            check(a.pendingCount() <= 400) { "too many messages pending: ${a.pendingCount()}" }
            if (msg != null) {
                check(accepts(msg.command, msg.to)) { "took a message not for us: $msg" }
                check(msg.command in MsgAssembler.COMMANDS) { "not a stored-message command: ${msg.command}" }
                check(msg.text.isNotBlank() && msg.text.length <= MAX_CHARS) { "text out of bounds: ${msg.text.length}" }
                check(msg.gaps >= 0) { "negative gaps" }
                check(CallsignValidator.isAmateurCallsign(msg.from)) { "sender is not a callsign: '${msg.from}'" }
                if (msg.intact) check(msg.gaps == 0) { "intact with frames missing: $msg" }
            }
        }
    }
}
