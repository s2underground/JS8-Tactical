package com.js8call.example.ui.traffic

import kotlin.math.exp

/**
 * Who could carry a message to a station we can't reach, from what we've already heard.
 *
 * Evidence, all from ordinary JS8 traffic on the band we're on:
 *  - [Leg.Kind.HEARD_US]: the station reported hearing us (SNR reports, heartbeat replies).
 *  - [Leg.Kind.WE_HEAR]: we hear the station (the path back is likely, not proven).
 *  - [Leg.Kind.REPORTED]: one of two other stations reported the other's SNR.
 *  - [Leg.Kind.WORKED]: two other stations were heard working each other, both ways or in a
 *    HEARING list, with no SNR.
 *  - [Leg.Kind.CALLED]: one called the other; no sign the call got through.
 *
 * A relay R is scored by its weaker leg (us↔R, R↔target), each leg by SNR, kind and age.
 * Everything here is advice: the user picks, and can ignore it.
 */
object RelayFinder {

    data class Leg(val kind: Kind, val snr: Int?, val ageMs: Long, val reporter: String? = null) {
        enum class Kind(val trust: Double) { HEARD_US(1.0), REPORTED(0.95), WORKED(0.6), WE_HEAR(0.7), CALLED(0.2) }

        /** 0..1: how likely this leg carries a message now. */
        val score: Double
            get() {
                val snrPart = snr?.let { ((it + 24).toDouble() / 24.0).coerceIn(0.05, 1.0) } ?: 0.5
                return kind.trust * snrPart * exp(-ageMs / FRESH_MS)
            }
    }

    data class Option(val relay: String, val toRelay: Leg, val relayToTarget: Leg) {
        val score: Double get() = minOf(toRelay.score, relayToTarget.score)
    }

    data class Plan(
        val target: String,
        /** The target reported hearing us. */
        val targetHeardUs: Leg?,
        /** We hear the target. */
        val weHearTarget: Leg?,
        val options: List<Option>,
    )

    /** One station, as seen from here. */
    data class Station(val call: String, val weHearSnr: Int?, val weHeardMs: Long?, val heardUsSnr: Int?, val heardUsMs: Long?)

    /** A link between two other stations ([MapMemory.Link] in plain terms). */
    data class Link(
        val a: String, val b: String, val lastHeardMs: Long, val snr: Int?, val reporter: String?,
        val bothWays: Boolean, val hearing: Boolean,
    )

    /** Ignore evidence older than this. */
    const val MAX_AGE_MS = 6 * 3_600_000L
    /** Evidence loses about two thirds of its weight in this time. */
    private const val FRESH_MS = 90 * 60_000.0

    fun plan(target: String, stations: Map<String, Station>, links: List<Link>, nowMs: Long, maxOptions: Int = 6): Plan {
        val t = target.uppercase()
        val tgt = stations[t]
        val targetHeardUs = tgt?.heardUsMs?.takeIf { nowMs - it <= MAX_AGE_MS }
            ?.let { Leg(Leg.Kind.HEARD_US, tgt.heardUsSnr, nowMs - it) }
        val weHearTarget = tgt?.weHeardMs?.takeIf { nowMs - it <= MAX_AGE_MS }
            ?.let { Leg(Leg.Kind.WE_HEAR, tgt.weHearSnr, nowMs - it) }

        val options = links.asSequence()
            .filter { (it.a == t || it.b == t) && nowMs - it.lastHeardMs <= MAX_AGE_MS }
            .mapNotNull { link ->
                val relay = if (link.a == t) link.b else link.a
                val st = stations[relay] ?: return@mapNotNull null
                val toRelay = legToStation(st, nowMs) ?: return@mapNotNull null
                val kind = when {
                    link.snr != null -> Leg.Kind.REPORTED
                    link.bothWays || link.hearing -> Leg.Kind.WORKED
                    else -> Leg.Kind.CALLED
                }
                Option(relay, toRelay, Leg(kind, link.snr, nowMs - link.lastHeardMs, link.reporter))
            }
            .sortedByDescending { it.score }
            .take(maxOptions)
            .toList()
        return Plan(t, targetHeardUs, weHearTarget, options)
    }

    /** Our leg to [st]: its report of hearing us beats our hearing it. */
    private fun legToStation(st: Station, nowMs: Long): Leg? {
        val heardUs = st.heardUsMs?.takeIf { nowMs - it <= MAX_AGE_MS }?.let { Leg(Leg.Kind.HEARD_US, st.heardUsSnr, nowMs - it) }
        val weHear = st.weHeardMs?.takeIf { nowMs - it <= MAX_AGE_MS }?.let { Leg(Leg.Kind.WE_HEAR, st.weHearSnr, nowMs - it) }
        return listOfNotNull(heardUs, weHear).maxByOrNull { it.score }
    }

    /** JS8 relay text: sent to [relay], who passes it on to [target]. */
    fun relayText(relay: String, target: String, message: String): String =
        "${relay.uppercase()}>${target.uppercase()}>${message.trim().uppercase()}"
}
