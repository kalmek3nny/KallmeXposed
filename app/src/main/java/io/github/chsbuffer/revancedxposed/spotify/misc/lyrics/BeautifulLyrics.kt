package io.github.chsbuffer.revancedxposed.spotify.misc.lyrics

import android.R
import android.animation.ObjectAnimator
import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadata
import android.media.session.PlaybackState
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import io.github.chsbuffer.revancedxposed.MainActivity
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.pow

class BeautifulLyrics(private val app: Application) {

    private val LYRICS_ACTIVITY = "com.spotify.lyrics.fullscreenview.page.LyricsFullscreenPageActivity"
    private val NPV_ACTIVITY = "com.spotify.nowplaying.musicinstallation.NowPlayingActivity"

    private var currentActivity: Activity? = null
    private var isNpvMode = false
    private var userInitiatedClose = false

    private var prefEnableIsland = true
    private var prefEnableBackground = true
    private var prefEnableSweep = true
    private var prefTextSize = 26f
    private var prefAnimSpeed = 5f

    private var currentTrack: String = ""
    private var currentArtist: String = ""
    private var currentAlbum: String = ""
    private var currentAlbumArt: Bitmap? = null
    private var isPlaying = false
    private var currentPositionMs: Long = 0
    private var lastUpdateTimeMs: Long = 0

    private val fluidEaseOut = PathInterpolator(0.22f, 1f, 0.36f, 1f)
    private val fluidSpring = PathInterpolator(0.34f, 1.15f, 0.3f, 1f)

    private var colorTopLeft: Int = Color.parseColor("#1A1A1A")
    private var colorTopRight: Int = Color.parseColor("#2A2A2A")
    private var colorBottomLeft: Int = Color.parseColor("#0A0A0A")
    private var colorBottomRight: Int = Color.parseColor("#111111")

    private var dynamicOutlineColor: Int = Color.parseColor("#33FFFFFF")

    private var colorActiveText: Int = Color.WHITE
    private var colorInactiveText: Int = Color.parseColor("#888888")
    private var colorActiveAdlib: Int = Color.parseColor("#B3B3B3")
    private var colorInactiveAdlib: Int = Color.parseColor("#555555")

    private var currentLyrics: List<LyricLine>? = null
    private var activeLineIndex: Int = -1
    private var fetchState: Int = 0

    private var activeContainer: FrameLayout? = null
    private var activeLyricsContainer: LinearLayout? = null
    private var activeScrollView: ScrollView? = null
    private var activeBackgroundView: FluidMeshBackgroundView? = null
    private var scrollAnimator: ObjectAnimator? = null

    private var islandTitleView: TextView? = null
    private var islandSubtitleView: TextView? = null
    private var islandArtView: ImageView? = null
    private var activeIslandPlayPauseView: View? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    private fun sendMediaEvent(context: Context, code: Int) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        audioManager.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, code))
        audioManager.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, code))
    }

    private val syncRunnable = object : Runnable {
        override fun run() {
            val activity = currentActivity ?: return
            val activityName = activity::class.java.name

            try {
                if (activityName == NPV_ACTIVITY) ensureNpvMiniCardInjected()
                else if (activityName == LYRICS_ACTIVITY) ensureFullscreenInjected()
                else return

                activeBackgroundView?.invalidate()
                if (fetchState == 1) syncLyricsToPlayback()
            } catch (e: Exception) {
                Log.e("V_SONAR", "Lyrics Sync Error: ${e.message}")
            }
            mainHandler.postDelayed(this, 16)
        }
    }

    fun init(classLoader: ClassLoader) {
        val prefs = XSharedPreferences(MainActivity.PACKAGE_NAME, MainActivity.PREF_FILE)
        prefs.makeWorldReadable()

        prefEnableIsland = prefs.getBoolean("enable_island", true)
        prefEnableBackground = prefs.getBoolean("enable_background", true)
        prefEnableSweep = prefs.getBoolean("enable_sweep", true)
        prefTextSize = prefs.getInt("text_size", 26).toFloat()
        prefAnimSpeed = prefs.getInt("anim_speed", 5).toFloat()

        if (prefs.getBoolean("enable_lyrics", true)) {
            registerLifecycleCallbacks()
            hookMediaSession(classLoader)
            hookPreventAutoClose(classLoader)

            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.action == "io.github.chsbuffer.revancedxposed.LIVE_UPDATE") {
                        prefEnableIsland = intent.getBooleanExtra("enable_island", prefEnableIsland)
                        prefEnableBackground = intent.getBooleanExtra("enable_background", prefEnableBackground)
                        prefEnableSweep = intent.getBooleanExtra("enable_sweep", prefEnableSweep)
                        prefTextSize = intent.getIntExtra("text_size", prefTextSize.toInt()).toFloat()
                        prefAnimSpeed = intent.getIntExtra("anim_speed", prefAnimSpeed.toInt()).toFloat()
                        mainHandler.post { applySettingsToUI() }
                    }
                }
            }

            val filter = IntentFilter("io.github.chsbuffer.revancedxposed.LIVE_UPDATE")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                app.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                app.registerReceiver(receiver, filter)
            }
        }
    }

    private fun hookPreventAutoClose(classLoader: ClassLoader) {
        try {
            val lyricsActivityClass = XposedHelpers.findClass(LYRICS_ACTIVITY, classLoader)
            XposedBridge.hookAllMethods(lyricsActivityClass, "dispatchKeyEvent", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val event = param.args[0] as android.view.KeyEvent
                    if (event.keyCode == android.view.KeyEvent.KEYCODE_BACK && event.action == android.view.KeyEvent.ACTION_UP) {
                        userInitiatedClose = true
                    }
                }
            })
            XposedBridge.hookAllMethods(lyricsActivityClass, "onBackPressed", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) { userInitiatedClose = true }
            })
            XposedBridge.hookAllMethods(lyricsActivityClass, "finish", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!userInitiatedClose) param.result = null
                }
            })
        } catch (e: Exception) {}
    }

    private fun applySettingsToUI() {
        activeBackgroundView?.visibility = if (prefEnableBackground) View.VISIBLE else View.INVISIBLE
        if (fetchState == 1) buildLyricsUI()
    }

    private fun hookMediaSession(classLoader: ClassLoader) {
        try {
            val mediaSessionClass = XposedHelpers.findClass("android.media.session.MediaSession", classLoader)

            XposedBridge.hookAllMethods(mediaSessionClass, "setMetadata", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val metadata = param.args[0] as? MediaMetadata ?: return
                    val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE) ?: return
                    val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""
                    val album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""

                    val bitmap = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                        ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
                        ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)

                    if (bitmap != null && bitmap != currentAlbumArt) {
                        currentAlbumArt = bitmap
                        extractAppleMusicColors(bitmap)
                        mainHandler.post { updateIslandUI() }
                    }

                    if (title != currentTrack || artist != currentArtist) {
                        currentTrack = title; currentArtist = artist; currentAlbum = album
                        currentPositionMs = 0; lastUpdateTimeMs = SystemClock.elapsedRealtime()

                        mainHandler.post { updateIslandUI() }
                        fetchLyrics(title, artist)
                    }
                }
            })

            XposedBridge.hookAllMethods(mediaSessionClass, "setPlaybackState", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val state = param.args[0] as? PlaybackState ?: return
                    val wasPlaying = isPlaying
                    isPlaying = state.state == PlaybackState.STATE_PLAYING
                    currentPositionMs = state.position
                    lastUpdateTimeMs = SystemClock.elapsedRealtime()

                    if (wasPlaying != isPlaying) mainHandler.post { activeIslandPlayPauseView?.invalidate() }
                }
            })
        } catch (e: Exception) {}
    }

    private fun extractAppleMusicColors(bitmap: Bitmap) {
        try {
            val thumb = Bitmap.createScaledBitmap(bitmap, 3, 3, true)
            val pixels = IntArray(9)
            thumb.getPixels(pixels, 0, 3, 0, 0, 3, 3)

            var rSum = 0; var gSum = 0; var bSum = 0
            for (p in pixels) { rSum += Color.red(p); gSum += Color.green(p); bSum += Color.blue(p) }
            val avgColor = Color.rgb(rSum / 9, gSum / 9, bSum / 9)

            var maxDist = -1
            var accentColor = avgColor
            for (p in pixels) {
                val dist = Math.abs(Color.red(p) - Color.red(avgColor)) + Math.abs(Color.green(p) - Color.green(avgColor)) + Math.abs(Color.blue(p) - Color.blue(avgColor))
                if (dist > maxDist) { maxDist = dist; accentColor = p }
            }
            thumb.recycle()

            val lum = 0.299 * Color.red(avgColor) + 0.587 * Color.green(avgColor) + 0.114 * Color.blue(avgColor)
            val outHsv = FloatArray(3)
            Color.colorToHSV(accentColor, outHsv)

            val newOutlineColor = if (lum > 127) {
                outHsv[1] = (outHsv[1] * 1.5f).coerceAtMost(1f)
                outHsv[2] = 0.3f
                Color.HSVToColor(200, outHsv)
            } else {
                outHsv[1] = (outHsv[1] * 0.6f).coerceAtMost(1f)
                outHsv[2] = 0.95f
                Color.HSVToColor(220, outHsv)
            }

            val hsv = FloatArray(3)
            Color.colorToHSV(avgColor, hsv)

            hsv[1] = 0.05f; hsv[2] = 1.0f
            colorActiveText = Color.HSVToColor(hsv)
            hsv[1] = 0.15f; hsv[2] = 0.75f
            colorInactiveText = Color.HSVToColor(hsv)
            hsv[1] = 0.10f; hsv[2] = 0.85f
            colorActiveAdlib = Color.HSVToColor(hsv)
            hsv[1] = 0.20f; hsv[2] = 0.60f
            colorInactiveAdlib = Color.HSVToColor(hsv)

            val baseHue = hsv[0]
            val baseSat = hsv[1].coerceIn(0.5f, 1f)

            hsv[0] = baseHue; hsv[1] = baseSat; hsv[2] = 0.90f; val c1 = Color.HSVToColor(hsv)
            hsv[0] = (baseHue + 40f) % 360f; hsv[1] = baseSat * 0.9f; hsv[2] = 0.80f; val c2 = Color.HSVToColor(hsv)
            hsv[0] = (baseHue - 30f + 360f) % 360f; hsv[1] = baseSat; hsv[2] = 0.70f; val c3 = Color.HSVToColor(hsv)
            hsv[0] = (baseHue + 15f) % 360f; hsv[1] = baseSat * 1.0f; hsv[2] = 0.60f; val c4 = Color.HSVToColor(hsv)

            mainHandler.post {
                activeBackgroundView?.animateColorsTo(c1, c2, c3, c4)
                colorTopLeft = c1; colorTopRight = c2; colorBottomLeft = c3; colorBottomRight = c4

                val aOut = android.animation.ValueAnimator.ofArgb(dynamicOutlineColor, newOutlineColor).apply {
                    addUpdateListener {
                        dynamicOutlineColor = it.animatedValue as Int

                        val backBtn = activeContainer?.findViewWithTag<FrameLayout>("BACK_BTN")
                        (backBtn?.background as? GradientDrawable)?.setStroke(3, dynamicOutlineColor)

                        val island = activeContainer?.findViewWithTag<LinearLayout>("ISLAND_TAB")
                        (island?.background as? GradientDrawable)?.setStroke(3, dynamicOutlineColor)
                    }
                }
                aOut.duration = 1500
                aOut.interpolator = DecelerateInterpolator(1.5f)
                aOut.start()
            }
        } catch (e: Exception) {}
    }

    private fun fetchLyrics(track: String, artist: String) {
        fetchState = 0
        currentLyrics?.forEach { it.detachView() }
        currentLyrics = null
        activeLineIndex = -1

        mainHandler.post {
            activeContainer?.visibility = View.VISIBLE
            updateUIState("Loading $track...")
        }

        MusixmatchAPI.getLyrics(app, track, artist) { lines ->
            mainHandler.post {
                if (lines == null || lines.isEmpty()) {
                    fetchState = 2
                    updateUIState("No lyrics available")
                } else {
                    fetchState = 1
                    currentLyrics = lines
                    buildLyricsUI()
                }
            }
        }
    }

    private fun registerLifecycleCallbacks() {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                currentActivity = activity
                val activityName = activity::class.java.name

                if (activityName == LYRICS_ACTIVITY) {
                    userInitiatedClose = false
                    isNpvMode = false
                    mainHandler.removeCallbacks(syncRunnable)
                    mainHandler.post(syncRunnable)
                } else if (activityName == NPV_ACTIVITY) {
                    isNpvMode = true
                    mainHandler.removeCallbacks(syncRunnable)
                    mainHandler.post(syncRunnable)
                } else {
                    mainHandler.removeCallbacks(syncRunnable)
                }
            }

            override fun onActivityPaused(activity: Activity) {
                if (activity == currentActivity) {
                    mainHandler.removeCallbacks(syncRunnable)
                    currentActivity = null
                }
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {
                currentLyrics?.forEach { it.detachView() }
            }
        })
    }

    private fun ensureNpvMiniCardInjected() {
        val activity = currentActivity ?: return
        val root = activity.findViewById<ViewGroup>(R.id.content) ?: return
        val widgetsId = activity.resources.getIdentifier("widgets_container", "id", activity.packageName).takeIf { it != 0 } ?: activity.resources.getIdentifier("widgets_container", "id", "com.spotify.music")
        val widgetsContainer = activity.findViewById<ViewGroup>(widgetsId) ?: return
        val verticalLayout = widgetsContainer.getChildAt(0) as? ViewGroup ?: return

        var cardContainer = verticalLayout.findViewWithTag<FrameLayout>("BEAUTIFUL_LYRICS_MINI_CARD")

        if (cardContainer == null) injectNpvMiniCard(activity, verticalLayout)

        for (i in 0 until verticalLayout.childCount) {
            val child = verticalLayout.getChildAt(i)
            if (child.tag == "BEAUTIFUL_LYRICS_MINI_CARD") continue
            if (child.javaClass.simpleName.contains("ComposeView") && i <= 2) {
                if (child.visibility != View.GONE) {
                    child.visibility = View.GONE
                    child.layoutParams?.height = 0
                }
            }
        }
    }

    private fun injectNpvMiniCard(activity: Activity, verticalLayout: ViewGroup) {
        val cardHeight = (activity.resources.displayMetrics.heightPixels * 0.55).toInt()

        val cardContainer = FrameLayout(activity).apply {
            tag = "BEAUTIFUL_LYRICS_MINI_CARD"
            layoutParams = LinearLayout.LayoutParams(-1, cardHeight).apply { setMargins(20, 30, 20, 30) }
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) { outline.setRoundRect(0, 0, view.width, view.height, 45f) }
            }
        }

        activeBackgroundView = FluidMeshBackgroundView(activity).apply {
            layoutParams = FrameLayout.LayoutParams(-1, -1)
            visibility = if (prefEnableBackground) View.VISIBLE else View.INVISIBLE
            setColors(colorTopLeft, colorTopRight, colorBottomLeft, colorBottomRight)
        }
        cardContainer.addView(activeBackgroundView)

        val titleText = TextView(activity).apply {
            text = "Lyrics preview"
            setTextColor(Color.WHITE)
            textSize = 16f
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            layoutParams = FrameLayout.LayoutParams(-2, -2).apply { gravity = Gravity.TOP or Gravity.START; setMargins(40, 45, 0, 0) }
        }
        cardContainer.addView(titleText)

        val scrollWrapper = FrameLayout(activity).apply { layoutParams = FrameLayout.LayoutParams(-1, -1).apply { setMargins(0, 150, 0, 180) } }

        activeScrollView = ScrollView(activity).apply {
            layoutParams = FrameLayout.LayoutParams(-1, -1)
            isFillViewport = true
            clipToPadding = false
            clipChildren = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setPadding(0, cardHeight / 4, 0, cardHeight)
            setOnTouchListener { v, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> v.parent.requestDisallowInterceptTouchEvent(true)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.parent.requestDisallowInterceptTouchEvent(false)
                }
                false
            }
        }

        activeLyricsContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START
            clipChildren = false
            clipToPadding = false
            setPadding(40, 0, 40, 0)
        }

        activeScrollView?.addView(activeLyricsContainer)
        scrollWrapper.addView(activeScrollView)
        cardContainer.addView(scrollWrapper)

        val showLyricsBtn = TextView(activity).apply {
            text = "Show lyrics"
            setTextColor(Color.BLACK)
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setPadding(45, 20, 45, 20)
            background = GradientDrawable().apply { cornerRadius = 100f; setColor(Color.WHITE) }
            layoutParams = FrameLayout.LayoutParams(-2, -2).apply { gravity = Gravity.BOTTOM or Gravity.START; setMargins(40, 0, 0, 45) }
            setOnClickListener {
                try {
                    val intent = Intent()
                    intent.setClassName(activity, LYRICS_ACTIVITY)
                    activity.startActivity(intent)
                } catch (e: Exception) {}
            }
        }
        cardContainer.addView(showLyricsBtn)

        verticalLayout.addView(cardContainer, 0)
        activeContainer = cardContainer

        if (fetchState == 1 && currentLyrics != null) buildLyricsUI()
        else if (fetchState == 0) updateUIState(if (currentTrack.isNotEmpty()) "Loading $currentTrack..." else "Play a song...")
        else updateUIState("No lyrics available")
    }

    private fun ensureFullscreenInjected() {
        val activity = currentActivity ?: return
        val root = activity.window.decorView as ViewGroup

        var customView = root.findViewWithTag<FrameLayout>("BEAUTIFUL_LYRICS_FULLSCREEN")
        if (customView == null) {
            injectFullscreenOverlay(activity, root)
            customView = root.findViewWithTag<FrameLayout>("BEAUTIFUL_LYRICS_FULLSCREEN")
        }

        customView?.let {
            if (root.getChildAt(root.childCount - 1) != it) it.bringToFront()
            it.translationZ = 10000f; it.elevation = 10000f
        }
    }

    private fun injectFullscreenOverlay(activity: Activity, root: ViewGroup) {
        val container = FrameLayout(activity).apply {
            tag = "BEAUTIFUL_LYRICS_FULLSCREEN"
            layoutParams = FrameLayout.LayoutParams(-1, -1)
            elevation = 10000f; translationZ = 10000f
            isClickable = true; isFocusable = true
            setBackgroundColor(Color.parseColor("#050505"))
        }

        activeBackgroundView = FluidMeshBackgroundView(activity).apply {
            layoutParams = FrameLayout.LayoutParams(-1, -1)
            visibility = if (prefEnableBackground) View.VISIBLE else View.INVISIBLE
            setColors(colorTopLeft, colorTopRight, colorBottomLeft, colorBottomRight)
        }
        container.addView(activeBackgroundView)

        activeScrollView = ScrollView(activity).apply {
            layoutParams = FrameLayout.LayoutParams(-1, -1)
            isFillViewport = true; clipToPadding = false; clipChildren = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setPadding(0, 0, 0, activity.resources.displayMetrics.heightPixels / 2)
        }

        activeLyricsContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START
            clipChildren = false; clipToPadding = false
            setPadding(80, activity.resources.displayMetrics.heightPixels / 3, 80, 0)
        }

        activeScrollView?.addView(activeLyricsContainer)
        container.addView(activeScrollView)

        val topFade = View(activity).apply {
            layoutParams = FrameLayout.LayoutParams(-1, 550).apply { gravity = Gravity.TOP }
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.parseColor("#CC000000"), Color.TRANSPARENT))
        }
        container.addView(topFade)

        val bottomFade = View(activity).apply {
            layoutParams = FrameLayout.LayoutParams(-1, 500).apply { gravity = Gravity.BOTTOM }
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(Color.parseColor("#CC000000"), Color.TRANSPARENT))
        }
        container.addView(bottomFade)

        val backBtn = FrameLayout(activity).apply {
            tag = "BACK_BTN"
            layoutParams = FrameLayout.LayoutParams(110, 110).apply { gravity = Gravity.TOP or Gravity.START; setMargins(60, 140, 0, 0) }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                // 🎯 DARKER & RICHER GLASS (65% Opacity)
                setColor(Color.parseColor("#A6000000"))
                setStroke(3, dynamicOutlineColor)
            }
            addView(object : View(activity) {
                val p = Paint().apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 6f; strokeCap = Paint.Cap.ROUND; isAntiAlias = true }
                override fun onDraw(canvas: Canvas) {
                    val cx = width / 2f; val cy = height / 2f; val s = 18f
                    canvas.drawLine(cx - s, cy - s/2, cx, cy + s/2, p)
                    canvas.drawLine(cx, cy + s/2, cx + s, cy - s/2, p)
                }
            })
            setOnClickListener { userInitiatedClose = true; activity.finish() }
        }
        container.addView(backBtn)

        val island = LinearLayout(activity).apply {
            tag = "ISLAND_TAB"
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = FrameLayout.LayoutParams(-2, -2).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; topMargin = 125 }
            setPadding(35, 30, 60, 30)
            visibility = if (prefEnableIsland) View.VISIBLE else View.GONE
            background = GradientDrawable().apply {
                cornerRadius = 100f
                // 🎯 DARKER & RICHER GLASS (65% Opacity)
                setColor(Color.parseColor("#A6000000"))
                setStroke(3, dynamicOutlineColor)
            }
        }

        islandArtView = ImageView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(130, 130).apply { marginEnd = 30 }
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() { override fun getOutline(view: View, outline: Outline) { outline.setRoundRect(0, 0, view.width, view.height, 25f) } }
        }
        island.addView(islandArtView)

        val textGroup = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }
        islandTitleView = TextView(activity).apply { setTextColor(Color.WHITE); textSize = 15f; typeface = Typeface.create("sans-serif-medium", Typeface.BOLD); maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        textGroup.addView(islandTitleView)
        islandSubtitleView = TextView(activity).apply { setTextColor(Color.parseColor("#CCCCCC")); textSize = 12f; typeface = Typeface.create("sans-serif", Typeface.NORMAL); maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        textGroup.addView(islandSubtitleView)
        island.addView(textGroup)

        val controlsGroup = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginStart = 20 } }

        val prevBtn = object : View(activity) {
            val p = Paint().apply { color = Color.WHITE; style = Paint.Style.FILL; isAntiAlias = true; pathEffect = CornerPathEffect(2f) }
            override fun onDraw(canvas: Canvas) {
                val cx = width / 2f; val cy = height / 2f; val w = 22f; val h = 26f
                val path = Path()
                path.moveTo(cx + w/2, cy - h/2); path.lineTo(cx, cy); path.lineTo(cx + w/2, cy + h/2); path.close()
                path.moveTo(cx, cy - h/2); path.lineTo(cx - w/2, cy); path.lineTo(cx, cy + h/2); path.close()
                canvas.drawPath(path, p)
                canvas.drawRect(cx - w/2 - 5f, cy - h/2, cx - w/2, cy + h/2, p)
            }
        }.apply { layoutParams = LinearLayout.LayoutParams(60, 60).apply { marginEnd = 25 }; setOnClickListener { sendMediaEvent(activity, android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS) } }

        activeIslandPlayPauseView = object : View(activity) {
            val p = Paint().apply { color = Color.WHITE; style = Paint.Style.FILL; isAntiAlias = true; pathEffect = CornerPathEffect(4f) }
            override fun onDraw(canvas: Canvas) {
                val cx = width / 2f; val cy = height / 2f
                if (isPlaying) {
                    val bw = 8f; val bh = 32f; val space = 6f
                    canvas.drawRoundRect(cx - space - bw/2, cy - bh/2, cx - space + bw/2, cy + bh/2, 4f, 4f, p)
                    canvas.drawRoundRect(cx + space - bw/2, cy - bh/2, cx + space + bw/2, cy + bh/2, 4f, 4f, p)
                } else {
                    val w = 30f; val h = 34f
                    val path = Path()
                    p.strokeJoin = Paint.Join.ROUND; p.strokeCap = Paint.Cap.ROUND
                    path.moveTo(cx - w/2 + 4f, cy - h/2); path.lineTo(cx + w/2, cy); path.lineTo(cx - w/2 + 4f, cy + h/2); path.close()
                    canvas.drawPath(path, p)
                }
            }
        }.apply { layoutParams = LinearLayout.LayoutParams(70, 70).apply { marginEnd = 25 }; setOnClickListener { sendMediaEvent(activity, android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) } }

        val nextBtn = object : View(activity) {
            val p = Paint().apply { color = Color.WHITE; style = Paint.Style.FILL; isAntiAlias = true; pathEffect = CornerPathEffect(2f) }
            override fun onDraw(canvas: Canvas) {
                val cx = width / 2f; val cy = height / 2f; val w = 22f; val h = 26f
                val path = Path()
                path.moveTo(cx - w/2, cy - h/2); path.lineTo(cx, cy); path.lineTo(cx - w/2, cy + h/2); path.close()
                path.moveTo(cx, cy - h/2); path.lineTo(cx + w/2, cy); path.lineTo(cx, cy + h/2); path.close()
                canvas.drawPath(path, p)
                canvas.drawRect(cx + w/2, cy - h/2, cx + w/2 + 5f, cy + h/2, p)
            }
        }.apply { layoutParams = LinearLayout.LayoutParams(60, 60); setOnClickListener { sendMediaEvent(activity, android.view.KeyEvent.KEYCODE_MEDIA_NEXT) } }

        controlsGroup.addView(prevBtn)
        controlsGroup.addView(activeIslandPlayPauseView)
        controlsGroup.addView(nextBtn)
        island.addView(controlsGroup)
        container.addView(island)

        root.addView(container)
        activeContainer = container

        updateIslandUI()

        when (fetchState) {
            0 -> { activeContainer?.visibility = View.VISIBLE; updateUIState(if (currentTrack.isNotEmpty()) "Loading $currentTrack..." else "Play a song...") }
            1 -> { activeContainer?.visibility = View.VISIBLE; if (currentLyrics != null) buildLyricsUI() }
            2 -> { activeContainer?.visibility = View.VISIBLE; updateUIState("No lyrics available") }
        }
    }

    private fun updateIslandUI() {
        if (isNpvMode) return

        val titleView = islandTitleView ?: return
        val subtitleView = islandSubtitleView ?: return
        val artView = islandArtView ?: return

        if (titleView.text == currentTrack) {
            if (currentAlbumArt != null) artView.setImageBitmap(currentAlbumArt)
            return
        }

        if (titleView.text.isEmpty()) {
            titleView.text = currentTrack
            subtitleView.text = "$currentArtist • $currentAlbum"
            if (currentAlbumArt != null) artView.setImageBitmap(currentAlbumArt)
            return
        }

        val outDuration = 200L
        val inDuration = 450L

        val animateOut = { v: View -> v.animate().alpha(0f).translationY(-25f).setDuration(outDuration).start() }
        animateOut(titleView); animateOut(subtitleView); animateOut(artView)

        mainHandler.postDelayed({
            titleView.text = currentTrack
            subtitleView.text = "$currentArtist • $currentAlbum"
            if (currentAlbumArt != null) artView.setImageBitmap(currentAlbumArt)

            val animateIn = { v: View ->
                v.translationY = 35f
                v.animate().alpha(1f).translationY(0f).setDuration(inDuration).setInterpolator(fluidSpring).start()
            }
            animateIn(titleView); animateIn(subtitleView); animateIn(artView)
        }, outDuration)
    }

    private fun updateUIState(message: String) {
        val container = activeLyricsContainer ?: return
        activeScrollView?.post { activeScrollView?.scrollTo(0, 0) }

        currentLyrics?.forEach { it.detachView() }
        container.removeAllViews()
        container.alpha = 1f
        container.translationY = 0f

        val tv = TextView(container.context).apply {
            text = message
            textSize = if (isNpvMode) 20f else 24f
            setTextColor(Color.parseColor("#B3B3B3"))
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = if (isNpvMode) 100 else 300 }
        }
        container.addView(tv)
    }

    private fun buildLyricsUI() {
        val container = activeLyricsContainer ?: return
        val lyrics = currentLyrics ?: return

        currentLyrics?.forEach { it.detachView() }
        container.removeAllViews()
        container.alpha = 1f
        container.translationY = 0f

        val lineEndings = lyrics.map { line ->
            val lastWord = line.text.trim().split(Regex("\\s+")).lastOrNull()?.replace(Regex("[^a-zA-Z]"), "")?.lowercase() ?: ""
            if (lastWord.length >= 2) lastWord.takeLast(2) else lastWord
        }

        for ((index, line) in lyrics.withIndex()) {
            val cleanText = line.text.trim().replace(Regex("^[,\\-\\s]+|[,\\-\\s]+$"), "")

            val isAdlib = cleanText.startsWith("(") && cleanText.endsWith(")")
            val baseSize = if (isNpvMode) prefTextSize * 0.80f else prefTextSize

            val isNextAlsoAdlib = index + 1 < lyrics.size && lyrics[index + 1].text.trim().startsWith("(")
            val calculatedBottomMargin = if (isAdlib && isNextAlsoAdlib) 15 else (if (isNpvMode) 35 else 55)

            var isRhyme = false
            val currentEnding = lineEndings[index]
            if (currentEnding.isNotEmpty() && currentEnding.length >= 2 && !isAdlib) {
                val match1 = if (index >= 1) lineEndings[index - 1] == currentEnding else false
                val match2 = if (index >= 2) lineEndings[index - 2] == currentEnding else false
                isRhyme = match1 || match2
            }

            val tv = SweepTextView(container.context, isAdlib, isRhyme).apply {
                text = cleanText
                textSize = if (isAdlib) baseSize * 0.75f else baseSize
                gravity = if (isAdlib) Gravity.END else Gravity.START
                typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
                alpha = 0.35f
                scaleX = 0.95f
                scaleY = 0.95f
                translationY = 15f
                pivotX = if (isAdlib) resources.displayMetrics.widthPixels.toFloat() else 0f
                setLineSpacing(0f, 1.2f)

                setPadding(0, 20, 0, 60)
                setShadowLayer(25f, 0f, 15f, Color.parseColor("#66000000"))

                layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                    bottomMargin = calculatedBottomMargin - 50
                }
                setProgress(0f)
            }
            line.view = tv
            container.addView(tv)
        }

        activeScrollView?.post { activeScrollView?.scrollTo(0, 0) }
        activeLineIndex = -1
        syncLyricsToPlayback()
    }

    private fun syncLyricsToPlayback() {
        val lyrics = currentLyrics ?: return
        val scroll = activeScrollView ?: return

        var estimatedTime = currentPositionMs
        if (isPlaying) estimatedTime += (SystemClock.elapsedRealtime() - lastUpdateTimeMs)

        var newActiveIndex = -1
        for (i in lyrics.indices) {
            if (estimatedTime >= lyrics[i].timeMs) newActiveIndex = i else break
        }

        if (newActiveIndex != -1) {
            val activeView = lyrics[newActiveIndex].view as? SweepTextView
            val currentLineTime = lyrics[newActiveIndex].timeMs

            val nextLineTime = if (newActiveIndex + 1 < lyrics.size) lyrics[newActiveIndex + 1].timeMs else currentLineTime + 5000L
            val gapToNextLine = nextLineTime - currentLineTime

            val maxVocalDuration = (lyrics[newActiveIndex].text.length * 100L) + 1200L
            val sweepDuration = Math.min(gapToNextLine - 150L, maxVocalDuration).coerceAtLeast(300L)

            val timeElapsed = estimatedTime - currentLineTime
            val linearProgress = (timeElapsed.toFloat() / sweepDuration.toFloat()).coerceIn(0f, 1f)

            val sweepProgress = 1f - (1f - linearProgress).pow(2.5f)

            activeView?.setProgress(sweepProgress)

            val baseMaxSwell = if (activeView?.isAdlib == true) 0.015f else 0.025f
            val finalMaxSwell = if (activeView?.isRhyme == true) baseMaxSwell * 1.6f else baseMaxSwell

            val swellScale = 1.0f + (finalMaxSwell * sweepProgress)
            activeView?.scaleX = swellScale
            activeView?.scaleY = swellScale
        }

        if (newActiveIndex != activeLineIndex && newActiveIndex != -1) {
            activeLineIndex = newActiveIndex
            for (i in lyrics.indices) {
                val tv = lyrics[i].view as? SweepTextView ?: continue

                if (i < activeLineIndex) {
                    tv.setProgress(1f)
                    tv.animate().scaleX(0.95f).scaleY(0.95f).alpha(0.6f).translationY(0f)
                        .setDuration(450).setInterpolator(fluidEaseOut).start()
                } else if (i == activeLineIndex) {
                    tv.animate().alpha(1.0f).translationY(0f)
                        .setDuration(400).setInterpolator(fluidEaseOut).start()

                    tv.post {
                        val offset = if (isNpvMode) (scroll.height / 4) else (scroll.height / 3)
                        val targetScrollY = tv.top - offset + (tv.height / 2)

                        scrollAnimator?.cancel()
                        scrollAnimator = ObjectAnimator.ofInt(scroll, "scrollY", targetScrollY).apply {
                            duration = 750
                            interpolator = fluidSpring
                            start()
                        }
                    }
                } else {
                    tv.setProgress(0f)
                    tv.animate().scaleX(0.92f).scaleY(0.92f).alpha(0.3f).translationY(15f)
                        .setDuration(500).setInterpolator(fluidEaseOut).start()
                }
            }
        }
    }

    inner class SweepTextView(context: Context, val isAdlib: Boolean = false, val isRhyme: Boolean = false) : TextView(context) {
        private var sweepProgress = 0f
        private val colorActive get() = if (isAdlib) colorActiveAdlib else colorActiveText
        private val colorInactive get() = if (isAdlib) colorInactiveAdlib else colorInactiveText

        fun setProgress(p: Float) {
            if (sweepProgress == p) return
            sweepProgress = p
            if (p <= 0.01f || !prefEnableSweep) {
                if (currentTextColor != colorInactive) setTextColor(colorInactive)
            } else {
                if (currentTextColor != colorActive) setTextColor(colorActive)
            }
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            if (!prefEnableSweep || sweepProgress <= 0.01f || sweepProgress >= 0.99f) {
                paint.shader = null
                super.onDraw(canvas)
                return
            }

            val textLayout = layout ?: run { super.onDraw(canvas); return }

            canvas.save()
            canvas.translate(totalPaddingLeft.toFloat(), totalPaddingTop.toFloat())

            val originalColor = paint.color
            val originalShader = paint.shader
            val sRad = shadowRadius
            val sDx = shadowDx
            val sDy = shadowDy
            val sCol = shadowColor

            paint.shader = null
            paint.color = Color.argb(1, 0, 0, 0)
            textLayout.draw(canvas)

            paint.clearShadowLayer()

            val totalWidth = (0 until textLayout.lineCount).sumOf { textLayout.getLineWidth(it).toDouble() }.toFloat()
            val blur = width * 0.3f
            var widthSoFar = 0f

            for (i in 0 until textLayout.lineCount) {
                val w = textLayout.getLineWidth(i)
                if (w <= 0f) continue

                val lineStartP = widthSoFar / totalWidth
                val lineEndP = (widthSoFar + w) / totalWidth

                val lineProgress = when {
                    sweepProgress <= lineStartP -> 0f
                    sweepProgress >= lineEndP -> 1f
                    else -> (sweepProgress - lineStartP) / (lineEndP - lineStartP)
                }

                if (lineProgress <= 0.01f) {
                    paint.shader = null
                    paint.color = colorInactive
                } else if (lineProgress >= 0.99f) {
                    paint.shader = null
                    paint.color = colorActive
                } else {
                    paint.color = Color.WHITE

                    val sweepCenter = lineProgress * (w + blur) - (blur * 0.7f)
                    val lineLeft = textLayout.getLineLeft(i)

                    paint.shader = LinearGradient(
                        lineLeft + sweepCenter - (blur * 0.8f), 0f,
                        lineLeft + sweepCenter + (blur * 0.2f), 0f,
                        intArrayOf(colorActive, Color.WHITE, colorInactive),
                        floatArrayOf(0.0f, 0.90f, 1.0f),
                        Shader.TileMode.CLAMP
                    )
                }

                canvas.save()
                canvas.clipRect(0f, textLayout.getLineTop(i).toFloat(), width.toFloat(), textLayout.getLineBottom(i).toFloat())
                textLayout.draw(canvas)
                canvas.restore()

                widthSoFar += w
            }

            canvas.restore()

            paint.shader = originalShader
            paint.color = originalColor
            paint.setShadowLayer(sRad, sDx, sDy, sCol)
        }
    }

    inner class FluidMeshBackgroundView(context: Context) : View(context) {
        private var c1 = Color.BLACK; private var c2 = Color.BLACK
        private var c3 = Color.BLACK; private var c4 = Color.BLACK

        private val paint1 = Paint(Paint.ANTI_ALIAS_FLAG)
        private val paint2 = Paint(Paint.ANTI_ALIAS_FLAG)
        private val paint3 = Paint(Paint.ANTI_ALIAS_FLAG)
        private val paint4 = Paint(Paint.ANTI_ALIAS_FLAG)

        private fun transparentColor(c: Int) = Color.argb(0, Color.red(c), Color.green(c), Color.blue(c))

        // 🎯 2x MORE TRANSPARENT ORBS: Alpha dropped to 50 (~20% opacity)
        private fun applyAlpha(c: Int, alpha: Int) = Color.argb(alpha, Color.red(c), Color.green(c), Color.blue(c))

        fun setColors(topL: Int, topR: Int, botL: Int, botR: Int) {
            c1 = topL; c2 = topR; c3 = botL; c4 = botR; invalidate()
        }

        fun animateColorsTo(topL: Int, topR: Int, botL: Int, botR: Int) {
            val a1 = android.animation.ValueAnimator.ofArgb(c1, topL).apply { addUpdateListener { c1 = it.animatedValue as Int; invalidate() } }
            val a2 = android.animation.ValueAnimator.ofArgb(c2, topR).apply { addUpdateListener { c2 = it.animatedValue as Int; invalidate() } }
            val a3 = android.animation.ValueAnimator.ofArgb(c3, botL).apply { addUpdateListener { c3 = it.animatedValue as Int; invalidate() } }
            val a4 = android.animation.ValueAnimator.ofArgb(c4, botR).apply { addUpdateListener { c4 = it.animatedValue as Int; invalidate() } }

            android.animation.AnimatorSet().apply {
                playTogether(a1, a2, a3, a4)
                duration = 2000
                interpolator = fluidEaseOut
                start()
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val speedFactor = prefAnimSpeed / 5.0
            val time = SystemClock.elapsedRealtime() * speedFactor

            canvas.drawColor(Color.parseColor("#050505"))

            val w = width.toFloat(); val h = height.toFloat()
            val rad = Math.max(w, h) * 1.5f

            val x1 = w * 0.3f + (w * 0.5f * Math.sin(time / 6100.0)).toFloat()
            val y1 = h * 0.2f + (h * 0.4f * Math.cos(time / 4700.0)).toFloat()

            val x2 = w * 0.7f + (w * 0.4f * Math.cos(time / 7300.0)).toFloat()
            val y2 = h * 0.3f + (h * 0.5f * Math.sin(time / 5900.0)).toFloat()

            val x3 = w * 0.4f + (w * 0.5f * Math.sin(time / 5300.0 + Math.PI)).toFloat()
            val y3 = h * 0.8f + (h * 0.4f * Math.cos(time / 6700.0)).toFloat()

            val x4 = w * 0.8f + (w * 0.4f * Math.cos(time / 8300.0 + Math.PI)).toFloat()
            val y4 = h * 0.7f + (h * 0.5f * Math.sin(time / 4100.0)).toFloat()

            paint1.shader = RadialGradient(x1, y1, rad, applyAlpha(c1, 50), transparentColor(c1), Shader.TileMode.CLAMP)
            paint2.shader = RadialGradient(x2, y2, rad, applyAlpha(c2, 50), transparentColor(c2), Shader.TileMode.CLAMP)
            paint3.shader = RadialGradient(x3, y3, rad, applyAlpha(c3, 50), transparentColor(c3), Shader.TileMode.CLAMP)
            paint4.shader = RadialGradient(x4, y4, rad, applyAlpha(c4, 50), transparentColor(c4), Shader.TileMode.CLAMP)

            canvas.drawCircle(x1, y1, rad, paint1)
            canvas.drawCircle(x2, y2, rad, paint2)
            canvas.drawCircle(x3, y3, rad, paint3)
            canvas.drawCircle(x4, y4, rad, paint4)
        }
    }
}