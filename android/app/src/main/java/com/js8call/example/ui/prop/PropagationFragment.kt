package com.js8call.example.ui.prop

import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.preference.PreferenceManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.js8call.example.R
import com.js8call.example.prop.Band
import com.js8call.example.prop.Observation
import com.js8call.example.prop.ObservationStore
import com.js8call.example.prop.PropagationModel
import com.js8call.example.prop.SpaceWeather
import com.js8call.example.prop.SpaceWeatherRepository
import com.js8call.example.reports.Geo
import com.js8call.example.reports.LatLon
import com.js8call.example.ui.map.myPosition
import com.js8call.example.ui.traffic.TrafficViewModel
import com.js8call.example.ui.traffic.zuluTime
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngQuad
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.HeatmapLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.ImageSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * Propagation tab: where your signal is likely to reach on a band at a given
 * hour (a simple ionospheric model fed by solar flux and K index), overlaid
 * with what was actually heard on that band. The heard layer needs no
 * internet: it is built from the traffic this station has decoded, and for
 * other hours of the day from what was heard at that hour on previous days.
 */
class PropagationFragment : Fragment() {

    private var mapView: MapView? = null
    private var map: MapLibreMap? = null
    private var style: Style? = null
    private lateinit var traffic: TrafficViewModel
    private lateinit var weather: SpaceWeatherRepository

    private var band: Band = Band.B40
    private var hoursAhead = 0f
    private var wx: SpaceWeather? = null
    private var me: LatLon? = null
    private var centered = false

    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var generation = 0

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        MapLibre.getInstance(requireContext().applicationContext)
        return inflater.inflate(R.layout.fragment_propagation, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        traffic = ViewModelProvider(requireActivity())[TrafficViewModel::class.java]
        weather = SpaceWeatherRepository(requireContext())
        wx = weather.current()
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        band = traffic.currentBand()
            ?: prefs.getString(PREF_BAND, null)?.let { runCatching { Band.valueOf(it) }.getOrNull() }
            ?: Band.B40

        val mv: MapView = view.findViewById(R.id.prop_map)
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
            m.addOnMapClickListener { latLng -> onTap(latLng); true }
        }

        buildBandChips(view)

        view.findViewById<Slider>(R.id.prop_slider).addOnChangeListener { _, value, _ ->
            hoursAhead = value
            render()
        }
        view.findViewById<Chip>(R.id.prop_show_predicted).setOnCheckedChangeListener { _, on ->
            setVisible(on, L_COVERAGE)
        }
        view.findViewById<Chip>(R.id.prop_show_heard).setOnCheckedChangeListener { _, on ->
            setVisible(on, L_HEARD_HEAT, L_HEARD_DOTS, L_HEARD_LABELS)
        }
        view.findViewById<Chip>(R.id.prop_solar).setOnClickListener { showSolarDialog() }

        val chartCard = view.findViewById<View>(R.id.prop_chart_card)
        chartCard.isVisible = prefs.getBoolean(PREF_CHART, resources.getBoolean(R.bool.prop_chart_open_default))
        view.findViewById<MaterialButton>(R.id.prop_chart_toggle).setOnClickListener {
            chartCard.isVisible = !chartCard.isVisible
            prefs.edit().putBoolean(PREF_CHART, chartCard.isVisible).apply()
        }
        view.findViewById<StoplightView>(R.id.prop_chart).onBandTapped = { selectBand(it) }

        traffic.observationsVersion.observe(viewLifecycleOwner) { render() }
    }

    private fun buildBandChips(view: View) {
        val group = view.findViewById<ChipGroup>(R.id.prop_bands)
        val onAir = traffic.currentBand()
        Band.entries.forEach { b ->
            val chip = layoutInflater.inflate(R.layout.item_prop_band_chip, group, false) as Chip
            chip.id = View.generateViewId()
            chip.text = if (b == onAir) "${b.label} •" else b.label
            chip.tag = b
            chip.isChecked = b == band
            group.addView(chip)
        }
        group.setOnCheckedStateChangeListener { g, ids ->
            val b = ids.firstOrNull()?.let { g.findViewById<Chip>(it)?.tag as? Band } ?: return@setOnCheckedStateChangeListener
            if (b != band) {
                band = b
                PreferenceManager.getDefaultSharedPreferences(requireContext()).edit().putString(PREF_BAND, b.name).apply()
                render()
            }
        }
    }

    private fun selectBand(b: Band) {
        val group = view?.findViewById<ChipGroup>(R.id.prop_bands) ?: return
        (0 until group.childCount).map { group.getChildAt(it) as Chip }.firstOrNull { it.tag == b }?.isChecked = true
    }

    // ------------------------------------------------------------- layers

    private fun addOverlay(st: Style) {
        val halo = "#0A0D11"
        st.addSource(ImageSource(S_COVERAGE, WORLD_QUAD, Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)))
        st.addLayer(RasterLayer(L_COVERAGE, S_COVERAGE).withProperties(
            PropertyFactory.rasterOpacity(0.85f),
            PropertyFactory.rasterResampling(Property.RASTER_RESAMPLING_LINEAR),
            PropertyFactory.rasterFadeDuration(0f),
        ))
        listOf(S_NIGHT, S_TERMINATOR, S_HEARD, S_ME).forEach { st.addSource(GeoJsonSource(it)) }
        st.addLayer(FillLayer(L_NIGHT, S_NIGHT).withProperties(
            PropertyFactory.fillColor("#000000"),
            PropertyFactory.fillOpacity(0.28f),
        ))
        st.addLayer(LineLayer(L_TERMINATOR, S_TERMINATOR).withProperties(
            PropertyFactory.lineColor("#C9D1DC"),
            PropertyFactory.lineWidth(1f),
            PropertyFactory.lineOpacity(0.45f),
            PropertyFactory.lineDasharray(arrayOf(3f, 3f)),
        ))
        st.addLayer(HeatmapLayer(L_HEARD_HEAT, S_HEARD).withProperties(
            PropertyFactory.heatmapWeight(Expression.get("w")),
            PropertyFactory.heatmapIntensity(Expression.interpolate(Expression.linear(), Expression.zoom(),
                Expression.stop(0, 0.8f), Expression.stop(6, 1.6f))),
            PropertyFactory.heatmapRadius(Expression.interpolate(Expression.linear(), Expression.zoom(),
                Expression.stop(0, 14f), Expression.stop(3, 26f), Expression.stop(7, 48f))),
            PropertyFactory.heatmapOpacity(0.85f),
            PropertyFactory.heatmapColor(Expression.interpolate(Expression.linear(), Expression.heatmapDensity(),
                Expression.stop(0, Expression.rgba(79, 195, 247, 0)),
                Expression.stop(0.25, Expression.rgba(79, 195, 247, 0.45)),
                Expression.stop(0.6, Expression.rgba(127, 211, 250, 0.75)),
                Expression.stop(1, Expression.rgba(230, 247, 255, 0.95)))),
        ))
        st.addLayer(CircleLayer(L_HEARD_DOTS, S_HEARD).withProperties(
            PropertyFactory.circleRadius(4f),
            PropertyFactory.circleColor(Expression.switchCase(Expression.get("me"), Expression.color(0xFFF5A3FF.toInt()), Expression.color(0xFF7FD3FA.toInt()))),
            PropertyFactory.circleStrokeColor(halo),
            PropertyFactory.circleStrokeWidth(1f),
        ))
        st.addLayer(SymbolLayer(L_HEARD_LABELS, S_HEARD).withProperties(
            PropertyFactory.textField(Expression.get("label")),
            PropertyFactory.textFont(arrayOf("NotoSansBold")),
            PropertyFactory.textSize(11f),
            PropertyFactory.textColor("#C9D1DC"),
            PropertyFactory.textHaloColor(halo),
            PropertyFactory.textHaloWidth(1.5f),
            PropertyFactory.textAnchor(Property.TEXT_ANCHOR_LEFT),
            PropertyFactory.textOffset(arrayOf(0.7f, 0f)),
            PropertyFactory.textOptional(true),
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

    private fun setVisible(on: Boolean, vararg layers: String) {
        layers.forEach { style?.getLayer(it)?.setProperties(PropertyFactory.visibility(if (on) Property.VISIBLE else Property.NONE)) }
    }

    // ------------------------------------------------------------- render

    private fun render() {
        val v = view ?: return
        val ctx = context ?: return
        val now = System.currentTimeMillis()
        val t = now + (hoursAhead * 3_600_000L).toLong()
        val wx = this.wx ?: weather.current().also { this.wx = it }
        me = myPosition(ctx)
        val me = this.me

        v.findViewById<TextView>(R.id.prop_time).text = if (hoursAhead == 0f) {
            getString(R.string.prop_now, zuluTime(t))
        } else {
            getString(R.string.prop_ahead, fmtHours(hoursAhead), zuluTime(t))
        }
        renderSolar(v, wx, me != null)

        // Things that are cheap: night side, my marker.
        val st = style
        if (st != null) {
            val line = PropagationModel.terminator(t)
            (st.getSource(S_TERMINATOR) as? GeoJsonSource)?.setGeoJson(
                Feature.fromGeometry(LineString.fromLngLats(line.map { Point.fromLngLat(it.lon, it.lat) })),
            )
            val nightPole = if (com.js8call.example.prop.Sun.subsolarPoint(t).lat >= 0) -MAX_LAT else MAX_LAT
            val ring = line.map { Point.fromLngLat(it.lon, it.lat.coerceIn(-MAX_LAT, MAX_LAT)) } +
                listOf(Point.fromLngLat(180.0, nightPole), Point.fromLngLat(-180.0, nightPole), Point.fromLngLat(line.first().lon, line.first().lat.coerceIn(-MAX_LAT, MAX_LAT)))
            (st.getSource(S_NIGHT) as? GeoJsonSource)?.setGeoJson(Feature.fromGeometry(Polygon.fromLngLats(listOf(ring))))
            (st.getSource(S_ME) as? GeoJsonSource)?.setGeoJson(
                FeatureCollection.fromFeatures(listOfNotNull(me?.let { Feature.fromGeometry(Point.fromLngLat(it.lon, it.lat)) })),
            )
            if (!centered && me != null) {
                centered = true
                map?.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(me.lat, me.lon), 2.2))
            }
        }

        // The rest goes to a background thread; only the newest request is drawn.
        val gen = ++generation
        val band = this.band
        val all = traffic.observationStore.all()
        val gridFor = HashMap<String, String?>().also { m -> all.forEach { o -> m[o.call] = traffic.gridFor(o.call) } }
        worker.execute {
            if (gen != generation) return@execute
            val heard = ObservationStore.relevant(all, band, t, now) { gridFor[it] }
                .groupBy { it.obs.call }
                .map { (_, list) -> list.maxBy { it.weight }.copy(weight = list.sumOf { it.weight }.coerceAtMost(1.5)) }
            val heardMe = heard.count { w -> all.any { it.call == w.obs.call && it.kind == Observation.Kind.HEARD_ME && it.band == band } }
            val rows = Band.entries.map { b ->
                val predicted = me?.let { PropagationModel.stoplight(it, b, t, wx, PropagationModel.CHART_BUCKETS_KM) }
                val counts = IntArray(PropagationModel.CHART_BUCKETS_KM.size)
                if (me != null) {
                    ObservationStore.relevant(all, b, t, now) { gridFor[it] }.map { it.obs.call to it.position }.distinctBy { it.first }
                        .forEach { (_, pos) ->
                            val km = Geo.distanceKm(me, pos)
                            counts[PropagationModel.CHART_EDGES_KM.indexOfFirst { km < it }]++
                        }
                }
                StoplightView.Row(b, predicted, counts.toList())
            }
            val bitmap = me?.let { coverageBitmap(it, band, t, wx) }
            if (gen != generation) return@execute
            main.post { if (gen == generation) deliver(heard, heardMe, rows, bitmap, t) }
        }
    }

    private fun deliver(
        heard: List<com.js8call.example.prop.WeightedObservation>,
        heardMe: Int,
        rows: List<StoplightView.Row>,
        bitmap: Bitmap?,
        t: Long,
    ) {
        val v = view ?: return
        val st = style
        if (st != null) {
            val meCalls = traffic.observationStore.all().filter { it.kind == Observation.Kind.HEARD_ME && it.band == band }.map { it.call }.toSet()
            (st.getSource(S_HEARD) as? GeoJsonSource)?.setGeoJson(FeatureCollection.fromFeatures(heard.map { w ->
                Feature.fromGeometry(Point.fromLngLat(w.position.lon, w.position.lat)).apply {
                    addNumberProperty("w", w.weight)
                    addStringProperty("label", w.obs.call)
                    addBooleanProperty("me", w.obs.call in meCalls)
                }
            }))
            (st.getSource(S_COVERAGE) as? ImageSource)?.setImage(bitmap ?: Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888))
        }
        v.findViewById<TextView>(R.id.prop_heard_count).text =
            if (heard.isEmpty()) getString(R.string.prop_heard_none)
            else getString(R.string.prop_heard_summary, heard.size, heardMe)
        v.findViewById<StoplightView>(R.id.prop_chart).apply {
            this.rows = rows
            selected = band
        }
        v.findViewById<TextView>(R.id.prop_chart_title).text = getString(R.string.prop_chart_title,
            if (hoursAhead == 0f) getString(R.string.prop_now, zuluTime(t)) else zuluTime(t))
    }

    private fun renderSolar(v: View, wx: SpaceWeather, haveMe: Boolean) {
        val age = ageText(wx.updatedMs)
        val src = when (wx.source) {
            SpaceWeatherRepository.SOURCE_NOAA -> getString(R.string.prop_src_noaa, age)
            SpaceWeatherRepository.SOURCE_MANUAL -> getString(R.string.prop_src_manual, age)
            SpaceWeatherRepository.SOURCE_FIT -> getString(R.string.prop_src_fit, age)
            else -> getString(R.string.prop_src_default)
        }
        v.findViewById<Chip>(R.id.prop_solar).text = getString(R.string.prop_solar_chip, wx.sfi.roundToInt(), fmtK(wx.kIndex), src)
        val notice = v.findViewById<TextView>(R.id.prop_notice)
        val stale = wx.updatedMs > 0 && System.currentTimeMillis() - wx.updatedMs > 36 * 3_600_000L
        notice.text = when {
            !haveMe -> getString(R.string.prop_no_position)
            wx.source == SpaceWeatherRepository.SOURCE_DEFAULT -> getString(R.string.prop_default_wx)
            stale -> getString(R.string.prop_stale, age)
            else -> null
        }
        notice.isVisible = notice.text.isNotEmpty()
    }

    /** Predicted reliability painted into a Web-Mercator bitmap covering the world. */
    private fun coverageBitmap(me: LatLon, band: Band, t: Long, wx: SpaceWeather): Bitmap {
        val grid = PropagationModel.coverageGrid(me, band, t, wx, GRID_W, GRID_H, MAX_LAT)
        val pixels = IntArray(grid.size) { colorFor(grid[it]) }
        return Bitmap.createBitmap(pixels, GRID_W, GRID_H, Bitmap.Config.ARGB_8888)
    }

    private fun onTap(latLng: LatLng) {
        val me = this.me ?: return
        val wx = this.wx ?: return
        val v = view ?: return
        val t = System.currentTimeMillis() + (hoursAhead * 3_600_000L).toLong()
        val to = LatLon(latLng.latitude, latLng.longitude)
        val path = PropagationModel.path(me, to, t, wx)
        val r = PropagationModel.reliability(path, band.mhz)
        val verdict = when {
            r >= 0.66 -> "likely"
            r >= 0.33 -> "possible"
            r >= 0.10 -> "marginal"
            else -> "unlikely"
        }
        val best = Band.entries.maxBy { PropagationModel.reliability(path, it.mhz) }
        val dirs = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        val dir = dirs[(((Geo.bearingDeg(me, to) + 22.5) % 360) / 45).toInt()]
        val text = String.format(
            Locale.US, "%,d km %s on %s: %s · MUF %.1f MHz · best band %s",
            path.distanceKm.roundToInt(), dir, band.label, verdict, path.mufMHz, best.label,
        )
        Snackbar.make(v, text, Snackbar.LENGTH_LONG).show()
    }

    // ------------------------------------------------------------- solar data

    private fun showSolarDialog() {
        val wx = weather.current()
        val src = when (wx.source) {
            SpaceWeatherRepository.SOURCE_NOAA -> getString(R.string.prop_src_noaa, ageText(wx.updatedMs))
            SpaceWeatherRepository.SOURCE_MANUAL -> getString(R.string.prop_src_manual, ageText(wx.updatedMs))
            SpaceWeatherRepository.SOURCE_FIT -> getString(R.string.prop_src_fit, ageText(wx.updatedMs))
            else -> getString(R.string.prop_src_default)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.prop_solar_title)
            .setMessage(getString(R.string.prop_solar_body, wx.sfi.roundToInt(), fmtK(wx.kIndex), src))
            .setPositiveButton(R.string.prop_solar_refresh) { _, _ -> refresh(announce = true) }
            .setNegativeButton(R.string.prop_solar_manual) { _, _ -> showManualDialog(wx) }
            .setNeutralButton(R.string.prop_solar_fit) { _, _ -> fitFromTraffic() }
            .show()
    }

    private fun showManualDialog(current: SpaceWeather) {
        val ctx = requireContext()
        fun field(hint: Int, value: String): Pair<TextInputLayout, TextInputEditText> {
            val layout = TextInputLayout(ctx).apply { this.hint = getString(hint) }
            val edit = TextInputEditText(layout.context).apply {
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                setText(value)
            }
            layout.addView(edit)
            return layout to edit
        }
        val (sfiLayout, sfiEdit) = field(R.string.prop_solar_sfi, current.sfi.roundToInt().toString())
        val (kLayout, kEdit) = field(R.string.prop_solar_k, fmtK(current.kIndex))
        val pad = (20 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(sfiLayout)
            addView(kLayout)
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.prop_solar_title)
            .setView(box)
            .setPositiveButton(R.string.prop_solar_save) { _, _ ->
                val sfi = sfiEdit.text?.toString()?.toDoubleOrNull()?.coerceIn(50.0, 400.0) ?: current.sfi
                val k = kEdit.text?.toString()?.toDoubleOrNull()?.coerceIn(0.0, 9.0) ?: current.kIndex
                this.wx = weather.setManual(sfi, k)
                render()
            }
            .setNegativeButton(R.string.prop_solar_cancel, null)
            .show()
    }

    private fun fitFromTraffic() {
        val v = view ?: return
        val me = this.me
        val since = System.currentTimeMillis() - 6 * 3_600_000L
        val evidence = traffic.observationStore.all().filter { it.timeMs >= since }.mapNotNull { o ->
            val grid = o.grid ?: traffic.gridFor(o.call) ?: return@mapNotNull null
            Geo.maidenheadToLatLon(grid)?.let { o to it }
        }
        val sfi = me?.let { ObservationStore.fitSfi(it, evidence, weather.current()) }
        if (sfi == null) {
            Snackbar.make(v, R.string.prop_fit_none, Snackbar.LENGTH_LONG).show()
            return
        }
        this.wx = weather.setEstimated(sfi)
        Snackbar.make(v, getString(R.string.prop_fit_done, sfi.roundToInt(), evidence.size), Snackbar.LENGTH_LONG).show()
        render()
    }

    private fun refresh(announce: Boolean) {
        weather.refresh { result ->
            main.post {
                if (!isAdded) return@post
                if (result != null) {
                    wx = result
                    render()
                }
                if (announce) view?.let { v ->
                    val msg = if (result != null) getString(R.string.prop_refresh_ok, result.sfi.roundToInt(), fmtK(result.kIndex))
                    else getString(R.string.prop_refresh_failed)
                    Snackbar.make(v, msg, Snackbar.LENGTH_LONG).show()
                }
            }
        }
    }

    // ------------------------------------------------------------- helpers

    private fun fmtHours(h: Float) = if (h % 1f == 0f) h.toInt().toString() else String.format(Locale.US, "%.1f", h)
    private fun fmtK(k: Double) = if (k % 1.0 == 0.0) k.toInt().toString() else String.format(Locale.US, "%.1f", k)

    private fun ageText(ms: Long): String {
        if (ms <= 0) return ""
        val min = (System.currentTimeMillis() - ms) / 60_000
        return when {
            min < 1 -> "now"
            min < 60 -> "${min}m"
            min < 48 * 60 -> "${min / 60}h"
            else -> "${min / (24 * 60)}d"
        }
    }

    // ------------------------------------------------------------- lifecycle

    override fun onStart() { super.onStart(); mapView?.onStart() }
    override fun onResume() {
        super.onResume()
        mapView?.onResume()
        // Quietly try NOAA when the numbers are more than three hours old.
        val age = System.currentTimeMillis() - weather.current().updatedMs
        if (age > 3 * 3_600_000L) refresh(announce = false)
        render()
    }
    override fun onPause() { mapView?.onPause(); super.onPause() }
    override fun onStop() { mapView?.onStop(); super.onStop() }
    override fun onLowMemory() { super.onLowMemory(); mapView?.onLowMemory() }
    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); mapView?.onSaveInstanceState(outState) }

    override fun onDestroyView() {
        generation++
        mapView?.onDestroy()
        mapView = null
        map = null
        style = null
        super.onDestroyView()
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    companion object {
        private const val PREF_BAND = "prop_band"
        private const val PREF_CHART = "prop_chart_open"
        private const val GRID_W = 240
        private const val GRID_H = 180
        private const val MAX_LAT = 85.0

        private val WORLD_QUAD = LatLngQuad(
            LatLng(MAX_LAT, -180.0), LatLng(MAX_LAT, 180.0),
            LatLng(-MAX_LAT, 180.0), LatLng(-MAX_LAT, -180.0),
        )

        private const val S_COVERAGE = "prop-coverage"
        private const val S_NIGHT = "prop-night"
        private const val S_TERMINATOR = "prop-terminator"
        private const val S_HEARD = "prop-heard"
        private const val S_ME = "prop-me"
        private const val L_COVERAGE = "prop-coverage"
        private const val L_NIGHT = "prop-night"
        private const val L_TERMINATOR = "prop-terminator"
        private const val L_HEARD_HEAT = "prop-heard-heat"
        private const val L_HEARD_DOTS = "prop-heard-dots"
        private const val L_HEARD_LABELS = "prop-heard-labels"
        private const val L_ME = "prop-me"
        private const val L_ME_LABEL = "prop-me-label"

        /** Marginal → red, possible → amber, likely → green; closed is clear. */
        fun colorFor(r: Float): Int {
            if (r < 0.10f) return 0
            val a: Int
            val rgb: Int
            if (r < 0.5f) {
                val f = (r - 0.10f) / 0.40f
                rgb = mix(0xFF6B6B, 0xF2A33A, f)
                a = (70 + 70 * f).toInt()
            } else {
                val f = ((r - 0.5f) / 0.5f).coerceAtMost(1f)
                rgb = mix(0xF2A33A, 0x4CC38A, f)
                a = (140 + 40 * f).toInt()
            }
            return (a shl 24) or rgb
        }

        private fun mix(a: Int, b: Int, f: Float): Int {
            fun ch(c: Int, s: Int) = (c shr s) and 0xFF
            fun m(s: Int) = (ch(a, s) + (ch(b, s) - ch(a, s)) * f).toInt().coerceIn(0, 255)
            return (m(16) shl 16) or (m(8) shl 8) or m(0)
        }
    }
}
