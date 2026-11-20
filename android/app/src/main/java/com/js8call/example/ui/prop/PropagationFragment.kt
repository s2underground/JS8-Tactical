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
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.navigation.NavigationBarView
import com.google.android.material.slider.Slider
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.js8call.example.R
import com.js8call.example.prop.Antenna
import com.js8call.example.prop.Band
import com.js8call.example.prop.Js8Speed
import com.js8call.example.prop.NetPath
import com.js8call.example.prop.Observation
import com.js8call.example.prop.ObservationStore
import com.js8call.example.prop.Predictor
import com.js8call.example.prop.PropagationModel
import com.js8call.example.prop.SpaceWeather
import com.js8call.example.prop.SpaceWeatherRepository
import com.js8call.example.prop.StationModel
import com.js8call.example.prop.StationProfile
import com.js8call.example.prop.Sun
import com.js8call.example.prop.WeightedObservation
import com.js8call.example.reports.Geo
import com.js8call.example.reports.LatLon
import com.js8call.example.reports.Reports
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
 * Propagation tab. Predictions come from [Predictor]: a sky model fitted to
 * real reception reports, adjusted for this station's power, speed and
 * antenna, corrected by what this station has learned from its own log, and
 * tuned by the last few hours of traffic. Everything works offline; solar
 * numbers fall back to NOAA's saved outlook, a 27-day repeat, or values
 * relayed by other stations.
 */
class PropagationFragment : Fragment() {

    private var mapView: MapView? = null
    private var map: MapLibreMap? = null
    private var style: Style? = null
    private lateinit var traffic: TrafficViewModel
    private lateinit var weather: SpaceWeatherRepository

    private var band: Band = Band.B40
    private var hoursAhead = 0f
    private var me: LatLon? = null
    private var centered = false
    /** Latest predictor (built off the main thread); used for taps. */
    @Volatile private var predictor: Predictor? = null
    /** Learned predictor and last picture, kept while the app runs so reopening the tab is instant. */
    private lateinit var cache: PropCache

    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    /** Sharper picture of the area on screen when zoomed in (own thread; newest request wins). */
    private val detailWorker = Executors.newSingleThreadExecutor()
    @Volatile private var detailGeneration = 0
    private var detailKey: String? = null
    @Volatile private var generation = 0

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        MapLibre.getInstance(requireContext().applicationContext)
        return inflater.inflate(R.layout.fragment_propagation, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        traffic = TrafficViewModel.of(requireContext())
        cache = ViewModelProvider(requireActivity())[PropCache::class.java]
        weather = traffic.weather
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
            m.addOnMapClickListener { latLng -> guarded("Prop tap") { onTap(latLng) }; true }
            m.addOnCameraIdleListener { renderDetail() }
        }

        buildBandChips(view)

        view.findViewById<Slider>(R.id.prop_slider).addOnChangeListener { _, value, _ ->
            hoursAhead = value
            render()
        }
        view.findViewById<Chip>(R.id.prop_show_predicted).setOnCheckedChangeListener { _, on ->
            setVisible(on, L_COVERAGE, L_DETAIL)
            if (on) { detailKey = null; renderDetail() }
        }
        view.findViewById<Chip>(R.id.prop_show_heard).setOnCheckedChangeListener { _, on ->
            setVisible(on, L_HEARD_HEAT, L_HEARD_DOTS, L_HEARD_LABELS, L_NET)
        }
        view.findViewById<Chip>(R.id.prop_solar).setOnClickListener { showSolarDialog() }
        view.findViewById<Chip>(R.id.prop_station).setOnClickListener { showStationDialog() }

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
        // Start with the last picture (if any) so reopening the tab doesn't flash empty.
        st.addSource(ImageSource(S_COVERAGE, WORLD_QUAD, cache.bitmap ?: Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)))
        st.addLayer(RasterLayer(L_COVERAGE, S_COVERAGE).withProperties(
            PropertyFactory.rasterOpacity(COVERAGE_OPACITY),
            PropertyFactory.rasterResampling(Property.RASTER_RESAMPLING_LINEAR),
            PropertyFactory.rasterFadeDuration(0f),
        ))
        // Detail image: fixed size, moved over the visible area after the camera settles.
        // (MapLibre 13 fixed the native crash this tab hit in 11.8; that was the heatmap
        // layer, not this one, but it is still updated only from the main thread.)
        st.addSource(ImageSource(S_DETAIL, WORLD_QUAD, Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)))
        st.addLayer(RasterLayer(L_DETAIL, S_DETAIL).withProperties(
            PropertyFactory.rasterOpacity(DETAIL_OPACITY),
            PropertyFactory.rasterResampling(Property.RASTER_RESAMPLING_LINEAR),
            PropertyFactory.rasterFadeDuration(0f),
            PropertyFactory.visibility(Property.NONE),
        ))
        listOf(S_NIGHT, S_TERMINATOR, S_NET, S_HEARD, S_ME).forEach { st.addSource(GeoJsonSource(it)) }
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
        st.addLayer(LineLayer(L_NET, S_NET).withProperties(
            PropertyFactory.lineColor("#7FD3FA"),
            PropertyFactory.lineWidth(1.2f),
            PropertyFactory.lineOpacity(Expression.get("o")),
        ))
        // Soft glow for stations heard. Deliberately not a HeatmapLayer: MapLibre 11.8 crashes in
        // RenderHeatmapLayer::update -> RenderLayer::updateTile (fixed upstream in android-v13.1.0, #4217).
        st.addLayer(CircleLayer(L_HEARD_HEAT, S_HEARD).withProperties(
            PropertyFactory.circleRadius(Expression.interpolate(Expression.linear(), Expression.zoom(),
                Expression.stop(0, 12f), Expression.stop(3, 22f), Expression.stop(7, 44f))),
            PropertyFactory.circleBlur(1f),
            PropertyFactory.circleColor("#7FD3FA"),
            PropertyFactory.circleOpacity(Expression.min(Expression.literal(0.75), Expression.product(Expression.get("w"), Expression.literal(0.55)))),
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
        guarded("Prop layer toggle") {
            layers.forEach { style?.getLayer(it)?.setProperties(PropertyFactory.visibility(if (on) Property.VISIBLE else Property.NONE)) }
        }
    }

    // ------------------------------------------------------------- render

    private fun selectedTime(now: Long = System.currentTimeMillis()) = now + (hoursAhead * 3_600_000L).toLong()

    private fun render() {
        val v = view ?: return
        val ctx = context ?: return
        val now = System.currentTimeMillis()
        val t = selectedTime(now)
        val wx = weather.effective(t, now)
        me = myPosition(ctx)
        val me = this.me
        val profile = StationProfile.load(ctx)

        v.findViewById<TextView>(R.id.prop_time).text = if (hoursAhead == 0f) {
            getString(R.string.prop_now, zuluTime(t))
        } else {
            getString(R.string.prop_ahead, fmtHours(hoursAhead), zuluTime(t))
        }
        renderSolar(v, wx, me != null)
        v.findViewById<Chip>(R.id.prop_station).text = stationLabel(profile)

        // Cheap layers straight away: night side, terminator, my marker.
        val st = style
        if (st != null) guarded("Prop layers") {
            val line = PropagationModel.terminator(t)
            (st.getSource(S_TERMINATOR) as? GeoJsonSource)?.setGeoJson(
                Feature.fromGeometry(LineString.fromLngLats(line.map { Point.fromLngLat(it.lon, it.lat) })),
            )
            val nightPole = if (Sun.subsolarPoint(t).lat >= 0) -MAX_LAT else MAX_LAT
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

        // Learning and painting run off the main thread; only the newest request is drawn.
        val gen = ++generation
        val band = this.band
        val all = traffic.observationStore.all()
        val listening = traffic.listeningLog.hours()
        val listenedHours = traffic.listeningLog.totalHours()
        val grids = HashMap<String, String?>().also { m ->
            all.forEach { o -> m[o.call] = o.grid ?: traffic.gridFor(o.call); o.by?.let { b -> m[b] = traffic.gridFor(b) } }
        }
        val gridFor: (String) -> String? = { grids[it] }
        submit(worker, "Prop render") {
            if (gen != generation) return@submit
            val key = "${all.size}|${listening.size}|$profile|$me"
            val cached = cache.learned
            val p = if (cached != null && cache.learnedKey == key && now - cache.learnedAtMs < 5 * 60_000L) {
                Predictor(me, wx, profile, cached.correction, cached.nowcast)
            } else {
                Predictor.build(me, wx, profile, all, listening, gridFor, now).also {
                    cache.learned = it
                    cache.learnedKey = key
                    cache.learnedAtMs = now
                }
            }
            predictor = p
            // Same band, same 5-minute slot, same inputs: reuse the last picture.
            val picKey = listOf(key, band.name, t / 300_000L, wx.sfi, wx.kIndex, p.nowcast.phi).joinToString("|")
            val reuse = cache.bitmapKey == picKey && cache.bitmap != null && cache.rows != null
            val heard = ObservationStore.relevant(all, band, t, now, gridFor)
                .groupBy { it.obs.call }
                .map { (_, list) -> list.maxBy { it.weight }.copy(weight = list.sumOf { it.weight }.coerceAtMost(1.5)) }
            val heardMe = heard.count { w -> all.any { it.call == w.obs.call && it.kind == Observation.Kind.HEARD_ME && it.band == band } }
            val net = ObservationStore.relevantPaths(all, band, t, now, gridFor)
            val rows = if (reuse) cache.rows!! else Band.entries.map { b ->
                val counts = IntArray(PropagationModel.CHART_BUCKETS_KM.size)
                if (me != null) {
                    ObservationStore.relevant(all, b, t, now, gridFor).map { it.obs.call to it.position }.distinctBy { it.first }
                        .forEach { (_, pos) -> counts[PropagationModel.CHART_EDGES_KM.indexOfFirst { Geo.distanceKm(me, pos) < it }]++ }
                }
                StoplightView.Row(b, p.stoplight(b, t), counts.toList())
            }
            val bitmap = if (reuse) cache.bitmap else {
                p.coverage(band, t, GRID_W, GRID_H, MAX_LAT, -MAX_LAT, -180.0, 180.0)?.let { toBitmap(it, GRID_W, GRID_H) }
                    .also { bmp -> cache.bitmap = bmp; cache.rows = rows; cache.bitmapKey = picKey }
            }
            if (gen != generation) return@submit
            main.post { if (gen == generation) guarded("Prop deliver") { deliver(heard, heardMe, net, rows, bitmap, t, p, listenedHours) } }
        }
    }

    private fun deliver(
        heard: List<WeightedObservation>,
        heardMe: Int,
        net: List<NetPath>,
        rows: List<StoplightView.Row>,
        bitmap: Bitmap?,
        t: Long,
        p: Predictor,
        listenedHours: Double,
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
            (st.getSource(S_NET) as? GeoJsonSource)?.setGeoJson(FeatureCollection.fromFeatures(net.map { n ->
                Feature.fromGeometry(LineString.fromLngLats(listOf(Point.fromLngLat(n.from.lon, n.from.lat), Point.fromLngLat(n.to.lon, n.to.lat)))).apply {
                    addNumberProperty("o", (0.25 + 0.5 * n.weight).coerceAtMost(0.8))
                }
            }))
            (st.getSource(S_COVERAGE) as? ImageSource)?.setImage(bitmap ?: Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888))
        }
        v.findViewById<TextView>(R.id.prop_heard_count).text = when {
            heard.isEmpty() && net.isEmpty() -> getString(R.string.prop_heard_none)
            else -> getString(R.string.prop_heard_summary_net, heard.size, heardMe, net.size)
        }
        v.findViewById<TextView>(R.id.prop_learning).text = learningText(p, listenedHours)
        v.findViewById<StoplightView>(R.id.prop_chart).apply {
            this.rows = rows
            selected = band
        }
        v.findViewById<TextView>(R.id.prop_chart_title).text = getString(R.string.prop_chart_title,
            if (hoursAhead == 0f) getString(R.string.prop_now, zuluTime(t)) else zuluTime(t))
        detailKey = null
        renderDetail()
    }

    /** Sharper prediction for the area on screen once zoomed in past a whole-world view. */
    private fun renderDetail() = guarded("Prop detail") {
        val m = map ?: return@guarded
        val st = style ?: return@guarded
        val p = predictor ?: return@guarded
        val v = view ?: return@guarded
        if (v.width == 0 || v.height == 0) return@guarded
        val showPredicted = v.findViewById<Chip>(R.id.prop_show_predicted)?.isChecked != false
        val bounds = m.projection.visibleRegion.latLngBounds
        val north = bounds.latitudeNorth.coerceIn(-MAX_LAT, MAX_LAT)
        val south = bounds.latitudeSouth.coerceIn(-MAX_LAT, MAX_LAT)
        val west = bounds.longitudeWest
        val east = bounds.longitudeEast
        val sane = listOf(north, south, west, east).all { it.isFinite() }
        if (!sane || !showPredicted || m.cameraPosition.zoom < 2.6 || east <= west || east - west > 200 || north <= south) {
            detailKey = null
            st.getLayer(L_DETAIL)?.setProperties(PropertyFactory.visibility(Property.NONE))
            st.getLayer(L_COVERAGE)?.setProperties(PropertyFactory.rasterOpacity(COVERAGE_OPACITY))
            return@guarded
        }
        val band = this.band
        val t = selectedTime()
        // Skip work when nothing changed (camera idle fires often).
        val key = String.format(Locale.US, "%s|%d|%.3f|%.3f|%.3f|%.3f", band.name, t / 600_000L, north, south, west, east)
        if (key == detailKey) return@guarded
        detailKey = key
        val gen = ++detailGeneration
        submit(detailWorker, "Prop detail") {
            if (gen != detailGeneration) return@submit
            val grid = p.coverage(band, t, DETAIL_W, DETAIL_H, north, south, west, east) ?: return@submit
            val bmp = toBitmap(grid, DETAIL_W, DETAIL_H)
            main.post {
                if (gen != detailGeneration || view == null) return@post
                val s = style ?: return@post
                guarded("Prop detail draw") {
                    (s.getSource(S_DETAIL) as? ImageSource)?.apply {
                        setCoordinates(LatLngQuad(LatLng(north, west), LatLng(north, east), LatLng(south, east), LatLng(south, west)))
                        setImage(bmp)
                    }
                    // The sharper picture sits on top of the world picture: two see-through layers
                    // stacked hid the base map. Dim the world one so together they look like one.
                    s.getLayer(L_COVERAGE)?.setProperties(PropertyFactory.rasterOpacity(COVERAGE_UNDER_DETAIL_OPACITY))
                    s.getLayer(L_DETAIL)?.setProperties(
                        PropertyFactory.rasterOpacity(DETAIL_OPACITY),
                        PropertyFactory.visibility(Property.VISIBLE),
                    )
                }
            }
        }
    }

    private fun learningText(p: Predictor, listenedHours: Double): String {
        val c = p.correction
        val parts = mutableListOf<String>()
        parts += when {
            !p.profile.learn -> getString(R.string.prop_learning_off)
            c.pairs == 0 -> getString(R.string.prop_learning_start, listenedHours.roundToInt())
            else -> getString(R.string.prop_learning_status, c.heard, c.pairs, String.format(Locale.US, "%.1f", c.days.coerceAtLeast(0.1)))
        }
        if (p.nowcast.evidence >= 15 && kotlin.math.abs(p.nowcast.phi - 1.0) > 0.01) {
            parts += getString(R.string.prop_live_tune, String.format(Locale.US, "%+d%%", ((p.nowcast.phi - 1) * 100).roundToInt()), p.nowcast.evidence)
        }
        return parts.joinToString(" · ")
    }

    private fun renderSolar(v: View, wx: SpaceWeather, haveMe: Boolean) {
        v.findViewById<Chip>(R.id.prop_solar).text = getString(R.string.prop_solar_chip, wx.sfi.roundToInt(), fmtK(wx.kIndex), sourceText(wx))
        val notice = v.findViewById<TextView>(R.id.prop_notice)
        notice.text = when {
            !haveMe -> getString(R.string.prop_no_position)
            wx.source == SpaceWeatherRepository.SOURCE_DEFAULT -> getString(R.string.prop_default_wx)
            else -> null
        }
        notice.isVisible = !notice.text.isNullOrEmpty()
    }

    private fun sourceText(wx: SpaceWeather): String {
        val age = ageText(wx.updatedMs)
        return when {
            wx.source == SpaceWeatherRepository.SOURCE_NOAA -> getString(R.string.prop_src_noaa, age)
            wx.source == SpaceWeatherRepository.SOURCE_MANUAL -> getString(R.string.prop_src_manual, age)
            wx.source == SpaceWeatherRepository.SOURCE_FORECAST -> getString(R.string.prop_src_forecast)
            wx.source == SpaceWeatherRepository.SOURCE_RECURRENCE -> getString(R.string.prop_src_recurrence)
            wx.source.startsWith(SpaceWeatherRepository.SOURCE_RELAY_PREFIX) ->
                getString(R.string.prop_src_relay, wx.source.removePrefix(SpaceWeatherRepository.SOURCE_RELAY_PREFIX), age)
            else -> getString(R.string.prop_src_default)
        }
    }

    private fun toBitmap(grid: FloatArray, w: Int, h: Int): Bitmap {
        val pixels = IntArray(grid.size) { colorFor(grid[it]) }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun onTap(latLng: LatLng) {
        val p = predictor ?: return
        val me = this.me ?: return
        val v = view ?: return
        val t = selectedTime()
        val to = LatLon(latLng.latitude, latLng.longitude)
        val path = p.path(to, t) ?: return
        val sky = PropagationModel.Sky(t)
        val r = p.score(to, sky, band)
        val verdict = when {
            r >= 0.66 -> "likely"
            r >= 0.33 -> "possible"
            r >= 0.10 -> "marginal"
            else -> "unlikely"
        }
        val best = Band.entries.maxBy { p.score(to, sky, it) }
        val dirs = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        val dir = dirs[(((Geo.bearingDeg(me, to) + 22.5) % 360) / 45).toInt()]
        val margin = StationModel.marginDb(p.profile, path, band)
        val text = String.format(
            Locale.US, "%,d km %s on %s: %s · MUF %.1f MHz · best %s · your margin %+.0f dB",
            path.distanceKm.roundToInt(), dir, band.label, verdict, path.medianMufMHz, best.label, margin,
        )
        Snackbar.make(v, text, Snackbar.LENGTH_LONG).show()
    }

    // ------------------------------------------------------------- station profile

    private fun stationLabel(s: StationProfile): String {
        val w = if (s.powerW >= 10) s.powerW.roundToInt().toString() else String.format(Locale.US, "%.1f", s.powerW)
        val ant = if (s.antenna == Antenna.AVERAGE) getString(R.string.prop_station_no_antenna) else s.antenna.label
        return getString(R.string.prop_station_chip, w, s.speed.label, ant)
    }

    private fun showStationDialog() {
        val ctx = requireContext()
        val cur = StationProfile.load(ctx)
        val pad = (20 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad / 2, pad, 0) }

        val powerLayout = TextInputLayout(ctx).apply { hint = getString(R.string.prop_station_power) }
        val powerEdit = TextInputEditText(powerLayout.context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(if (cur.powerW % 1.0 == 0.0) cur.powerW.toInt().toString() else cur.powerW.toString())
        }
        powerLayout.addView(powerEdit)
        box.addView(powerLayout)

        fun label(res: Int) = TextView(ctx).apply {
            text = getString(res); setPadding(0, pad / 2, 0, pad / 4)
            setTextColor(ctx.getColor(R.color.tac_text_dim)); textSize = 13f
        }
        box.addView(label(R.string.prop_station_speed))
        val speedGroup = ChipGroup(ctx).apply { isSingleSelection = true; isSelectionRequired = true }
        val speedOptions: List<Js8Speed?> = listOf(null) + Js8Speed.entries
        speedOptions.forEach { sp ->
            val chip = layoutInflater.inflate(R.layout.item_prop_band_chip, speedGroup, false) as Chip
            chip.id = View.generateViewId()
            chip.text = sp?.label ?: getString(R.string.prop_station_follow_tx, cur.txSpeed.label)
            chip.tag = sp?.name ?: "FOLLOW"
            chip.isChecked = sp == cur.speedOverride
            speedGroup.addView(chip)
        }
        box.addView(speedGroup)

        box.addView(label(R.string.prop_station_antenna))
        val antGroup = ChipGroup(ctx).apply { isSingleSelection = true; isSelectionRequired = true }
        Antenna.entries.forEach { a ->
            val chip = layoutInflater.inflate(R.layout.item_prop_band_chip, antGroup, false) as Chip
            chip.id = View.generateViewId()
            chip.text = a.label
            chip.tag = a
            chip.isChecked = a == cur.antenna
            antGroup.addView(chip)
        }
        box.addView(antGroup)
        val antDetail = TextView(ctx).apply { textSize = 12f; setTextColor(ctx.getColor(R.color.tac_text_quiet)); text = cur.antenna.detail }
        box.addView(antDetail)
        antGroup.setOnCheckedStateChangeListener { g, ids ->
            (ids.firstOrNull()?.let { g.findViewById<Chip>(it)?.tag } as? Antenna)?.let { antDetail.text = it.detail }
        }

        val adjust = MaterialSwitch(ctx).apply { text = getString(R.string.prop_station_adjust); isChecked = cur.adjust }
        val learn = MaterialSwitch(ctx).apply { text = getString(R.string.prop_station_learn); isChecked = cur.learn }
        box.addView(adjust)
        box.addView(learn)

        val scroll = androidx.core.widget.NestedScrollView(ctx).apply { addView(box) }
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.prop_station_title)
            .setView(scroll)
            .setPositiveButton(R.string.prop_solar_save) { _, _ ->
                val power = powerEdit.text?.toString()?.toDoubleOrNull()?.coerceIn(0.01, 2000.0) ?: cur.powerW
                val speedTag = speedGroup.findViewById<Chip>(speedGroup.checkedChipId)?.tag as? String
                val speed = speedTag?.takeIf { it != "FOLLOW" }?.let { Js8Speed.valueOf(it) }
                val ant = antGroup.findViewById<Chip>(antGroup.checkedChipId)?.tag as? Antenna ?: cur.antenna
                StationProfile.save(ctx, cur.copy(powerW = power, speedOverride = speed, antenna = ant, adjust = adjust.isChecked, learn = learn.isChecked))
                render()
            }
            .setNeutralButton(R.string.prop_station_reset) { _, _ -> confirmReset() }
            .setNegativeButton(R.string.prop_solar_cancel, null)
            .show()
    }

    private fun confirmReset() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.prop_station_reset)
            .setMessage(R.string.prop_station_reset_body)
            .setPositiveButton(R.string.prop_station_reset_yes) { _, _ ->
                traffic.observationStore.clear()
                traffic.listeningLog.clear()
                render()
            }
            .setNegativeButton(R.string.prop_solar_cancel, null)
            .show()
    }

    // ------------------------------------------------------------- solar data

    private fun showSolarDialog() {
        val wx = weather.effective(selectedTime())
        val forecastDays = weather.forecast().count { it.date >= SpaceWeatherRepository.dateKey(System.currentTimeMillis()) }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.prop_solar_title)
            .setMessage(getString(R.string.prop_solar_body2, wx.sfi.roundToInt(), fmtK(wx.kIndex), sourceText(wx), forecastDays))
            .setPositiveButton(R.string.prop_solar_refresh) { _, _ -> refresh(announce = true) }
            .setNegativeButton(R.string.prop_solar_manual) { _, _ -> showManualDialog(wx) }
            .setNeutralButton(R.string.prop_solar_share) { _, _ -> shareSolar(wx) }
            .show()
    }

    /** Put a #SWX line in the Traffic compose box so the operator can send it to the net. */
    private fun shareSolar(wx: SpaceWeather) {
        val at = if (wx.updatedMs > 0) wx.updatedMs else System.currentTimeMillis()
        val line = Reports.encode(Reports.SWX, mapOf("F" to wx.sfi.roundToInt().toString(), "K" to fmtK(wx.kIndex), "T" to Reports.dtg(at)))
        traffic.requestCompose(line)
        activity?.findViewById<NavigationBarView>(R.id.bottom_navigation)?.selectedItemId = R.id.navigation_traffic
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
                weather.setManual(sfi, k)
                render()
            }
            .setNegativeButton(R.string.prop_solar_cancel, null)
            .show()
    }

    private fun refresh(announce: Boolean) {
        weather.refresh { result ->
            main.post {
                if (!isAdded) return@post
                if (result != null) render()
                if (announce) view?.let { v ->
                    val msg = if (result != null) getString(R.string.prop_refresh_ok, result.sfi.roundToInt(), fmtK(result.kIndex))
                    else getString(R.string.prop_refresh_failed)
                    Snackbar.make(v, msg, Snackbar.LENGTH_LONG).show()
                }
            }
        }
    }

    // ------------------------------------------------------------- helpers

    /** Run [block] on [pool]; an error is recorded for the error report instead of closing the app. */
    private fun submit(pool: java.util.concurrent.ExecutorService, where: String, block: () -> Unit) {
        try {
            pool.execute {
                try { block() } catch (e: Throwable) { reportError(where, e) }
            }
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            // Tab is closing; nothing to draw.
        }
    }

    private inline fun guarded(where: String, block: () -> Unit) {
        try { block() } catch (e: Exception) { reportError(where, e) }
    }

    private fun reportError(where: String, e: Throwable) {
        if (e is OutOfMemoryError) System.gc()
        com.js8call.example.CrashReporter.recordNonFatal(where, e)
        main.post {
            val v = view ?: return@post
            if (!errorShown) {
                errorShown = true
                Snackbar.make(v, R.string.prop_error_saved, Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private var errorShown = false

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
        detailGeneration++
        detailKey = null
        mapView?.onDestroy()
        mapView = null
        map = null
        style = null
        super.onDestroyView()
    }

    override fun onDestroy() {
        worker.shutdownNow()
        detailWorker.shutdownNow()
        super.onDestroy()
    }

    companion object {
        private const val PREF_BAND = "prop_band"
        private const val PREF_CHART = "prop_chart_open"
        private const val GRID_W = 360
        private const val GRID_H = 270
        private const val MAX_LAT = 85.0

        private val WORLD_QUAD = LatLngQuad(
            LatLng(MAX_LAT, -180.0), LatLng(MAX_LAT, 180.0),
            LatLng(-MAX_LAT, 180.0), LatLng(-MAX_LAT, -180.0),
        )

        private const val DETAIL_W = 200
        private const val DETAIL_H = 150
        private const val S_DETAIL = "prop-detail"
        private const val L_DETAIL = "prop-detail"
        private const val COVERAGE_OPACITY = 0.85f
        // Stacked: 1 - (1 - 0.3a)(1 - 0.65a) is within a few % of 0.85a for the colours' alpha a (0.27-0.7).
        private const val COVERAGE_UNDER_DETAIL_OPACITY = 0.3f
        private const val DETAIL_OPACITY = 0.65f
        private const val S_COVERAGE = "prop-coverage"
        private const val S_NIGHT = "prop-night"
        private const val S_TERMINATOR = "prop-terminator"
        private const val S_NET = "prop-net"
        private const val S_HEARD = "prop-heard"
        private const val S_ME = "prop-me"
        private const val L_COVERAGE = "prop-coverage"
        private const val L_NIGHT = "prop-night"
        private const val L_TERMINATOR = "prop-terminator"
        private const val L_NET = "prop-net"
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
