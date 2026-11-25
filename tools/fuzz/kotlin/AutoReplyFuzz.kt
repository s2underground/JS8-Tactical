package com.js8call.example.fuzz

import com.code_intelligence.jazzer.api.FuzzedDataProvider
import com.js8call.example.service.AutoReply
import com.js8call.example.service.RelayProtocol
import com.js8call.example.util.CallsignValidator

/**
 * Automatic replies: text from anyone on the air decides whether this station transmits.
 *
 * Checks: never answers its own frames or a group; only answers frames addressed to it; a
 * reply is addressed back to the asker (or is the last transmission, for AGN?); SNR text is
 * always plain "+NN"/"-NN"; nothing throws.
 */
object AutoReplyFuzz {
    private const val ME = "W2XYZ"

    @JvmStatic
    fun fuzzerTestOneInput(d: FuzzedDataProvider) {
        val snr = d.consumeInt(-100, 100)
        AutoReply.formatSnr(snr).let { s ->
            check(s.isEmpty() || Regex("^[+-][0-9]{2}$").matches(s)) { "bad SNR text '$s' for $snr" }
        }
        val me = AutoReply.Station(ME, info = "INFO", status = "QRV", grid = "FN42", lastTx = "LAST")
        val heard = AutoReply.Heard { ex -> listOf("K1ABC", "N3QRS", ME).filterNot { it in ex } }
        val relayPath = d.consumeString(24)
        val text = d.consumeRemainingAsString()

        AutoReply.heartbeatAck(text, snr, me)?.let { ack ->
            val from = RelayProtocol.heartbeatSender(text)!!
            check(!CallsignValidator.matches(ME, from)) { "acked own heartbeat: '$text'" }
            check(CallsignValidator.isAmateurCallsign(from)) { "acked a sender that isn't a callsign: '$text'" }
            check(ack.startsWith("$ME: ")) { "ack not from us: '$ack'" }
        }

        RelayProtocol.parseDirectedCommand(text)?.let { c ->
            AutoReply.directedReply(c, snr, me, heard)?.let { r ->
                check(!c.to.startsWith("@")) { "answered a group: '$text'" }
                check(CallsignValidator.matches(ME, c.to)) { "answered a frame for someone else: '$text'" }
                check(!CallsignValidator.matches(ME, c.from)) { "answered itself: '$text'" }
                check(CallsignValidator.isAmateurCallsign(c.from)) { "answered a sender that isn't a callsign: '$text'" }
                check(r == "LAST" || r.startsWith("$ME: ${c.from.trim().uppercase()} ")) { "reply not to the asker: '$r' for '$text'" }
                val hearingPrefix = "$ME: ${c.from.trim().uppercase()} HEARING "
                if (c.command.uppercase() == "HEARING?" && r.startsWith(hearingPrefix)) {
                    check(c.from.trim().uppercase() !in r.removePrefix(hearingPrefix).split(' ')) { "told the asker it hears the asker: '$r'" }
                }
            }
        }

        AutoReply.relayedReply(text, relayPath, snr, me, heard)?.let { r ->
            check(relayPath.isNotBlank()) { "relayed reply with no path" }
            check(r == "LAST" || r.startsWith("$relayPath ")) { "relayed reply off the path: '$r'" }
        }
    }
}
