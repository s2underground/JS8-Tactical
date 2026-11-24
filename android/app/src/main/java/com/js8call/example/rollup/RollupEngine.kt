package com.js8call.example.rollup

import java.util.Calendar
import java.util.TimeZone
import kotlin.math.abs

/**
 * Message rollup for JS8 traffic.
 *
 * Turns the raw, interleaved frame-by-frame decode stream into one entry per
 * message: who sent it, to whom, how complete it is and how many copies were
 * heard. Purely deterministic; never invents text. Missing frames are shown
 * as explicit gaps.
 *
 * How it works:
 *  1. Lanes: frames are grouped by audio offset (within [laneToleranceHz]).
 *     Continuation frames carry no callsign, so the lane is what ties them to
 *     the first frame of their transmission.
 *  2. Slots: a multi-frame message goes out in consecutive time slots, so the
 *     slot number of each frame gives its position. An empty slot between the
 *     first and last frame is a known missing frame at a known position.
 *  3. Senders: the first frame carries the sender (and usually the target).
 *  4. Repeats: JS8 encoding is deterministic, so a resent message produces
 *     identical frames in identical order. Copies are merged slot by slot.
 *     Copies that disagree anywhere are kept apart (shown as edited repeats).
 *
 * Not thread-safe; call from one thread (e.g. the main thread or a ViewModel).
 */
class RollupEngine(
    private val laneToleranceHz: Float = 10f,
    /** Close an open transmission after this many slots with no new frame. */
    private val idleSlotsBeforeClose: Int = 2,
    /** Copies further apart than this are never merged. */
    private val mergeWindowMs: Long = 60 * 60 * 1000L,
    /** Upper bound on frames in one transmission (guards bad slot math). */
    private val maxFramesPerTransmission: Int = 64,
) {

    private var nextId = 1L
    private val open = mutableListOf<Transmission>()
    private val messages = mutableListOf<RolledMessage>()

    /** Add one decoded frame. [nowMs] defaults to the frame's receive time. */
    fun addFrame(frame: Frame, nowMs: Long = frame.receivedAtMs) {
        closeIdle(nowMs, exceptLaneOf = frame)
        val slot = slotOf(frame)
        val lane = open.firstOrNull { abs(it.laneHz - frame.frequencyHz) <= laneToleranceHz }

        if (frame.isFirst) {
            lane?.let { close(it) }
            val tx = Transmission(nextId++, frame.frequencyHz, slot, frame.periodSeconds, headMissing = false)
            tx.put(0, frame)
            open += tx
            if (frame.isLast) close(tx)
            return
        }

        if (lane != null) {
            val index = (slot - lane.startSlot).toInt()
            if (index in 1 until maxFramesPerTransmission && !lane.hasIndex(index)) {
                lane.put(index, frame)
                if (frame.isLast) close(lane)
                return
            }
            // Out of order or impossible position: this frame starts something new.
            close(lane)
        }

        // Continuation frame with no open transmission: its first frame was missed.
        val tx = Transmission(nextId++, frame.frequencyHz, slot, frame.periodSeconds, headMissing = true)
        tx.put(0, frame)
        open += tx
        if (frame.isLast) close(tx)
    }

    /** Close transmissions that have gone quiet. Call periodically (e.g. once a period). */
    fun tick(nowMs: Long) = closeIdle(nowMs, exceptLaneOf = null)

    /** Rolled-up messages, oldest first. Includes partial ones that have closed. */
    fun messages(): List<RolledMessage> {
        sortedCache?.let { if (sortedVersion == version) return it }
        return messages.sortedBy { it.firstHeardMs }.also { sortedCache = it; sortedVersion = version }
    }

    /** Bumped on every change to [messages]; [messages()] reuses its sorted copy until then. */
    private var version = 0L
    private var sortedCache: List<RolledMessage>? = null
    private var sortedVersion = -1L

    /** Transmissions still being received (for a live "receiving from…" indicator). */
    fun live(): List<LiveTransmission> = open.map { tx ->
        val parsed = tx.firstFrame()?.let { HeaderParser.parse(it.text) }
        LiveTransmission(
            laneHz = tx.laneHz,
            sender = parsed?.sender,
            framesSoFar = tx.frames.size,
            textSoFar = TextAssembler.segments(tx.ordered(), tx.headMissing, tailMissing = false)
                .joinToString("") { (it as? Segment.Text)?.text ?: "…" },
            lastFrameMs = tx.lastFrameMs,
        )
    }

    /** Messages grouped into threads by sender (stations whose sender is unknown are grouped by lane). */
    fun threads(): Map<String, List<RolledMessage>> =
        messages().groupBy { it.sender ?: "?${it.laneHz.toInt()}" }

    fun clear() {
        open.clear()
        messages.clear()
        version++
    }

    /** Forget finished messages last heard before [beforeMs] (history retention). */
    fun prune(beforeMs: Long) {
        messages.removeAll { it.lastHeardMs < beforeMs }
        version++
    }

    // ---------------------------------------------------------------- internals

    private fun closeIdle(nowMs: Long, exceptLaneOf: Frame?) {
        val stale = open.filter { tx ->
            if (exceptLaneOf != null && abs(tx.laneHz - exceptLaneOf.frequencyHz) <= laneToleranceHz) return@filter false
            nowMs - tx.lastFrameMs > (idleSlotsBeforeClose + 1) * tx.periodSeconds * 1000L
        }
        stale.forEach { close(it) }
    }

    private fun close(tx: Transmission) {
        if (!open.remove(tx)) return
        version++
        val copy = tx.toCopy()
        val target = findMergeTarget(copy)
        if (target != null) {
            target.absorb(copy)
        } else {
            messages += RolledMessage.from(nextId++, copy)
        }
    }

    private fun findMergeTarget(copy: Copy): RolledMessage? {
        val candidates = messages.asReversed().filter { msg ->
            copy.firstHeardMs - msg.lastHeardMs <= mergeWindowMs &&
                (copy.sender == null || msg.sender == null || copy.sender == msg.sender)
        }
        for (msg in candidates) {
            val offset = msg.alignmentFor(copy) ?: continue
            // Merged copies obey the same length limit as one transmission, so a string of
            // overlapping partial copies can't grow a message without end.
            if (msg.mergedSize(copy, offset) > maxFramesPerTransmission) continue
            return msg
        }
        return null
    }

    private fun slotOf(frame: Frame): Long = frame.slotStartEpochSec / frame.periodSeconds

    // ---------------------------------------------------------------- types

    /** One decoded frame, as the decoder reports it. */
    data class Frame(
        /** UTC time of the period start, HHMMSS, as the engine reports it. */
        val utc: Int,
        val frequencyHz: Float,
        /** JS8 frame flags: bit 0 first, bit 1 last, bit 2 data. */
        val type: Int,
        val text: String,
        val snr: Int,
        /** Submode: 0 Normal, 1 Fast, 2 Turbo, 4 Slow. */
        val mode: Int = 0,
        val receivedAtMs: Long,
    ) {
        val isFirst get() = (type and 1) != 0
        val isLast get() = (type and 2) != 0
        val isData get() = (type and 4) != 0
        val periodSeconds: Int get() = periodSecondsFor(mode)

        /** Absolute start of this frame's period, from its UTC field and the receive date. */
        val slotStartEpochSec: Long
            get() {
                val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
                cal.timeInMillis = receivedAtMs
                cal.set(Calendar.HOUR_OF_DAY, utc / 10000)
                cal.set(Calendar.MINUTE, (utc / 100) % 100)
                cal.set(Calendar.SECOND, utc % 100)
                cal.set(Calendar.MILLISECOND, 0)
                var sec = cal.timeInMillis / 1000
                // A frame from just before midnight decoded just after it.
                if (sec * 1000 > receivedAtMs + 60_000) sec -= 86_400
                return sec
            }
    }

    sealed class Segment {
        data class Text(val text: String) : Segment()
        /** [count] frames known to be missing here; null when the count is unknown. */
        data class Gap(val count: Int?) : Segment()
    }

    data class LiveTransmission(
        val laneHz: Float,
        val sender: String?,
        val framesSoFar: Int,
        val textSoFar: String,
        val lastFrameMs: Long,
    )

    /** One received copy of a transmission, positions 0..n-1 (null = missing). */
    internal class Copy(
        val laneHz: Float,
        val frames: List<Frame?>,
        val firstHeardMs: Long,
        val lastHeardMs: Long,
    ) {
        val headMissing: Boolean get() = frames.firstOrNull()?.isFirst != true
        val tailMissing: Boolean get() = frames.lastOrNull()?.isLast != true
        val sender: String? = if (headMissing) null else frames.first()?.let { HeaderParser.parse(it.text)?.sender }
    }

    private class Transmission(
        val id: Long,
        val laneHz: Float,
        val startSlot: Long,
        val periodSeconds: Int,
        val headMissing: Boolean,
    ) {
        val frames = sortedMapOf<Int, Frame>()
        var lastFrameMs = 0L

        fun put(index: Int, frame: Frame) {
            frames[index] = frame
            lastFrameMs = maxOf(lastFrameMs, frame.receivedAtMs)
        }

        fun hasIndex(index: Int) = frames.containsKey(index)
        fun firstFrame(): Frame? = if (headMissing) null else frames[0]

        fun ordered(): List<Frame?> {
            if (frames.isEmpty()) return emptyList()
            val last = frames.lastKey()
            return (0..last).map { frames[it] }
        }

        fun toCopy(): Copy {
            val ordered = ordered()
            return Copy(
                laneHz = laneHz,
                frames = ordered,
                firstHeardMs = frames.values.minOf { it.receivedAtMs },
                lastHeardMs = lastFrameMs,
            )
        }
    }
}

/** A message assembled from one or more received copies. */
class RolledMessage private constructor(
    val id: Long,
    val laneHz: Float,
    frames: List<RollupEngine.Frame?>,
    firstHeardMs: Long,
    lastHeardMs: Long,
) {
    /** Merged frames by position; null = still missing. */
    var frames: List<RollupEngine.Frame?> = frames; private set
    var firstHeardMs: Long = firstHeardMs; private set
    var lastHeardMs: Long = lastHeardMs; private set
    var copies: Int = 1; private set
    /** True when copies disagreed at some position (should not happen for exact repeats). */
    var conflict: Boolean = false; private set

    /** The first frame (with the sender) was never heard. */
    val headMissing: Boolean get() = frames.firstOrNull()?.isFirst != true
    /** The last frame was never heard, so more may be missing at the end. */
    val tailMissing: Boolean get() = frames.lastOrNull()?.isLast != true
    val sender: String? get() = derived().sender
    val recipient: String? get() = derived().recipient
    val missingFrames: Int get() = frames.count { it == null }
    val isComplete: Boolean get() = !headMissing && !tailMissing && missingFrames == 0
    val bestSnr: Int? get() = frames.filterNotNull().maxOfOrNull { it.snr }

    /** Display segments: text with explicit gaps. The sender/recipient header is stripped. */
    val segments: List<RollupEngine.Segment>
        get() = derived().segments

    /** Plain body text with "[…]" where frames are missing. */
    val bodyText: String
        get() = derived().bodyText

    /**
     * Header parse and text assembly, worked out once per version of [frames] (the list is
     * replaced, never changed in place, when a copy merges in). The traffic screen reads these
     * many times per message on every refresh; recomputing each time got slow on a busy net.
     */
    private class Derived(val frames: List<RollupEngine.Frame?>, headMissing: Boolean, tailMissing: Boolean) {
        private val header = if (headMissing) null else frames.first()?.let { HeaderParser.parse(it.text) }
        val sender: String? = header?.sender
        val recipient: String? = header?.recipient
        val segments: List<RollupEngine.Segment> = TextAssembler.segments(frames, headMissing, tailMissing, stripHeader = true)
        val bodyText: String = segments.joinToString("") {
            when (it) {
                is RollupEngine.Segment.Text -> it.text
                is RollupEngine.Segment.Gap -> "[…]"
            }
        }.trim()
    }

    @Volatile private var cached: Derived? = null

    private fun derived(): Derived {
        val f = frames
        cached?.let { if (it.frames === f) return it }
        return Derived(f, headMissing, tailMissing).also { cached = it }
    }

    /**
     * Position in this message where [copy]'s first received frame lands, or
     * null if [copy] is not a repeat of this message. A repeat must agree
     * everywhere the two overlap and share at least one body frame (sharing
     * only the "CALL: @GROUP" header is not enough).
     */
    internal fun alignmentFor(copy: RollupEngine.Copy): Int? {
        val offsets = when {
            !copy.headMissing && !headMissing -> listOf(0)
            !copy.headMissing && headMissing -> (-(copy.frames.size - 1)..0).toList()
            copy.headMissing && !headMissing -> (1 until frames.size + copy.frames.size).toList()
            else -> (-(copy.frames.size - 1) until frames.size).toList()
        }
        return offsets.filter { agreesAt(copy, it) }.singleOrNull()
    }

    private fun agreesAt(copy: RollupEngine.Copy, offset: Int): Boolean {
        var sharedBody = 0
        copy.frames.forEachIndexed { i, f ->
            val mine = frames.getOrNull(i + offset)
            if (f != null && mine != null) {
                if (f.text != mine.text || f.isFirst != mine.isFirst || f.isLast != mine.isLast) return false
                if (isBody(f)) sharedBody++
            }
        }
        // Copies that both end with a last frame must end at the same position.
        if (!copy.tailMissing && !tailMissing && copy.frames.size + offset != frames.size) return false
        // A copy that ends cannot run past this message's end, and vice versa.
        if (!copy.tailMissing && copy.frames.size + offset < frames.size && frames.drop((copy.frames.size + offset).coerceAtLeast(0)).any { it != null }) return false
        if (!tailMissing && copy.frames.size + offset > frames.size && copy.frames.drop((frames.size - offset).coerceAtLeast(0)).any { it != null }) return false
        return sharedBody > 0
    }

    private fun isBody(f: RollupEngine.Frame): Boolean {
        if (f.isFirst) return false
        val t = f.text.trim()
        return t.isNotEmpty() && !t.startsWith("@") && HeaderParser.parse(t) == null
    }

    /** Length of this message after merging [copy] at [offset]. */
    internal fun mergedSize(copy: RollupEngine.Copy, offset: Int): Int {
        val shift = if (offset < 0) -offset else 0
        return maxOf(frames.size + shift, offset + shift + copy.frames.size)
    }

    internal fun absorb(copy: RollupEngine.Copy) {
        val offset = alignmentFor(copy) ?: return
        val shift = if (offset < 0) -offset else 0
        val start = offset + shift
        val size = mergedSize(copy, offset)
        val merged = MutableList<RollupEngine.Frame?>(size) { null }
        frames.forEachIndexed { i, f -> merged[i + shift] = f }
        copy.frames.forEachIndexed { i, f ->
            if (f == null) return@forEachIndexed
            val j = i + start
            val existing = merged[j]
            if (existing == null) merged[j] = f
            else if (existing.text != f.text) conflict = true
        }
        frames = merged
        firstHeardMs = minOf(firstHeardMs, copy.firstHeardMs)
        lastHeardMs = maxOf(lastHeardMs, copy.lastHeardMs)
        copies++
    }

    internal companion object {
        fun from(id: Long, copy: RollupEngine.Copy) =
            RolledMessage(id, copy.laneHz, copy.frames, copy.firstHeardMs, copy.lastHeardMs)
    }
}

/** Reads sender and recipient from a transmission's first frame text. */
object HeaderParser {
    data class Header(val sender: String, val recipient: String?, val headerLength: Int)

    private val directed = Regex("""^\s*([A-Z0-9]{1,3}[0-9][A-Z0-9/]*[A-Z])\s*:\s*(@?[A-Z0-9/]+)?""")
    private val compoundIdentity = Regex("""^\s*([A-Z0-9]{1,3}[0-9][A-Z0-9/]*[A-Z])\s+([A-R]{2}[0-9]{2}([A-X]{2})?)\s*$""")

    fun parse(text: String): Header? {
        val upper = text.uppercase()
        directed.find(upper)?.let { m ->
            return Header(m.groupValues[1], m.groupValues[2].ifEmpty { null }, m.range.last + 1)
        }
        compoundIdentity.find(upper)?.let { m ->
            return Header(m.groupValues[1], null, text.length)
        }
        return null
    }
}

/** Joins frame texts the way JS8Call displays them, with explicit gaps. */
internal object TextAssembler {
    fun segments(
        frames: List<RollupEngine.Frame?>,
        headMissing: Boolean,
        tailMissing: Boolean,
        stripHeader: Boolean = false,
    ): List<RollupEngine.Segment> {
        val out = mutableListOf<RollupEngine.Segment>()
        if (headMissing) out += RollupEngine.Segment.Gap(null)
        var gapRun = 0
        var prev: RollupEngine.Frame? = null
        frames.forEachIndexed { index, frame ->
            if (frame == null) {
                gapRun++
                return@forEachIndexed
            }
            if (gapRun > 0) {
                out += RollupEngine.Segment.Gap(gapRun)
                gapRun = 0
                prev = null
            }
            var text = frame.text
            if (index == 0 && !headMissing && stripHeader) {
                val header = HeaderParser.parse(text)
                text = if (header != null) text.substring(minOf(header.headerLength, text.length)) else text
                // A compound identity frame ("CALL GRID") carries no body.
                if (header != null && header.recipient == null && !frame.isData) text = ""
            }
            if (text.isNotEmpty()) {
                val p = prev
                // p.text can be empty (a frame that decoded to no text): lastOrNull, not last().
                if (p != null && !p.isData && p.text.lastOrNull()?.isWhitespace() == false && !text.first().isWhitespace()) {
                    appendText(out, " ")
                }
                appendText(out, text)
            }
            prev = frame
        }
        if (gapRun > 0) out += RollupEngine.Segment.Gap(gapRun)
        if (tailMissing) out += RollupEngine.Segment.Gap(null)
        return out
    }

    private fun appendText(out: MutableList<RollupEngine.Segment>, text: String) {
        val last = out.lastOrNull()
        if (last is RollupEngine.Segment.Text) out[out.lastIndex] = RollupEngine.Segment.Text(last.text + text)
        else out += RollupEngine.Segment.Text(text)
    }
}

/** Period length for a JS8 submode. */
fun periodSecondsFor(mode: Int): Int = when (mode) {
    1 -> 10   // Fast
    2 -> 6    // Turbo
    4 -> 30   // Slow
    else -> 15 // Normal
}
