package com.js8call.example.ui.traffic

/**
 * Which @groups get a row in the Traffic list.
 *
 * - Groups the operator watches (Settings › My Groups) always do.
 * - Groups only heard on the air do when "Show groups heard on the air" is on, unless the
 *   operator hid them; a hidden group stays hidden until it is watched again. Field report
 *   (build 344): "Stop watching" on a busy heard group did nothing, because the next message
 *   to it brought it straight back.
 * - The group being viewed keeps its row, so the screen doesn't lose what it is showing.
 * @HB (heartbeats) never gets one.
 */
object GroupList {

    const val PREF_AUTO_SHOW = "traffic_auto_show_groups"
    const val PREF_HIDDEN = "traffic_hidden_groups"

    fun visible(
        watched: List<String>,
        heard: List<String>,
        hidden: Set<String>,
        autoShow: Boolean,
        selected: String?,
    ): List<String> {
        val fromAir = if (autoShow) heard.filterNot { it in hidden } else emptyList()
        val keep = listOfNotNull(selected?.takeIf { it.startsWith("@") && it in heard })
        return (watched + fromAir + keep).filterNot { it == "@HB" }.distinct()
    }

    fun parse(raw: String?): List<String> =
        raw.orEmpty().split(',', ' ', ';').map { it.trim().uppercase() }.filter { it.length > 1 }

    /** The hidden list with [group] added or removed. */
    fun hide(raw: String?, group: String): String = (parse(raw) + group.uppercase()).distinct().joinToString(",")
    fun unhide(raw: String?, group: String): String = parse(raw).filterNot { it == group.uppercase() }.joinToString(",")
}
