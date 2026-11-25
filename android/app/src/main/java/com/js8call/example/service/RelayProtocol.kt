package com.js8call.example.service

import com.js8call.example.util.CallsignValidator
import com.js8call.example.util.Js8Commands

/** A directed frame as heard: "FROM: TO COMMAND payload" (or "TO> payload" for a relay). */
data class DirectedCommand(
    val from: String,
    val to: String,
    val command: String,
    val payload: String
)

/**
 * Reading the directed and relay frames heard over the air: plain Kotlin with no Android classes,
 * so it can be unit-tested and fuzzed (tools/fuzz/kotlin/RelayProtocolFuzz.kt). Everything here
 * takes text from the radio, from anyone, and must never throw.
 *
 * Relay frames follow JS8Call: "FROM: ME> payload CHK" where CHK is the 3-character checksum
 * of the payload (CRC-16/KERMIT packed in base 41), the payload may end with the
 * end-of-message marker, and a forwarded message carries "*DE* SENDER" for each hop it took.
 */
object RelayProtocol {

    /** JS8Call's end-of-message marker (a white diamond). */
    const val EOM_MARKER = "♢"

    /** The alphabet and base JS8Call packs checksums in. */
    const val CHECKSUM_ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ+-./?"
    const val CHECKSUM_BASE = 41

    private val whitespace = Regex("\\s+")
    private val heartbeatRegex = Regex("^\\s*([^:]+):\\s+@HB\\s+HEARTBEAT\\b", RegexOption.IGNORE_CASE)
    private val relayTargetRegex = Regex("^\\s*([A-Z0-9/]+)([> ])", RegexOption.IGNORE_CASE)
    private val relayPathRegex = Regex("\\s(?:\\*DE\\*|VIA)\\s([A-Z0-9/]+)", RegexOption.IGNORE_CASE)

    /** The sender of a heartbeat frame ("CALL: @HB HEARTBEAT …"), or null when it isn't one. */
    fun heartbeatSender(text: String): String? {
        val match = heartbeatRegex.find(text) ?: return null
        return match.groupValues.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
    }

    /**
     * Splits a directed frame into sender, target, command and payload. Multi-word commands
     * ("HEARTBEAT SNR", "QUERY MSGS") are matched against the JS8 command table; unknown text
     * keeps the single-token shape so free text reaches callers unchanged. A frame without a
     * sender is only accepted for a relay ("TO> payload").
     */
    fun parseDirectedCommand(text: String): DirectedCommand? {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return null

        val tokens = trimmed.split(whitespace)
        if (tokens.size < 2) return null

        var index = 0
        var from = ""
        var toToken = tokens[index]

        if (toToken.endsWith(":")) {
            from = toToken.trimEnd(':')
            index++
            if (index >= tokens.size) return null
            toToken = tokens[index]
        }

        var to = toToken
        val command: String
        val payload: String

        if (toToken.endsWith(">")) {
            to = toToken.trimEnd('>')
            command = ">"
            payload = tokens.drop(index + 1).joinToString(" ")
        } else {
            if (index + 1 >= tokens.size) return null
            val remainder = tokens.drop(index + 1).joinToString(" ")
            val match = Js8Commands.matchAt(remainder)
            if (match != null) {
                command = match.command
                payload = match.payload
            } else {
                command = tokens[index + 1]
                payload = tokens.drop(index + 2).joinToString(" ")
            }
        }

        if (to.isBlank() || command.isBlank()) return null
        if (from.isBlank() && command != ">") return null
        return DirectedCommand(from, to, command, payload)
    }

    /**
     * When a relayed payload is itself addressed onward ("NEXT> text" or "NEXT text"), the
     * payload to forward, always in the "NEXT> text" form. Null when it isn't addressed to a
     * single station: a group (@…) or anything that isn't a callsign is never forwarded.
     */
    fun forwardPayload(message: String): String? {
        val trimmed = message.trimStart()
        val match = relayTargetRegex.find(trimmed) ?: return null
        val target = match.groupValues[1].trim().uppercase()
        if (!CallsignValidator.isAmateurCallsign(target) || target.contains("@")) return null

        if (match.groupValues[2] == ">") return trimmed

        val replaceIndex = match.groupValues[1].length
        if (replaceIndex >= trimmed.length) return null
        return StringBuilder(trimmed).apply { setCharAt(replaceIndex, '>') }.toString()
    }

    /**
     * The route back to whoever started a relayed message: the station that relayed it to us
     * first, then each "*DE* CALL" (or "VIA CALL") hop from the most recent to the original
     * sender. Joined with ">" this addresses a reply along the same path.
     */
    fun pathCallsigns(from: String, text: String): List<String> {
        val calls = mutableListOf<String>()
        for (match in relayPathRegex.findAll(text.uppercase())) {
            val call = match.groupValues.getOrNull(1)?.trim().orEmpty()
            if (call.isNotEmpty()) calls.add(0, call)
        }
        val base = from.trim().uppercase()
        if (base.isNotEmpty()) calls.add(0, base)
        return calls
    }

    /** Whether "body CHK" carries a valid checksum, and the body without it. */
    fun validateChecksum(message: String): Pair<Boolean, String> {
        val trimmed = message.trimStart()
        if (trimmed.length < 4) return false to trimmed
        val checksum = trimmed.takeLast(3).uppercase()
        val body = trimmed.dropLast(4)
        return checksum16Valid(checksum, body) to body
    }

    /** The payload without a trailing end-of-message marker, and whether it had one. */
    fun normalizePayload(payload: String): Pair<String, Boolean> {
        var trimmed = payload.trimEnd()
        val hasEom = trimmed.endsWith(EOM_MARKER)
        if (hasEom) trimmed = trimmed.dropLast(EOM_MARKER.length).trimEnd()
        return trimmed to hasEom
    }

    fun stripEom(payload: String): String = normalizePayload(payload).first

    /** Drops a trailing " CHK" token that looks like a checksum, valid or not. */
    fun stripOptionalChecksum(message: String): String {
        val trimmed = message.trimEnd()
        val lastSpace = trimmed.lastIndexOf(' ')
        if (lastSpace <= 0 || trimmed.length - lastSpace != 4) return trimmed
        val checksum = trimmed.substring(lastSpace + 1)
        val isChecksumToken = checksum.all { CHECKSUM_ALPHABET.contains(it.uppercaseChar()) }
        return if (isChecksumToken) trimmed.substring(0, lastSpace) else trimmed
    }

    /** JS8Call's 16-bit checksum of [input], as the 3 characters sent on the air. */
    // ISO-8859-1, not US-ASCII: the JS8 dictionary has single Latin-1 characters (¡ ¿ À …), and the
    // sender's checksum covers those bytes. US-ASCII turned them into "?" and failed intact messages.
    fun checksum16(input: String): String = pack16Bits(crc16Kermit(input.toByteArray(Charsets.ISO_8859_1)))

    fun checksum16Valid(checksum: String, input: String): Boolean = checksum16(input) == checksum

    /** CRC-16/KERMIT (reflected 0x1021, initial value 0, no final XOR). */
    fun crc16Kermit(data: ByteArray): Int {
        var crc = 0x0000
        for (byte in data) {
            var cur = byte.toInt() and 0xFF
            repeat(8) {
                val mix = (crc xor cur) and 0x01
                crc = crc ushr 1
                if (mix != 0) crc = crc xor 0x8408
                cur = cur ushr 1
            }
        }
        return crc and 0xFFFF
    }

    /** A 16-bit value as three base-41 characters. */
    fun pack16Bits(value: Int): String {
        val v = value and 0xFFFF
        val base = CHECKSUM_BASE
        val tmp1 = v / (base * base)
        val tmp2 = (v - tmp1 * base * base) / base
        val tmp3 = v % base
        return "${CHECKSUM_ALPHABET[tmp1]}${CHECKSUM_ALPHABET[tmp2]}${CHECKSUM_ALPHABET[tmp3]}"
    }
}
