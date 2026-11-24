package com.js8call.example.fuzz

import com.code_intelligence.jazzer.api.FuzzedDataProvider
import com.js8call.example.service.RelayProtocol

/**
 * Directed and relay frames as decoded off the air: anyone can send these, and the engine
 * reads every one it hears.
 *
 * Checks: nothing throws; a checksum we generate always validates and comes off cleanly; a
 * parsed directed frame has a target and a command; a payload we forward is addressed to one
 * station with ">"; every hop in a reply path is a non-blank, trimmed token.
 */
object RelayProtocolFuzz {
    @JvmStatic
    fun fuzzerTestOneInput(d: FuzzedDataProvider) {
        // Round trip: a checksum we append must validate and strip back to the same body.
        val body = d.consumeString(60).trimStart()
        if (body.length >= 1) {
            val chk = RelayProtocol.checksum16(body)
            check(chk.length == 3) { "checksum not 3 characters: '$chk'" }
            val (ok, back) = RelayProtocol.validateChecksum("$body $chk")
            check(ok && back == body) { "own checksum failed: '$body' '$chk' → $ok '$back'" }
        }
        check(RelayProtocol.pack16Bits(d.consumeInt()).length == 3)

        val from = d.consumeString(12)
        val text = d.consumeRemainingAsString()

        RelayProtocol.parseDirectedCommand(text)?.let { c ->
            check(c.to.isNotBlank() && c.command.isNotBlank()) { "empty target or command: $c" }
            check(c.from.isNotBlank() || c.command == ">") { "senderless non-relay accepted: $c" }
        }
        RelayProtocol.heartbeatSender(text)?.let { check(it.isNotBlank() && !it.contains(':')) { "bad sender '$it'" } }

        RelayProtocol.forwardPayload(text)?.let { f ->
            val target = f.substringBefore('>')
            check(f.contains('>')) { "forward without '>': '$f'" }
            check(!target.contains('@') && !target.contains(' ')) { "forward not to one station: '$f'" }
        }

        RelayProtocol.pathCallsigns(from, text).forEach { hop ->
            check(hop.isNotBlank() && hop.trim() == hop) { "bad hop '$hop'" }
        }

        val (payload, hadEom) = RelayProtocol.normalizePayload(text)
        check(payload.length <= text.length) { "payload grew: '$payload'" }
        if (!hadEom) check(payload == text.trimEnd()) { "changed a payload without a marker: '$payload'" }
        RelayProtocol.stripOptionalChecksum(text)
        RelayProtocol.validateChecksum(text)
    }
}
