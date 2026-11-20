package com.js8call.example.ui.map

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.PointF
import android.graphics.RectF
import android.location.LocationManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.preference.PreferenceManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.navigation.NavigationBarView
import com.js8call.example.R
import com.js8call.example.reports.Geo
import com.js8call.example.reports.LatLon
import com.js8call.example.reports.Reports
import com.js8call.example.ui.traffic.TrafficViewModel
import com.js8call.example.ui.traffic.ago
import com.js8call.example.ui.traffic.zuluTime
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Map tab: heard stations at their grid squares, received reports at their
 * reported locations (Spot-Map-style points, coloured by type and status),
 * great-circle paths from you to each station coloured by signal, and dashed
 * links between other stations heard working each other ([MapMemory] keeps
 * grids and links across restarts).
 *
 * The style (assets/map/style.json) always draws a bundled offline basemap;
 * when online, OpenFreeMap vector tiles are drawn on top and cached, so areas
 * viewed once stay detailed offline.
 */
class MapFragment : Fragment() {

    private var mapView: MapView? = null
    private var map: MapLibreMap? = null
    private var style: Style? = null
    private lateinit var traffic: TrafficViewModel
    private var data: TrafficViewModel.MapData? = null
    private var windowMs: Long? = 6 * HOUR
    private var fitted = false
    private var selected: Selection? = null

    private sealed class Selection {
        data class Station(val s: TrafficViewModel.MapStation) : Selection()
        data class ReportPoint(val r: TrafficViewModel.MapReport) : Selection()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        MapLibre.getInstance(requireContext().applicationContext)
        return inflater.inflate(R.layout.fragment_map, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        traffic = TrafficViewModel.of(requireContext())
        // Keep areas viewed online available offline.
        OfflineManager.getInstance(requireContext()).setMaximumAmbientCacheSize(AMBIENT_CACHE_BYTES, null)

        val mv: MapView = view.findViewById(R.id.map_view)
        mapView = mv
        mv.onCreate(savedInstanceState)
        mv.getMapAsync { m ->
            map = m
            m.uiSettings.isRotateGesturesEnabled = false
            m.uiSettings.isTiltGesturesEnabled = false
            m.uiSettings.isLogoEnabled = false
            m.uiSettings.isAttributionEnabled = false
            m.uiSettings.isCompassEnabled = false
            m.setStyle(Style.Builder().fromUri("asset://map/style.json")) { st ->
                style = st
                addOverlay(st)
                render()
            }
            m.addOnMapClickListener { latLng -> onTap(m, latLng) }
        }

        traffic.mapData.observe(viewLifecycleOwner) {
            data = it
            render()
        }

        fun toggle(chip: Int, vararg layers: String) {
            view.findViewById<Chip>(chip).setOnCheckedChangeListener { _, on ->
                layers.forEach { id -> style?.getLayer(id)?.setProperties(PropertyFactory.visibility(if (on) Property.VISIBLE else Property.NONE)) }
                updateLegend()
                if (chip == R.id.map_show_links) render()
            }
        }
        toggle(R.id.map_show_stations, L_STATIONS, L_STATION_LABELS)
        toggle(R.id.map_show_reports, L_REPORTS, L_REPORT_LABELS)
        toggle(R.id.map_show_paths, L_PATHS)
        toggle(R.id.map_show_links, L_LINKS, L_LINK_LABELS)

        view.findViewById<ChipGroup>(R.id.map_window).setOnCheckedStateChangeListener { _, ids ->
            windowMs = when (ids.firstOrNull()) {
                R.id.map_window_1h -> HOUR
                R.id.map_window_6h -> 6 * HOUR
                R.id.map_window_24h -> 24 * HOUR
                else -> null
            }
            render()
        }
        view.findViewById<MaterialButton>(R.id.map_fit).setOnClickListener { fitAll(animate = true) }
        view.findViewById<View>(R.id.map_card_close).setOnClickListener { showCard(null) }
        view.findViewById<MaterialButton>(R.id.map_card_open).setOnClickListener { openThread() }
    }

    private fun updateLegend() {
        val v = view ?: return
        val paths = v.findViewById<Chip>(R.id.map_show_paths).isChecked
        val links = v.findViewById<Chip>(R.id.map_show_links).isChecked
        v.findViewById<View>(R.id.map_legend).isVisible = paths || links
        v.findViewById<View>(R.id.map_legend_links).isVisible = links
    }

    // ------------------------------------------------------------- layers

    private fun addOverlay(st: Style) {
        listOf(S_LINKS, S_PATHS, S_STATIONS, S_REPORTS, S_ME).forEach { st.addSource(GeoJsonSource(it)) }
        val color = Expression.toColor(Expression.get("color"))
        val halo = "#0A0D11"
        // Other stations working each other: dashed, under our own paths.
        st.addLayer(LineLayer(L_LINKS, S_LINKS).withProperties(
            PropertyFactory.lineColor(color),
            PropertyFactory.lineWidth(1.4f),
            PropertyFactory.lineOpacity(Expression.toNumber(Expression.get("opacity"))),
            PropertyFactory.lineDasharray(arrayOf(3f, 2f)),
        ))
        st.addLayer(SymbolLayer(L_LINK_LABELS, S_LINKS).withProperties(
            PropertyFactory.symbolPlacement(Property.SYMBOL_PLACEMENT_LINE_CENTER),
            PropertyFactory.textField(Expression.get("label")),
            PropertyFactory.textFont(arrayOf("NotoSansBold")),
            PropertyFactory.textSize(10.5f),
            PropertyFactory.textColor(color),
            PropertyFactory.textHaloColor(halo),
            PropertyFactory.textHaloWidth(1.5f),
            PropertyFactory.textAllowOverlap(false),
        ))
        st.addLayer(LineLayer(L_PATHS, S_PATHS).withProperties(
            PropertyFactory.lineColor(color),
            PropertyFactory.lineWidth(1.6f),
            PropertyFactory.lineOpacity(0.75f),
        ))
        st.addLayer(CircleLayer(L_STATIONS, S_STATIONS).withProperties(
            PropertyFactory.circleRadius(6f),
            PropertyFactory.circleColor(color),
            PropertyFactory.circleStrokeColor(halo),
            PropertyFactory.circleStrokeWidth(1.5f),
        ))
        st.addLayer(SymbolLayer(L_STATION_LABELS, S_STATIONS).withProperties(
            PropertyFactory.textField(Expression.get("label")),
            PropertyFactory.textFont(arrayOf("NotoSansBold")),
            PropertyFactory.textSize(12f),
            PropertyFactory.textColor(color),
            PropertyFactory.textHaloColor(halo),
            PropertyFactory.textHaloWidth(1.5f),
            PropertyFactory.textAnchor(Property.TEXT_ANCHOR_LEFT),
            PropertyFactory.textOffset(arrayOf(0.9f, 0f)),
            PropertyFactory.textAllowOverlap(false),
        ))
        st.addLayer(CircleLayer(L_REPORTS, S_REPORTS).withProperties(
            PropertyFactory.circleRadius(9f),
            PropertyFactory.circleColor(color),
            PropertyFactory.circleStrokeColor("#E6EAF0"),
            PropertyFactory.circleStrokeWidth(2f),
        ))
        st.addLayer(SymbolLayer(L_REPORT_LABELS, S_REPORTS).withProperties(
            PropertyFactory.textField(Expression.get("label")),
            PropertyFactory.textFont(arrayOf("NotoSansBold")),
            PropertyFactory.textSize(11f),
            PropertyFactory.textColor("#E6EAF0"),
            PropertyFactory.textHaloColor(halo),
            PropertyFactory.textHaloWidth(1.5f),
            PropertyFactory.textAnchor(Property.TEXT_ANCHOR_TOP),
            PropertyFactory.textOffset(arrayOf(0f, 1.1f)),
        ))
        st.addLayer(CircleLayer(L_ME, S_ME).withProperties(
            PropertyFactory.circleRadius(8f),
            PropertyFactory.circleColor("#F2A33A"),
            PropertyFactory.circleStrokeColor("#E6EAF0"),
            PropertyFactory.circleStrokeWidth(2.5f),
        ))
        st.addLayer(SymbolLayer(L_ME_LABEL, S_ME).withProperties(
            PropertyFactory.textField("YOU"),
            PropertyFactory.textFont(arrayOf("NotoSansBold")),
            PropertyFactory.textSize(11f),
            PropertyFactory.textColor("#F2A33A"),
            PropertyFactory.textHaloColor(halo),
            PropertyFactory.textHaloWidth(1.5f),
            PropertyFactory.textAnchor(Property.TEXT_ANCHOR_TOP),
            PropertyFactory.textOffset(arrayOf(0f, 1.1f)),
        ))
    }

    private fun render() {
        val st = style ?: return
        val d = data ?: return
        val now = System.currentTimeMillis()
        val since = windowMs?.let { now - it } ?: 0L
        val links = d.links.filter { it.lastHeardMs >= since }
        val linkEnds = if (showLinks()) links.flatMap { listOf(it.a, it.b) }.toSet() else emptySet()
        // A link's far end stays on the map even if that station was last heard before the window.
        val stations = d.stations.filter { it.lastHeardMs >= since || it.call in linkEnds }
        val reports = d.reports.filter { it.timeMs >= since }
        val me = myPosition()

        (st.getSource(S_STATIONS) as? GeoJsonSource)?.setGeoJson(FeatureCollection.fromFeatures(stations.map { s ->
            point(s.position).apply {
                addStringProperty("id", "s:${s.call}")
                addStringProperty("label", s.call)
                addStringProperty("color", hex(s.color))
            }
        }))
        (st.getSource(S_REPORTS) as? GeoJsonSource)?.setGeoJson(FeatureCollection.fromFeatures(reports.map { r ->
            point(r.position).apply {
                addStringProperty("id", "r:${r.id}")
                addStringProperty("label", listOfNotNull(r.report.type.tag, r.sender).joinToString(" · "))
                addStringProperty("color", reportColor(r.report))
            }
        }))
        (st.getSource(S_PATHS) as? GeoJsonSource)?.setGeoJson(FeatureCollection.fromFeatures(
            if (me == null) emptyList() else stations.map { s ->
                Feature.fromGeometry(LineString.fromLngLats(greatCircle(me, s.position))).apply {
                    addStringProperty("color", snrColor(s.snr))
                }
            }
        ))
        (st.getSource(S_LINKS) as? GeoJsonSource)?.setGeoJson(FeatureCollection.fromFeatures(links.map { l ->
            Feature.fromGeometry(LineString.fromLngLats(greatCircle(l.aPos, l.bPos))).apply {
                addStringProperty("color", l.snr?.let { snrColor(it) } ?: "#9AA4B2")
                addNumberProperty("opacity", if (l.confirmed) 0.8f else 0.45f)
                addStringProperty("label", l.snr?.let { snrText(it) } ?: "")
            }
        }))
        (st.getSource(S_ME) as? GeoJsonSource)?.setGeoJson(
            FeatureCollection.fromFeatures(listOfNotNull(me?.let { point(it) }))
        )

        if (!fitted) {
            fitted = true
            fitAll(animate = false)
        }
        // Keep an open card in sync with fresh data.
        selected?.let { sel ->
            when (sel) {
                is Selection.Station -> stations.firstOrNull { it.call == sel.s.call }?.let { showCard(Selection.Station(it)) }
                is Selection.ReportPoint -> Unit
            }
        }
    }

    private fun fitAll(animate: Boolean) {
        val m = map ?: return
        val d = data
        val now = System.currentTimeMillis()
        val since = windowMs?.let { now - it } ?: 0L
        val points = buildList {
            myPosition()?.let(::add)
            d?.stations?.filter { it.lastHeardMs >= since }?.forEach { add(it.position) }
            if (showLinks()) d?.links?.filter { it.lastHeardMs >= since }?.forEach { add(it.aPos); add(it.bPos) }
            d?.reports?.filter { it.timeMs >= since }?.forEach { add(it.position) }
        }
        val update = when {
            points.isEmpty() -> CameraUpdateFactory.newLatLngZoom(LatLng(39.5, -98.35), 3.0)
            points.size == 1 -> CameraUpdateFactory.newLatLngZoom(LatLng(points[0].lat, points[0].lon), 7.0)
            else -> {
                val b = LatLngBounds.Builder()
                points.forEach { b.include(LatLng(it.lat, it.lon)) }
                CameraUpdateFactory.newLatLngBounds(b.build(), dp(80))
            }
        }
        if (animate) m.easeCamera(update, 600) else m.moveCamera(update)
    }

    // ------------------------------------------------------------- tap / card

    private fun onTap(m: MapLibreMap, latLng: LatLng): Boolean {
        val p: PointF = m.projection.toScreenLocation(latLng)
        val r = dp(22).toFloat()
        val hits = m.queryRenderedFeatures(RectF(p.x - r, p.y - r, p.x + r, p.y + r), L_REPORTS, L_STATIONS)
        val id = hits.firstOrNull()?.getStringProperty("id")
        val d = data
        val sel = when {
            id == null || d == null -> null
            id.startsWith("r:") -> d.reports.firstOrNull { "r:${it.id}" == id }?.let { Selection.ReportPoint(it) }
            id.startsWith("s:") -> d.stations.firstOrNull { "s:${it.call}" == id }?.let { Selection.Station(it) }
            else -> null
        }
        showCard(sel)
        return sel != null
    }

    private fun showCard(sel: Selection?) {
        selected = sel
        val v = view ?: return
        val card = v.findViewById<View>(R.id.map_card)
        card.isVisible = sel != null
        if (sel == null) return
        val badge = v.findViewById<TextView>(R.id.map_card_badge)
        val title = v.findViewById<TextView>(R.id.map_card_title)
        val body = v.findViewById<TextView>(R.id.map_card_body)
        val meta = v.findViewById<TextView>(R.id.map_card_meta)
        val open = v.findViewById<MaterialButton>(R.id.map_card_open)
        val me = myPosition()
        when (sel) {
            is Selection.Station -> {
                val s = sel.s
                badge.text = getString(R.string.map_badge_station)
                title.text = s.call
                title.setTextColor(s.color)
                val heard = ago(s.lastHeardMs).let { if (it == "now") getString(R.string.map_just_now) else getString(R.string.map_ago, it) }
                val worked = data?.links.orEmpty().filter { it.a == s.call || it.b == s.call }
                    .sortedByDescending { it.lastHeardMs }
                val workedText = worked.take(4).joinToString("\n") { l ->
                    val other = if (l.a == s.call) l.b else l.a
                    val snr = l.snr
                    when {
                        snr == null && l.confirmed -> getString(R.string.map_worked_heard, other)
                        snr == null -> getString(R.string.map_worked_called, other)
                        l.reporter == s.call -> getString(R.string.map_worked_snr, other, s.call, other, snrText(snr))
                        else -> getString(R.string.map_worked_snr, other, other, s.call, snrText(snr))
                    }
                } + if (worked.size > 4) "\n" + getString(R.string.map_worked_more, worked.size - 4) else ""
                body.text = getString(R.string.map_station_body, heard, s.snr) +
                    if (workedText.isNotEmpty()) "\n" + workedText else ""
                meta.text = listOfNotNull(s.grid, me?.let { rangeText(it, s.position) }).joinToString(" · ")
                open.isVisible = true
            }
            is Selection.ReportPoint -> {
                val r = sel.r
                badge.text = r.report.type.tag
                title.text = r.sender ?: getString(R.string.map_unknown_sender)
                title.setTextColor(r.color)
                body.text = r.report.type.fields
                    .filter { it.kind != Reports.Kind.LOCATION && it.kind != Reports.Kind.TIME }
                    .mapNotNull { f -> r.report.values[f.key]?.let { "${f.label}: $it" } }
                    .joinToString("\n")
                val loc = r.report.type.locationField?.let { r.report.values[it.key] }
                meta.text = listOfNotNull(loc, me?.let { rangeText(it, r.position) }, zuluTime(r.timeMs)).joinToString(" · ")
                open.isVisible = r.sender != null
            }
        }
    }

    private fun openThread() {
        val key = when (val s = selected) {
            is Selection.Station -> s.s.call
            is Selection.ReportPoint -> s.r.sender
            null -> null
        } ?: return
        traffic.select(key)
        requireActivity().findViewById<NavigationBarView>(R.id.bottom_navigation)?.selectedItemId = R.id.navigation_traffic
    }

    // ------------------------------------------------------------- helpers

    private fun myPosition(): LatLon? = context?.let { com.js8call.example.ui.map.myPosition(it) }

    private fun rangeText(from: LatLon, to: LatLon): String {
        val km = Geo.distanceKm(from, to)
        val dirs = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        val dir = dirs[(((Geo.bearingDeg(from, to) + 22.5) % 360) / 45).toInt()]
        return if (km < 10) String.format("%.1f km %s", km, dir) else "${km.toInt()} km $dir"
    }

    private fun point(p: LatLon) = Feature.fromGeometry(Point.fromLngLat(p.lon, p.lat))

    /** Points along the great circle from [a] to [b], longitudes unwrapped so the line never jumps the antimeridian. */
    private fun greatCircle(a: LatLon, b: LatLon, steps: Int = 48): List<Point> {
        val lat1 = Math.toRadians(a.lat); val lon1 = Math.toRadians(a.lon)
        val lat2 = Math.toRadians(b.lat); val lon2 = Math.toRadians(b.lon)
        val d = 2 * asin(sqrt(sin((lat2 - lat1) / 2).let { it * it } + cos(lat1) * cos(lat2) * sin((lon2 - lon1) / 2).let { it * it }))
        if (d < 1e-6) return listOf(Point.fromLngLat(a.lon, a.lat), Point.fromLngLat(b.lon, b.lat))
        val out = ArrayList<Point>(steps + 1)
        var prevLon = a.lon
        for (i in 0..steps) {
            val f = i.toDouble() / steps
            val x1 = sin((1 - f) * d) / sin(d)
            val x2 = sin(f * d) / sin(d)
            val x = x1 * cos(lat1) * cos(lon1) + x2 * cos(lat2) * cos(lon2)
            val y = x1 * cos(lat1) * sin(lon1) + x2 * cos(lat2) * sin(lon2)
            val z = x1 * sin(lat1) + x2 * sin(lat2)
            var lon = Math.toDegrees(atan2(y, x))
            while (lon - prevLon > 180) lon -= 360
            while (lon - prevLon < -180) lon += 360
            prevLon = lon
            out += Point.fromLngLat(lon, Math.toDegrees(atan2(z, sqrt(x * x + y * y))))
        }
        return out
    }

    private fun showLinks() = view?.findViewById<Chip>(R.id.map_show_links)?.isChecked ?: true

    private fun snrText(snr: Int) = if (snr >= 0) "+$snr dB" else "\u2212${-snr} dB"

    private fun hex(color: Int) = String.format("#%06X", color and 0xFFFFFF)

    private fun snrColor(snr: Int) = when {
        snr >= -10 -> "#4FC3F7"
        snr >= -18 -> "#F2A33A"
        else -> "#FF6B6B"
    }

    /** Spot-Map-style colour: by status where the report has one, else by type. */
    private fun reportColor(r: Reports.Report): String {
        val status = r.values["S"]?.uppercase()
        return when {
            r.type == Reports.CHECKIN || r.type == Reports.SITREP -> when (status) {
                "GOOD", "GREEN" -> "#4CC38A"
                "NEED", "AMBER" -> "#F2A33A"
                "EMERG", "RED" -> "#FF6B6B"
                else -> "#9AA4B2"
            }
            r.type == Reports.POSREP -> "#4FC3F7"
            else -> "#FF8A3D" // SPOT, SALUTE
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ------------------------------------------------------------- lifecycle

    override fun onStart() { super.onStart(); mapView?.onStart() }
    override fun onResume() { super.onResume(); mapView?.onResume(); render() }
    override fun onPause() { mapView?.onPause(); super.onPause() }
    override fun onStop() { mapView?.onStop(); super.onStop() }
    override fun onLowMemory() { super.onLowMemory(); mapView?.onLowMemory() }
    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); mapView?.onSaveInstanceState(outState) }

    override fun onDestroyView() {
        mapView?.onDestroy()
        mapView = null
        map = null
        style = null
        fitted = false
        super.onDestroyView()
    }

    private companion object {
        const val HOUR = 3_600_000L
        const val AMBIENT_CACHE_BYTES = 300L * 1024 * 1024
        const val S_PATHS = "tac-paths"
        const val S_LINKS = "tac-links"
        const val L_LINKS = "tac-links-line"
        const val L_LINK_LABELS = "tac-links-label"
        const val S_STATIONS = "tac-stations"
        const val S_REPORTS = "tac-reports"
        const val S_ME = "tac-me"
        const val L_PATHS = "tac-paths-line"
        const val L_STATIONS = "tac-stations-circle"
        const val L_STATION_LABELS = "tac-stations-label"
        const val L_REPORTS = "tac-reports-circle"
        const val L_REPORT_LABELS = "tac-reports-label"
        const val L_ME = "tac-me-circle"
        const val L_ME_LABEL = "tac-me-label"
    }
}
