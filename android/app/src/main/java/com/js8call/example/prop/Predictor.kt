package com.js8call.example.prop

import com.js8call.example.reports.Geo
import com.js8call.example.reports.LatLon

/**
 * Everything the Prop tab predicts, in one place: the fitted sky model, this
 * station's profile (power, speed, antenna), what it has learned from its own
 * log, and the live tune-up from recent traffic. Works entirely offline.
 */
class Predictor(
    val me: LatLon?,
    val wx: SpaceWeather,
    val profile: StationProfile = StationProfile(),
    val correction: StationCorrection = StationCorrection.NONE,
    val nowcast: Nowcast = Nowcast(1.0, 0),
) {
    /** Chance (0..1, chart thresholds 0.10/0.33/0.66) that this station works [to] on [band]. */
    fun score(to: LatLon, sky: PropagationModel.Sky, band: Band, phi: Double = nowcast.phi): Double {
        val from = me ?: return 0.0
        val p = PropagationModel.path(from, to, sky, wx, phi)
        return adjust(PropagationModel.reliability(p, band.mhz), p, band)
    }

    fun adjust(score: Double, p: PropagationModel.Path, band: Band): Double {
        var s = score
        if (profile.adjust) s = StationModel.adjust(s, profile, p, band)
        if (profile.learn) s = correction.adjust(s, band, p.distanceKm)
        return s
    }

    /** Path between two other stations (no station profile or learning applied). */
    fun between(a: LatLon, b: LatLon, sky: PropagationModel.Sky, band: Band, phi: Double = nowcast.phi): Double =
        PropagationModel.reliability(PropagationModel.path(a, b, sky, wx, phi), band.mhz)

    fun path(to: LatLon, timeMs: Long): PropagationModel.Path? =
        me?.let { PropagationModel.path(it, to, PropagationModel.Sky(timeMs), wx, nowcast.phi) }

    /** Average over 12 bearings per distance bucket: the band chart row. */
    fun stoplight(band: Band, timeMs: Long, bucketsKm: List<Double> = PropagationModel.CHART_BUCKETS_KM): List<Double>? {
        val from = me ?: return null
        val sky = PropagationModel.Sky(timeMs)
        return bucketsKm.map { km ->
            (0 until 12).map { i -> score(PropagationModel.destination(from, i * 30.0, km), sky, band) }.average()
        }
    }

    fun coverage(band: Band, timeMs: Long, width: Int, height: Int, north: Double, south: Double, west: Double, east: Double): FloatArray? {
        if (me == null) return null
        val sky = PropagationModel.Sky(timeMs)
        return PropagationModel.grid(width, height, north, south, west, east) { p -> score(p, sky, band) }
    }

    companion object {
        /**
         * Learn from the logs and build a predictor. [gridFor] resolves a
         * callsign's last known grid. Cheap enough to run on every refresh.
         */
        fun build(
            me: LatLon?,
            wx: SpaceWeather,
            profile: StationProfile,
            obs: List<Observation>,
            listening: Map<Pair<Long, Band>, Double>,
            gridFor: (String) -> String?,
            nowMs: Long,
        ): Predictor {
            if (me == null) return Predictor(null, wx, profile)
            val skies = HashMap<Long, PropagationModel.Sky>()
            fun sky(t: Long) = skies.getOrPut(t) { PropagationModel.Sky(t) }
            val base = Predictor(me, wx, profile)
            val pairs = Learner.pairs(obs, listening, gridFor, me)
            val correction = if (profile.learn) {
                Learner.fit(pairs) { p -> base.score(p.position, sky(p.timeMs), p.band, 1.0) }
            } else StationCorrection.NONE
            val learned = Predictor(me, wx, profile, correction)

            // Live tune-up from the last three hours.
            val since = nowMs - 3 * ObservationStore.HOUR_MS
            val recent = pairs.filter { it.timeMs >= since }
            val net = obs.filter { it.kind == Observation.Kind.PATH && it.timeMs >= since && it.by != null }.mapNotNull { o ->
                val a = (gridFor(o.by!!))?.let { Geo.maidenheadToLatLon(it) } ?: return@mapNotNull null
                val b = (o.grid ?: gridFor(o.call))?.let { Geo.maidenheadToLatLon(it) } ?: return@mapNotNull null
                Triple(a to b, o.band, o.timeMs)
            }
            val nowcast = Learner.nowcast(
                own = recent,
                netPaths = net.map { it.first },
                netBands = net.map { it.second },
                netTimes = net.map { it.third },
                global = correction.global,
            ) { from, to, band, t, phi ->
                if (from == null) learned.score(to, sky(t), band, phi) else learned.between(from, to, sky(t), band, phi)
            }
            return Predictor(me, wx, profile, correction, nowcast)
        }
    }
}
