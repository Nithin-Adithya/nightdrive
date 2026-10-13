package com.nithin.nightdrive

import android.app.Activity
import android.app.ActivityOptions
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.PorterDuff
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.os.SystemClock
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import java.util.EnumMap
import java.util.concurrent.Executors
import kotlin.math.abs

class LauncherActivity : Activity() {

    private lateinit var pager: ViewPager2
    private lateinit var dots: LinearLayout
    private lateinit var dockToggle: ImageButton
    private lateinit var dockApps: LinearLayout
    private lateinit var dockAdd: View
    private lateinit var dock: MutableList<String>
    private var dockIcons: Map<String, Bitmap?> = emptyMap()
    private lateinit var netBars: ImageView
    private lateinit var themePicker: View
    private lateinit var themeList: LinearLayout
    private lateinit var wallpaperId: String
    private var appearance = Appearance.DARK
    private var light = false
    private lateinit var netLabel: TextView
    private lateinit var nowPlaying: NowPlaying

    private val pages = PagesAdapter()
    private val loader = Executors.newSingleThreadExecutor()
    private var apps: List<AppInfo> = emptyList()
    private var media = NowPlaying.State()
    private var dashboard: DashboardHolder? = null
    private var mapCard: MapCard? = null
    private var started = false

    // Frosted glass: the blurred wallpaper that cards and the sidebar paint behind themselves.
    private var frost: Bitmap? = null
    private lateinit var rootView: View
    private lateinit var gridBackdrop: ImageView
    private lateinit var dockGlass: GlassDrawable
    private val corner = IntArray(2)

    // Home screen arrangement: which widget is in each slot (large, top, bottom).
    private lateinit var slots: MutableList<Widget>
    private var largeOnRight = false
    private var clockStyle = ClockStyle.DIGITAL_LIGHT
    private var editing = false
    private lateinit var editBar: View
    private lateinit var sheet: View

    private var online = false

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            val label = when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> R.string.net_wifi
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> R.string.net_cell
                else -> R.string.net_wired
            }
            val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            runOnUiThread { showNetwork(validated, label) }
        }

        override fun onLost(network: Network) {
            runOnUiThread { showNetwork(false, R.string.net_offline) }
        }
    }

    private val tickReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            refreshAppearance()
            refreshWeather()
        }
    }

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = refreshBluetooth()
    }
    private lateinit var btIcon: ImageView
    private lateinit var tempLabel: TextView
    private var weatherAt = 0L
    private var weather: Weather.Report? = null
    private var roundedCorners = false
    private var sweepSeconds = false

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = loadApps()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_launcher)

        pager = findViewById(R.id.pager)
        dots = findViewById(R.id.dots)
        dockToggle = findViewById(R.id.dock_toggle)
        dockApps = findViewById(R.id.dock_apps)
        dockAdd = findViewById(R.id.dock_add)
        dock = DashboardPrefs.dock(this)
        dockAdd.setOnClickListener { chooseDockApp(-1) }
        netBars = findViewById(R.id.net_bars)
        themePicker = findViewById(R.id.theme_picker)
        themeList = findViewById(R.id.theme_list)
        themePicker.setOnClickListener { Motion.dismiss(themePicker) }
        findViewById<View>(R.id.theme_done).setOnClickListener { Motion.dismiss(themePicker) }
        findViewById<View>(R.id.appearance_auto).setOnClickListener { chooseAppearance(Appearance.AUTO) }
        findViewById<View>(R.id.appearance_light).setOnClickListener { chooseAppearance(Appearance.LIGHT) }
        findViewById<View>(R.id.appearance_dark).setOnClickListener { chooseAppearance(Appearance.DARK) }
        rootView = findViewById(R.id.root)
        btIcon = findViewById(R.id.bt_icon)
        tempLabel = findViewById(R.id.temp_label)
        gridBackdrop = findViewById(R.id.grid_backdrop)
        gridBackdrop.setColorFilter(0x73000000, PorterDuff.Mode.SRC_ATOP)
        val dockView = findViewById<View>(R.id.dock)
        dockGlass = glass(0f) { dockView.getLocationInWindow(it) }
        dockGlass.veil = 0x660B0F1A.toInt()
        dockView.background = dockGlass
        roundedCorners = DashboardPrefs.option(this, DashboardPrefs.OPTION_CORNERS)
        sweepSeconds = DashboardPrefs.option(this, DashboardPrefs.OPTION_SWEEP)
        findViewById<View>(R.id.option_corners).setOnClickListener {
            roundedCorners = !roundedCorners
            DashboardPrefs.saveOption(this, DashboardPrefs.OPTION_CORNERS, roundedCorners)
            applyOptions()
        }
        findViewById<View>(R.id.option_sweep).setOnClickListener {
            sweepSeconds = !sweepSeconds
            DashboardPrefs.saveOption(this, DashboardPrefs.OPTION_SWEEP, sweepSeconds)
            applyOptions()
        }
        applyOptions()
        appearance = Themes.savedAppearance(this)
        light = Themes.isLight(appearance)
        applyWallpaper(Themes.savedWallpaper(this))
        netLabel = findViewById(R.id.net_label)

        slots = DashboardPrefs.slots(this)
        largeOnRight = DashboardPrefs.largeOnRight(this)
        clockStyle = DashboardPrefs.clockStyle(this)
        editBar = findViewById(R.id.edit_bar)
        sheet = findViewById(R.id.sheet)
        sheet.setOnClickListener { Motion.dismiss(sheet) }
        findViewById<View>(R.id.edit_done).setOnClickListener { setEditing(false) }
        findViewById<View>(R.id.edit_swap).setOnClickListener {
            largeOnRight = !largeOnRight
            DashboardPrefs.saveLargeOnRight(this, largeOnRight)
            dashboard?.arrange()
        }
        findViewById<View>(R.id.theme_edit_home).setOnClickListener {
            Motion.dismiss(themePicker)
            setEditing(true)
        }

        pager.adapter = pages
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) = updateIndicators()

            // The softened backdrop fades in as the dashboard gives way to the app grid.
            override fun onPageScrolled(position: Int, offset: Float, offsetPixels: Int) {
                gridBackdrop.alpha = (position + offset).coerceIn(0f, 1f)
            }
        })
        pager.setPageTransformer { page, position ->
            val distance = abs(position).coerceAtMost(1f)
            val scale = 1f - distance * 0.05f
            page.scaleX = scale
            page.scaleY = scale
            // Fading the dashboard would force its live map through an extra buffer every frame.
            if (page is GridLayout) page.alpha = 1f - distance * 0.6f
        }
        Motion.pressable(dockToggle)
        Motion.pressable(dockAdd)

        dockToggle.setOnClickListener {
            pager.setCurrentItem(if (pager.currentItem == 0) 1 else 0, true)
        }
        rebuildDock()

        nowPlaying = NowPlaying(this) {
            media = it
            dashboard?.renderMedia()
            updateDockActive()
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(
            this, packageReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED
        )
        // The calendar icon shows today's date, so redraw the icons when the day rolls over.
        val dateFilter = IntentFilter().apply {
            addAction(Intent.ACTION_DATE_CHANGED)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        ContextCompat.registerReceiver(
            this, packageReceiver, dateFilter, ContextCompat.RECEIVER_NOT_EXPORTED
        )

        rebuildDots()
        loadApps()
        hideSystemBars()
    }

    override fun onStart() {
        super.onStart()
        started = true
        refreshAppearance()
        registerReceiver(tickReceiver, IntentFilter(Intent.ACTION_TIME_TICK))
        val bluetoothFilter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        registerReceiver(bluetoothReceiver, bluetoothFilter)
        refreshBluetooth()
        showNetwork(false, R.string.net_offline)
        getSystemService(ConnectivityManager::class.java)
            .registerDefaultNetworkCallback(networkCallback)
        nowPlaying.start()
        Navigation.onChange = { dashboard?.renderNav() }
        dashboard?.renderNav()
        mapCard?.resume()
    }

    override fun onStop() {
        started = false
        unregisterReceiver(tickReceiver)
        unregisterReceiver(bluetoothReceiver)
        getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback)
        mapCard?.pause()
        Navigation.onChange = null
        nowPlaying.stop()
        super.onStop()
    }

    override fun onDestroy() {
        mapCard?.destroy()
        unregisterReceiver(packageReceiver)
        loader.shutdown()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Home pressed while we are already in front.
        if (intent.hasCategory(Intent.CATEGORY_HOME)) {
            Motion.dismiss(themePicker)
            Motion.dismiss(sheet)
            setEditing(false)
            pager.setCurrentItem(0, true)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == MapCard.REQUEST_LOCATION) {
            mapCard?.onLocationPermissionResult()
            refreshWeather(force = true)
        }
    }

    /** Full screen by default; a swipe in from the top or bottom edge brings the bars back briefly. */
    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // A launcher never finishes; back closes whatever is open, else returns to the dashboard.
        when {
            sheet.visibility == View.VISIBLE -> Motion.dismiss(sheet)
            editing -> setEditing(false)
            themePicker.visibility == View.VISIBLE -> Motion.dismiss(themePicker)
            else -> pager.setCurrentItem(0, true)
        }
    }

    /** Sidebar status: bars are solid only while the connection actually reaches the internet. */
    private fun showNetwork(connected: Boolean, label: Int) {
        if (!started) return
        netBars.alpha = if (connected) 1f else 0.35f
        netLabel.setText(label)
        val cameOnline = connected && !online
        online = connected
        if (cameOnline) {
            mapCard?.retry()
            refreshWeather()
        }
    }

    /** Sidebar Bluetooth mark: solid with a device connected, faint when merely on, hidden when off. */
    private fun refreshBluetooth() {
        val state = try {
            val adapter = getSystemService(BluetoothManager::class.java)?.adapter
            when {
                adapter == null || !adapter.isEnabled -> 0
                BLUETOOTH_PROFILES.any {
                    adapter.getProfileConnectionState(it) == BluetoothProfile.STATE_CONNECTED
                } -> 2
                else -> 1
            }
        } catch (e: SecurityException) {
            0
        }
        btIcon.visibility = if (state == 0) View.GONE else View.VISIBLE
        btIcon.alpha = if (state == 2) 1f else 0.4f
    }

    /** Outside temperature under the clock, refreshed at most every 20 minutes while online. */
    private fun refreshWeather(force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!started || !online) return
        if (!force && weatherAt != 0L && now - weatherAt < WEATHER_INTERVAL_MS) return
        weatherAt = now
        // Its own thread: finding a position can take many seconds and must not hold up app loading.
        Thread {
            val report = Weather.report(applicationContext)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (report == null) {
                    // Try again on the next minute tick; keep showing the last reading meanwhile.
                    weatherAt = 0L
                } else {
                    weather = report
                    tempLabel.text = getString(R.string.temperature, report.temperature)
                    tempLabel.visibility = View.VISIBLE
                    dashboard?.renderWeather()
                }
            }
        }.start()
    }

    // ---- Themes ----------------------------------------------------------------------------

    private fun applyWallpaper(id: String) {
        wallpaperId = id
        rootView.background = Themes.wallpaper(this, id, light)
        // Fresh drawables for the blurs, so the full-size one keeps its cached render.
        frost = Frost.blur(Themes.wallpaper(this, id, light), 384, 225, 5)
        gridBackdrop.setImageBitmap(Frost.blur(Themes.wallpaper(this, id, light), 512, 300, 2))
        dockGlass.invalidateSelf()
        dashboard?.refreshGlass()
    }

    /** A glass background; [origin] must write the surface's top-left corner in window pixels. */
    private fun glass(radius: Float, origin: (IntArray) -> Unit) =
        GlassDrawable(radius, { frost }) { spot ->
            origin(corner)
            spot[0] = corner[0].toFloat()
            spot[1] = corner[1].toFloat()
            spot[2] = rootView.width.toFloat()
            spot[3] = rootView.height.toFloat()
        }

    private fun applyOptions() {
        findViewById<View>(R.id.corner_mask).visibility = if (roundedCorners) View.VISIBLE else View.GONE
        findViewById<View>(R.id.option_corners).isSelected = roundedCorners
        findViewById<View>(R.id.option_sweep).isSelected = sweepSeconds
        dashboard?.updateClock()
    }

    private fun chooseWallpaper(id: String) {
        applyWallpaper(id)
        Themes.saveWallpaper(this, id)
        refreshThemePicker()
    }

    private fun chooseAppearance(choice: Appearance) {
        appearance = choice
        Themes.saveAppearance(this, choice)
        refreshAppearance()
        refreshThemePicker()
    }

    /** Re-evaluates light/dark; with AUTO this flips at 6 in the morning and evening. */
    private fun refreshAppearance() {
        val nowLight = Themes.isLight(appearance)
        if (nowLight == light) return
        light = nowLight
        applyWallpaper(wallpaperId)
        dashboard?.applyAppearance()
        mapCard?.dark = !light
    }

    private fun showThemePicker() {
        refreshThemePicker()
        Motion.reveal(themePicker)
    }

    private fun refreshThemePicker() {
        findViewById<View>(R.id.appearance_auto).isSelected = appearance == Appearance.AUTO
        findViewById<View>(R.id.appearance_light).isSelected = appearance == Appearance.LIGHT
        findViewById<View>(R.id.appearance_dark).isSelected = appearance == Appearance.DARK

        themeList.removeAllViews()
        addThemeTile(getString(R.string.choose_photo), null, false) { pickPhoto() }
        if (Themes.customFile(this).exists()) {
            addThemeTile(
                getString(R.string.your_photo), preview(Themes.CUSTOM_ID),
                wallpaperId == Themes.CUSTOM_ID
            ) { chooseWallpaper(Themes.CUSTOM_ID) }
        }
        for (option in Themes.all(this)) {
            addThemeTile(option.name, preview(option.id), wallpaperId == option.id) {
                chooseWallpaper(option.id)
            }
        }
    }

    /** The wallpaper with a miniature of the current home screen layout drawn over it. */
    private fun preview(id: String): Drawable = LayerDrawable(
        arrayOf(Themes.wallpaper(this, id, light), DashboardMock(light, largeOnRight))
    )

    private fun addThemeTile(name: String, preview: Drawable?, selected: Boolean, onClick: () -> Unit) {
        val item = LayoutInflater.from(this).inflate(R.layout.item_theme, themeList, false)
        item.findViewById<ImageView>(R.id.theme_preview).apply {
            clipToOutline = true
            isSelected = selected
            if (preview == null) {
                scaleType = ImageView.ScaleType.CENTER
                setImageResource(R.drawable.ic_add)
            } else {
                setImageDrawable(preview)
            }
        }
        item.findViewById<TextView>(R.id.theme_name).text = name
        item.setOnClickListener { onClick() }
        Motion.pressable(item)
        themeList.addView(item)
    }

    /** Lets the user use any image on the unit (or a USB stick) as the wallpaper. */
    private fun pickPhoto() {
        val open = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*")
        val get = Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*")
        for (intent in listOf(open, get)) {
            try {
                @Suppress("DEPRECATION")
                startActivityForResult(intent, REQUEST_PHOTO)
                return
            } catch (e: ActivityNotFoundException) {
                // Try the next kind of picker.
            }
        }
        toast(R.string.no_file_picker)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode != REQUEST_PHOTO || resultCode != RESULT_OK || uri == null) return
        val root = findViewById<View>(R.id.root)
        val width = root.width
        val height = root.height
        loader.execute {
            val imported = Themes.importWallpaper(applicationContext, uri, width, height)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (imported) chooseWallpaper(Themes.CUSTOM_ID) else toast(R.string.photo_failed)
            }
        }
    }

    // ---- Editing the home screen ------------------------------------------------------------

    /** In edit mode every slot shows a "tap to change" cover and the pager stops swiping. */
    private fun setEditing(on: Boolean) {
        if (editing == on) return
        editing = on
        if (on) pager.setCurrentItem(0, false)
        pager.isUserInputEnabled = !on
        editBar.visibility = if (on) View.VISIBLE else View.GONE
        rebuildDock()
        dashboard?.arrange()
    }

    private fun chooseWidget(slot: Int) {
        showSheet(
            getString(R.string.choose_widget),
            Widget.values().map { widget ->
                Choice(getString(widget.label), null, widget == slots[slot]) {
                    // A widget lives in one slot at a time, so picking a placed one swaps the two.
                    val other = slots.indexOf(widget)
                    if (other >= 0) slots[other] = slots[slot]
                    slots[slot] = widget
                    DashboardPrefs.saveSlots(this, slots)
                    dashboard?.arrange()
                }
            }
        )
    }

    private fun chooseClockStyle() {
        val previewPx = (46 * resources.displayMetrics.density).toInt()
        showSheet(
            getString(R.string.clock_style),
            ClockStyle.values().map { style ->
                Choice(getString(style.label), ClockView.preview(this, style, previewPx), style == clockStyle) {
                    clockStyle = style
                    DashboardPrefs.saveClockStyle(this, style)
                    dashboard?.updateClock()
                }
            }
        )
    }

    // ---- Sidebar apps ---------------------------------------------------------------------

    /** Fills the sidebar from the saved list. The add button only shows while editing. */
    private fun rebuildDock() {
        dockApps.removeAllViews()
        dock.forEachIndexed { i, entry ->
            val button = layoutInflater.inflate(R.layout.item_dock, dockApps, false) as ImageButton
            // Fall back to the package, in case an update renamed the app's launch screen.
            val app = apps.firstOrNull { it.component.flattenToString() == entry }
                ?: apps.firstOrNull { entry.startsWith(it.component.packageName + "/") }
            when (entry) {
                DashboardPrefs.DOCK_MAPS -> {
                    button.setImageResource(R.drawable.tile_maps)
                    button.tag = MAPS_PACKAGE
                    button.setOnClickListener { openMaps(button) }
                }
                DashboardPrefs.DOCK_MUSIC -> {
                    button.setImageResource(R.drawable.tile_music)
                    button.tag = DashboardPrefs.DOCK_MUSIC
                    button.setOnClickListener { openMusic(button) }
                }
                DashboardPrefs.DOCK_PHONE -> {
                    button.setImageResource(R.drawable.tile_phone)
                    button.setOnClickListener { openPhone(button) }
                }
                // An app that has since been uninstalled keeps its place but shows nothing.
                else -> {
                    if (app == null) return@forEachIndexed
                    button.tag = app.component.packageName
                    button.setOnClickListener { launch(app.component, button) }
                }
            }
            Motion.pressable(button)
            (dockIcons[entry] ?: app?.icon)?.let(button::setImageBitmap)
            button.setOnLongClickListener {
                chooseDockApp(i)
                true
            }
            dockApps.addView(button)
        }
        val canAdd = editing && dock.size < DashboardPrefs.DOCK_MAX
        dockAdd.visibility = if (canAdd) View.VISIBLE else View.GONE
        updateDockActive()
    }

    /** Marks the sidebar app that is playing; the generic music entry stands in for other players. */
    private fun updateDockActive() {
        val player = media.packageName.takeIf { media.playing }
        val keys = (0 until dockApps.childCount).map { dockApps.getChildAt(it).tag as? String }
        val hasOwnEntry = player != null && player in keys
        for (i in keys.indices) {
            val active = player != null &&
                (keys[i] == player || (!hasOwnEntry && keys[i] == DashboardPrefs.DOCK_MUSIC))
            dockApps.getChildAt(i).foreground = if (active) getDrawable(R.drawable.dock_active) else null
        }
    }

    /** Pick the app for sidebar position [index], or add a new one when [index] is -1. */
    private fun chooseDockApp(index: Int) {
        val current = dock.getOrNull(index)
        fun save() {
            DashboardPrefs.saveDock(this, dock)
            rebuildDock()
        }
        val choices = apps.filter { it.component.className != AppLoader.THEMES_ENTRY }.map { app ->
            val flat = app.component.flattenToString()
            Choice(app.label, app.icon, flat == current) {
                if (index >= 0) dock[index] = flat else dock.add(flat)
                save()
            }
        }
        val remove = Choice(getString(R.string.dock_remove), null, false) {
            dock.removeAt(index)
            save()
        }
        // The built-in entries, so they can be put back after being replaced. One each at most.
        val builtIn = listOf(
            DashboardPrefs.DOCK_MAPS to R.string.dock_maps_auto,
            DashboardPrefs.DOCK_MUSIC to R.string.dock_music_auto,
            DashboardPrefs.DOCK_PHONE to R.string.dock_phone_auto
        ).filter { (entry, _) -> entry == current || entry !in dock }.map { (entry, label) ->
            Choice(getString(label), dockIcons[entry], entry == current) {
                if (index >= 0) dock[index] = entry else dock.add(entry)
                save()
            }
        }
        val all = builtIn + choices
        showSheet(getString(R.string.choose_app), if (index >= 0) listOf(remove) + all else all)
    }

    private class Choice(
        val label: String, val icon: Bitmap?, val selected: Boolean, val onPick: () -> Unit
    )

    /** A simple pick-one list shown over everything. */
    private fun showSheet(title: String, choices: List<Choice>) {
        findViewById<TextView>(R.id.sheet_title).text = title
        val list = findViewById<LinearLayout>(R.id.sheet_list)
        list.removeAllViews()
        for (choice in choices) {
            val row = layoutInflater.inflate(R.layout.item_choice, list, false)
            row.findViewById<TextView>(R.id.choice_label).text = choice.label
            if (choice.icon != null) {
                row.findViewById<ImageView>(R.id.choice_icon).apply {
                    setImageBitmap(choice.icon)
                    visibility = View.VISIBLE
                }
            }
            if (choice.selected) row.findViewById<View>(R.id.choice_check).visibility = View.VISIBLE
            row.setOnClickListener {
                Motion.dismiss(sheet)
                choice.onPick()
            }
            list.addView(row)
        }
        Motion.reveal(sheet)
    }

    // ---- Apps -------------------------------------------------------------------------------

    private fun loadApps() {
        val iconPx = resources.getDimensionPixelSize(R.dimen.app_icon)
        val dockPx = resources.getDimensionPixelSize(R.dimen.dock_icon)
        loader.execute {
            val context = applicationContext
            val loaded = AppLoader.load(context, iconPx)
            // The dock shows the real icons of the apps it opens; the drawn tiles are the fallback.
            val maps = AppLoader.packageIcon(context, MAPS_PACKAGE, dockPx)
            val music = AppLoader.handlerIcon(
                context, Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MUSIC), dockPx
            )
            val phone = AppLoader.handlerIcon(context, Intent(Intent.ACTION_DIAL), dockPx)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                dockIcons = mapOf(
                    DashboardPrefs.DOCK_MAPS to maps,
                    DashboardPrefs.DOCK_MUSIC to music,
                    DashboardPrefs.DOCK_PHONE to phone
                )
                apps = loaded
                pages.notifyDataSetChanged()
                rebuildDock()
                rebuildDots()
            }
        }
    }

    private fun appPageCount() = maxOf(1, (apps.size + PER_PAGE - 1) / PER_PAGE)

    private fun rebuildDots() {
        val count = pages.itemCount
        if (dots.childCount != count) {
            dots.removeAllViews()
            repeat(count) { LayoutInflater.from(this).inflate(R.layout.item_dot, dots, true) }
        }
        updateIndicators()
    }

    private fun updateIndicators() {
        val current = pager.currentItem
        for (i in 0 until dots.childCount) dots.getChildAt(i).isSelected = i == current
        dockToggle.setImageResource(if (current == 0) R.drawable.ic_apps else R.drawable.ic_dashboard)
    }

    // ---- Launching --------------------------------------------------------------------------

    /** Starts [intent]; with [from], the new app zooms open out of that view. */
    private fun start(intent: Intent, from: View? = null): Boolean = try {
        val zoom = from?.let { ActivityOptions.makeScaleUpAnimation(it, 0, 0, it.width, it.height) }
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), zoom?.toBundle())
        true
    } catch (e: Exception) {
        false
    }

    private fun launch(component: ComponentName, from: View? = null) {
        if (component.className == AppLoader.THEMES_ENTRY) {
            showThemePicker()
            return
        }
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(component)
            .addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        if (!start(intent, from)) toast(R.string.cannot_open)
    }

    private fun launchPackage(packageName: String, from: View? = null): Boolean {
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return false
        return start(intent, from)
    }

    private fun openAppInfo(component: ComponentName) {
        if (component.packageName == packageName) return
        start(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.fromParts("package", component.packageName, null))
        )
    }

    private fun openMaps(from: View? = null) {
        if (launchPackage(MAPS_PACKAGE, from)) return
        if (start(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0")), from)) return
        toast(R.string.no_maps)
    }

    private fun openMusic(from: View? = null) {
        val playing = nowPlaying.packageName
        if (playing != null && launchPackage(playing, from)) return
        if (start(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MUSIC), from)) return
        pager.setCurrentItem(1, true)
    }

    private fun openPhone(from: View? = null) {
        if (!start(Intent(Intent.ACTION_DIAL), from)) toast(R.string.no_phone)
    }

    private fun toast(message: Int) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    // ---- Pages ------------------------------------------------------------------------------

    private inner class PagesAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        override fun getItemCount() = 1 + appPageCount()

        override fun getItemViewType(position: Int) =
            if (position == 0) TYPE_DASHBOARD else TYPE_APPS

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == TYPE_DASHBOARD) {
                DashboardHolder(inflater.inflate(R.layout.page_dashboard, parent, false))
                    .also { dashboard = it }
            } else {
                AppsHolder(inflater.inflate(R.layout.page_apps, parent, false) as GridLayout)
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (holder) {
                is DashboardHolder -> holder.renderMedia()
                is AppsHolder -> holder.bind(position - 1)
            }
        }
    }

    /**
     * The home screen: three slots filled with whichever widgets the user chose. Widget views are
     * created the first time they are needed and dropped again when no slot shows them.
     */
    private inner class DashboardHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val root = view as LinearLayout
        private val slotLarge: FrameLayout = view.findViewById(R.id.slot_large)
        private val column: View = view.findViewById(R.id.dash_column)
        private val slotViews = listOf<FrameLayout>(
            slotLarge, view.findViewById(R.id.slot_top), view.findViewById(R.id.slot_bottom)
        )
        private val widgetViews = EnumMap<Widget, View>(Widget::class.java)
        private var mediaWidget: MediaWidget? = null

        init {
            arrange()
        }

        /** Rebuilds the slots from the saved arrangement (and adds the edit covers if editing). */
        fun arrange() {
            val gap = (8 * resources.displayMetrics.density).toInt()
            root.removeAllViews()
            val ordered = if (largeOnRight) listOf(column, slotLarge) else listOf(slotLarge, column)
            ordered.forEachIndexed { i, child ->
                val weight = if (child === slotLarge) 1.5f else 1f
                val params = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight)
                params.marginStart = if (i == 0) 0 else gap
                root.addView(child, params)
            }

            for (widget in widgetViews.keys.toList()) {
                if (widget !in slots) release(widget)
            }
            slotViews.forEachIndexed { i, slot ->
                slot.removeAllViews()
                val widget = widgetViews.getOrPut(slots[i]) { create(slots[i]) }
                (widget.parent as? ViewGroup)?.removeView(widget)
                slot.addView(widget)
                if (editing) slot.addView(editCover(i))
            }
        }

        private fun create(widget: Widget): View {
            val view = layoutInflater.inflate(widget.layout, slotLarge, false)
            view.background = glass(resources.getDimension(R.dimen.card_radius)) { corner ->
                // Where the card rests on screen, ignoring how far the pager has slid the page.
                var x = 0
                var y = 0
                var v: View = view
                while (v !== itemView) {
                    x += v.left
                    y += v.top
                    v = v.parent as? View ?: break
                }
                pager.getLocationInWindow(corner)
                corner[0] += x
                corner[1] += y
            }
            view.clipToOutline = true
            when (widget) {
                Widget.MAP -> {
                    val open = view.findViewById<View>(R.id.map_open)
                    open.setOnClickListener { openMaps() }
                    mapCard?.destroy()
                    mapCard = MapCard(
                        this@LauncherActivity, view.findViewById(R.id.map_web), open, ::openMaps
                    ).also {
                        it.dark = !light
                        if (started) it.resume()
                    }
                }
                Widget.MEDIA -> mediaWidget = MediaWidget(view)
                Widget.CLOCK -> {
                    view.findViewById<ClockView>(R.id.clock_view).apply {
                        style = clockStyle
                        sweep = sweepSeconds
                    }
                    view.setOnClickListener { chooseClockStyle() }
                }
                Widget.WEATHER -> view.setOnClickListener {
                    if (Weather.hasLocationPermission(this@LauncherActivity)) {
                        refreshWeather(force = true)
                    } else {
                        requestPermissions(
                            arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION),
                            MapCard.REQUEST_LOCATION
                        )
                    }
                }
                Widget.NAV -> view.setOnClickListener { openMaps() }
                else -> Unit
            }
            // The map takes its own touches, so it can't start editing; every other widget can.
            if (widget != Widget.MAP) {
                view.setOnLongClickListener {
                    setEditing(true)
                    true
                }
            }
            tint(view)
            when (widget) {
                Widget.WEATHER -> bindWeather(view)
                Widget.NAV -> bindNav(view)
                else -> Unit
            }
            mediaWidget?.render(media)
            return view
        }

        private fun release(widget: Widget) {
            widgetViews.remove(widget)
            when (widget) {
                Widget.MAP -> {
                    mapCard?.destroy()
                    mapCard = null
                }
                Widget.MEDIA -> mediaWidget = null
                else -> Unit
            }
        }

        private fun editCover(slot: Int): View {
            val cover = layoutInflater.inflate(R.layout.slot_edit, slotLarge, false)
            cover.findViewById<TextView>(R.id.slot_edit_name).setText(slots[slot].label)
            cover.setOnClickListener { chooseWidget(slot) }
            return cover
        }

        fun updateClock() {
            widgetViews[Widget.CLOCK]?.findViewById<ClockView>(R.id.clock_view)?.apply {
                style = clockStyle
                sweep = sweepSeconds
            }
        }

        fun renderWeather() {
            widgetViews[Widget.WEATHER]?.let(::bindWeather)
        }

        private fun bindWeather(view: View) {
            val icon = view.findViewById<ImageView>(R.id.weather_icon)
            val temp = view.findViewById<TextView>(R.id.weather_temp)
            val condition = view.findViewById<TextView>(R.id.weather_condition)
            val range = view.findViewById<TextView>(R.id.weather_range)
            val report = weather
            icon.imageTintList = ColorStateList.valueOf(primaryText())
            if (report == null) {
                icon.setImageResource(R.drawable.ic_wx_cloud)
                temp.visibility = View.GONE
                condition.setText(R.string.weather_idle)
                val granted = Weather.hasLocationPermission(this@LauncherActivity)
                range.setText(if (granted) R.string.weather_wait_hint else R.string.weather_idle_hint)
                return
            }
            icon.setImageResource(report.icon)
            temp.visibility = View.VISIBLE
            temp.text = getString(R.string.temperature, report.temperature)
            condition.setText(report.condition)
            val known = report.high != null && report.low != null
            range.text = if (known) getString(R.string.weather_range, report.high, report.low) else ""
        }

        fun renderNav() {
            widgetViews[Widget.NAV]?.let(::bindNav)
        }

        private fun bindNav(view: View) {
            val icon = view.findViewById<ImageView>(R.id.nav_icon)
            val distance = view.findViewById<TextView>(R.id.nav_distance)
            val instruction = view.findViewById<TextView>(R.id.nav_instruction)
            val eta = view.findViewById<TextView>(R.id.nav_eta)
            val step = Navigation.step
            if (step?.icon != null) {
                // Navigation apps supply a white arrow; darken it on light cards.
                icon.imageTintList = if (light) ColorStateList.valueOf(primaryText()) else null
                icon.setImageBitmap(step.icon)
            } else {
                icon.imageTintList = ColorStateList.valueOf(primaryText())
                icon.setImageResource(R.drawable.ic_navigate)
            }
            distance.visibility = if (step == null || step.distance.isEmpty()) View.GONE else View.VISIBLE
            distance.text = step?.distance
            instruction.text = step?.instruction ?: getString(R.string.nav_idle)
            eta.text = step?.eta ?: getString(R.string.nav_idle_hint)
        }

        fun renderMedia() {
            mediaWidget?.render(media)
        }

        /** Recolours every widget for the current light or dark appearance. */
        fun applyAppearance() {
            widgetViews.values.forEach(::tint)
            renderWeather()
            renderNav()
            renderMedia()
        }

        /** Repaints the glass after the wallpaper behind it changed. */
        fun refreshGlass() {
            widgetViews.values.forEach { it.background?.invalidateSelf() }
        }

        private fun tint(view: View) {
            val card = if (light) 0x99F2F2F7.toInt() else 0x731C1C1E.toInt()
            val raised = if (light) 0xFFDCDCE2.toInt() else 0xFF2C2C2E.toInt()
            (view.background as? GlassDrawable)?.veil = card
            view.findViewById<View?>(R.id.media_art)?.backgroundTintList = ColorStateList.valueOf(raised)
            view.findViewById<ClockView?>(R.id.clock_view)?.light = light
            for (id in PRIMARY_TEXT) view.findViewById<TextView?>(id)?.setTextColor(primaryText())
            for (id in SECONDARY_TEXT) view.findViewById<TextView?>(id)?.setTextColor(secondaryText())
        }
    }

    private fun primaryText() = if (light) 0xFF1C1C1E.toInt() else 0xFFFFFFFF.toInt()

    private fun secondaryText() = if (light) 0x993C3C43.toInt() else 0x99EBEBF5.toInt()

    /**
     * Now Playing: artwork, title, a seek bar with elapsed and remaining time, and transport
     * controls. When the track has artwork the card is filled with a blurred copy of it.
     */
    private inner class MediaWidget(private val view: View) {
        private val backdrop: ImageView = view.findViewById(R.id.media_backdrop)
        private val scrim: View = view.findViewById(R.id.media_scrim)
        private val art: ImageView = view.findViewById(R.id.media_art)
        private val source: ImageView = view.findViewById(R.id.media_source)
        private val title: TextView = view.findViewById(R.id.media_title)
        private val artist: TextView = view.findViewById(R.id.media_artist)
        private val seek: SeekBar = view.findViewById(R.id.media_seek)
        private val elapsed: TextView = view.findViewById(R.id.media_elapsed)
        private val remaining: TextView = view.findViewById(R.id.media_remaining)
        private val play: ImageButton = view.findViewById(R.id.media_play)
        private val previous: ImageButton = view.findViewById(R.id.media_previous)
        private val next: ImageButton = view.findViewById(R.id.media_next)
        private var sourcePackage: String? = null
        private var backdropArt: Bitmap? = null
        private var dragging = false

        /** Moves the seek bar once a second while something is playing and we are on screen. */
        private val ticker = object : Runnable {
            override fun run() {
                showProgress()
                if (media.playing && started && view.isAttachedToWindow) view.postDelayed(this, 1000)
            }
        }

        init {
            view.setOnClickListener {
                if (media.hasAccess) {
                    openMusic()
                } else {
                    start(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }
            }
            art.clipToOutline = true
            for (button in arrayOf(previous, play, next)) Motion.pressable(button, dim = false)
            play.setOnClickListener { if (media.active) nowPlaying.playPause() else openMusic() }
            previous.setOnClickListener { nowPlaying.previous() }
            next.setOnClickListener { nowPlaying.next() }
            seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser) showTimes(progress.toLong())
                }

                override fun onStartTrackingTouch(bar: SeekBar) {
                    dragging = true
                    // Keep the pager from treating the drag as a swipe to the app grid.
                    bar.parent.requestDisallowInterceptTouchEvent(true)
                }

                override fun onStopTrackingTouch(bar: SeekBar) {
                    dragging = false
                    nowPlaying.seekTo(bar.progress.toLong())
                }
            })
        }

        fun render(state: NowPlaying.State) {
            when {
                !state.hasAccess -> {
                    title.setText(R.string.media_no_access_title)
                    artist.setText(R.string.media_no_access_hint)
                }
                !state.active -> {
                    title.setText(R.string.media_idle_title)
                    artist.setText(R.string.media_idle_hint)
                }
                else -> {
                    title.text = state.title ?: getString(R.string.media_unknown_title)
                    artist.text = state.artist.orEmpty()
                }
            }

            // Over artwork everything is white; otherwise it follows the light or dark card.
            val overArt = state.art != null
            val primary = if (overArt) 0xFFFFFFFF.toInt() else primaryText()
            val secondary = if (overArt) 0xB3FFFFFF.toInt() else secondaryText()
            title.setTextColor(primary)
            artist.setTextColor(secondary)
            elapsed.setTextColor(secondary)
            remaining.setTextColor(secondary)

            if (state.art !== backdropArt) {
                backdropArt = state.art
                backdrop.setImageBitmap(state.art?.let(::blurred))
            }
            val backdropVisibility = if (overArt) View.VISIBLE else View.GONE
            backdrop.visibility = backdropVisibility
            scrim.visibility = backdropVisibility

            if (state.art != null) {
                art.scaleType = ImageView.ScaleType.CENTER_CROP
                art.imageTintList = null
                art.setImageBitmap(state.art)
            } else {
                art.scaleType = ImageView.ScaleType.CENTER
                art.imageTintList = ColorStateList.valueOf(secondary)
                art.setImageResource(R.drawable.ic_music)
            }

            // Small badge on the artwork showing which app is playing.
            if (state.packageName != sourcePackage) {
                sourcePackage = state.packageName
                val badge = state.packageName?.let {
                    AppLoader.packageIcon(
                        this@LauncherActivity, it, resources.getDimensionPixelSize(R.dimen.media_badge)
                    )
                }
                source.setImageBitmap(badge)
                source.visibility = if (badge == null) View.GONE else View.VISIBLE
            }

            play.setImageResource(if (state.playing) R.drawable.ic_pause else R.drawable.ic_play)
            val controls = ColorStateList.valueOf(primary)
            for (button in arrayOf(previous, play, next)) button.imageTintList = controls
            val skipAlpha = if (state.active) 1f else 0.35f
            previous.isEnabled = state.active
            next.isEnabled = state.active
            previous.alpha = skipAlpha
            next.alpha = skipAlpha

            seek.isEnabled = state.active && state.duration > 0
            seek.max = state.duration.toInt().coerceAtLeast(1)
            seek.progressTintList = controls
            seek.thumbTintList = controls
            seek.progressBackgroundTintList = ColorStateList.valueOf((primary and 0x00FFFFFF) or 0x4D000000)
            seek.thumb.mutate().alpha = if (seek.isEnabled) 255 else 0
            view.removeCallbacks(ticker)
            ticker.run()
        }

        private fun showProgress() {
            if (dragging) return
            val position = nowPlaying.position().coerceIn(0L, maxOf(0L, media.duration))
            seek.progress = position.toInt()
            showTimes(position)
        }

        private fun showTimes(position: Long) {
            val known = media.active && media.duration > 0
            elapsed.text = if (known) clockTime(position) else ""
            remaining.text = if (known) "-" + clockTime(media.duration - position) else ""
        }

        private fun clockTime(ms: Long): String {
            val seconds = maxOf(0L, ms) / 1000
            return "%d:%02d".format(seconds / 60, seconds % 60)
        }

        /** Shrinks the artwork to a few pixels and back up, leaving only soft patches of colour. */
        private fun blurred(source: Bitmap): Bitmap? = try {
            val tiny = Bitmap.createScaledBitmap(source, 6, 6, true)
            Bitmap.createScaledBitmap(tiny, 96, 96, true)
        } catch (e: RuntimeException) {
            null
        }
    }

    private inner class AppsHolder(grid: GridLayout) : RecyclerView.ViewHolder(grid) {
        private val cells = List(PER_PAGE) { i ->
            LayoutInflater.from(grid.context).inflate(R.layout.item_app, grid, false).also { cell ->
                cell.layoutParams = GridLayout.LayoutParams(
                    GridLayout.spec(i / COLUMNS, 1f), GridLayout.spec(i % COLUMNS, 1f)
                ).apply {
                    width = 0
                    height = 0
                }
                Motion.pressable(cell)
                grid.addView(cell)
            }
        }

        fun bind(page: Int) {
            cells.forEachIndexed { i, cell ->
                val app = apps.getOrNull(page * PER_PAGE + i)
                if (app == null) {
                    cell.visibility = View.INVISIBLE
                    return@forEachIndexed
                }
                cell.visibility = View.VISIBLE
                cell.findViewById<ImageView>(R.id.app_icon).setImageBitmap(app.icon)
                cell.findViewById<TextView>(R.id.app_label).text = app.label
                cell.setOnClickListener { launch(app.component, cell.findViewById(R.id.app_icon)) }
                cell.setOnLongClickListener {
                    openAppInfo(app.component)
                    true
                }
            }
        }
    }

    private companion object {
        const val COLUMNS = 5
        const val ROWS = 2
        const val PER_PAGE = COLUMNS * ROWS
        const val TYPE_DASHBOARD = 0
        const val TYPE_APPS = 1
        const val REQUEST_PHOTO = 2
        val PRIMARY_TEXT = intArrayOf(
            R.id.date_title, R.id.weather_temp, R.id.weather_condition, R.id.nav_distance, R.id.nav_instruction
        )
        val SECONDARY_TEXT = intArrayOf(R.id.date_subtitle, R.id.weather_range, R.id.nav_eta)
        const val WEATHER_INTERVAL_MS = 20 * 60 * 1000L

        /** Phone and audio links in both directions; a head unit is usually the receiving end. */
        val BLUETOOTH_PROFILES = intArrayOf(
            BluetoothProfile.HEADSET, BluetoothProfile.A2DP, 11 /* A2DP sink */, 16 /* hands-free client */
        )
        const val MAPS_PACKAGE = "com.google.android.apps.maps"
    }
}
