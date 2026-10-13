package com.nithin.nightdrive

import android.content.Context

/** The widgets that can be placed in a home screen slot. */
enum class Widget(val label: Int, val layout: Int) {
    MAP(R.string.widget_map, R.layout.widget_map),
    MEDIA(R.string.widget_media, R.layout.widget_media),
    DATE(R.string.widget_date, R.layout.widget_date),
    CLOCK(R.string.widget_clock, R.layout.widget_clock),
    WEATHER(R.string.widget_weather, R.layout.widget_weather),
    NAV(R.string.widget_nav, R.layout.widget_nav)
}

/** Saved home screen and sidebar arrangement. Slots are ordered large, small top, small bottom. */
object DashboardPrefs {

    /** Sidebar entries that follow whichever app handles the job, instead of one fixed app. */
    const val DOCK_MAPS = "maps"
    const val DOCK_MUSIC = "music"
    const val DOCK_PHONE = "phone"
    const val DOCK_MAX = 5
    const val OPTION_CORNERS = "rounded_corners"
    const val OPTION_SWEEP = "sweep_seconds"
    private const val PREFS = "nightdrive"
    private const val KEY_SLOTS = "slots"
    private const val KEY_LARGE_RIGHT = "large_right"
    private const val KEY_DOCK = "dock"
    private const val KEY_DIPLAY_ADDED = "diplay_added"
    private const val DIPLAY_PACKAGE = "com.shihab.diplay"
    private const val DIPLAY = "$DIPLAY_PACKAGE/com.shilapi.xcertplay.DiPlayActivity"
    private const val KEY_CLOCK = "clock_style"
    private val DEFAULT_SLOTS = listOf(Widget.MAP, Widget.CLOCK, Widget.MEDIA)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun slots(context: Context): MutableList<Widget> {
        val saved = prefs(context).getString(KEY_SLOTS, null)?.split(',')
            ?.mapNotNull { name -> Widget.values().firstOrNull { it.name == name } }
        val valid = saved != null && saved.size == DEFAULT_SLOTS.size && saved.toSet().size == saved.size
        return (if (valid) saved!! else DEFAULT_SLOTS).toMutableList()
    }

    fun saveSlots(context: Context, slots: List<Widget>) =
        prefs(context).edit().putString(KEY_SLOTS, slots.joinToString(",") { it.name }).apply()

    fun largeOnRight(context: Context) = prefs(context).getBoolean(KEY_LARGE_RIGHT, false)

    fun saveLargeOnRight(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_LARGE_RIGHT, value).apply()

    fun clockStyle(context: Context): ClockStyle {
        val name = prefs(context).getString(KEY_CLOCK, null)
        return ClockStyle.values().firstOrNull { it.name == name } ?: ClockStyle.DIGITAL_LIGHT
    }

    fun saveClockStyle(context: Context, style: ClockStyle) =
        prefs(context).edit().putString(KEY_CLOCK, style.name).apply()

    fun option(context: Context, key: String) = prefs(context).getBoolean(key, false)

    fun saveOption(context: Context, key: String, value: Boolean) =
        prefs(context).edit().putBoolean(key, value).apply()

    /** Sidebar apps in order: a DOCK_* entry or a flattened component name. */
    fun dock(context: Context): MutableList<String> {
        val prefs = prefs(context)
        val dock = prefs.getString(KEY_DOCK, null)
            ?.split('|')?.filter { it.isNotEmpty() }?.take(DOCK_MAX)?.toMutableList()
            ?: mutableListOf(DOCK_MAPS, DOCK_MUSIC, DOCK_PHONE)
        // DiPlay (CarPlay receiver) joins the sidebar once; removing it afterwards sticks. The
        // entry shows nothing while the app is not installed.
        if (!prefs.getBoolean(KEY_DIPLAY_ADDED, false)) {
            if (dock.none { it.startsWith("$DIPLAY_PACKAGE/") } && dock.size < DOCK_MAX) dock.add(DIPLAY)
            prefs.edit().putBoolean(KEY_DIPLAY_ADDED, true).putString(KEY_DOCK, dock.joinToString("|")).apply()
        }
        return dock
    }

    fun saveDock(context: Context, dock: List<String>) =
        prefs(context).edit().putString(KEY_DOCK, dock.joinToString("|")).apply()
}
