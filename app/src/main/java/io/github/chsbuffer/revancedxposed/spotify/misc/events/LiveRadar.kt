package io.github.chsbuffer.revancedxposed.spotify.misc.events

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadata
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import io.github.chsbuffer.revancedxposed.MainActivity

class LiveRadar(private val app: Application) {

    private val NPV_ACTIVITY = "com.spotify.nowplaying.musicinstallation.NowPlayingActivity"
    private var currentActivity: Activity? = null

    private var currentArtistString: String = ""
    private var currentEvents: List<RadarEvent> = emptyList()
    private var fetchState = 0 // 0=Loading, 1=Found, 2=No Tours

    // Color Variables
    private var albumColorTop: Int = Color.parseColor("#2C2C2C")
    private var albumColorBottom: Int = Color.parseColor("#0A0A0A")

    private var activeContainer: LinearLayout? = null
    private var activeScrollView: ScrollView? = null
    private var activeBackgroundView: AnimatedBackgroundView? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val syncRunnable = object : Runnable {
        override fun run() {
            if (currentActivity?.javaClass?.name == NPV_ACTIVITY) {
                ensureCardInjected()
                activeBackgroundView?.invalidate()
            }
            mainHandler.postDelayed(this, 16)
        }
    }

    fun init(classLoader: ClassLoader) {
        val prefs = XSharedPreferences(MainActivity.PACKAGE_NAME, MainActivity.PREF_FILE)
        prefs.makeWorldReadable()
        if (!prefs.getBoolean("enable_live_radar", true)) return

        registerLifecycleCallbacks()
        hookMediaSession(classLoader)
    }

    private fun hookMediaSession(classLoader: ClassLoader) {
        try {
            val mediaSessionClass = XposedHelpers.findClass("android.media.session.MediaSession", classLoader)
            XposedBridge.hookAllMethods(mediaSessionClass, "setMetadata", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val metadata = param.args[0] as? MediaMetadata ?: return
                    val artistString = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""

                    val bitmap = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                        ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
                        ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)

                    if (bitmap != null) extractAppleMusicColors(bitmap)

                    if (artistString != currentArtistString && artistString.isNotEmpty()) {
                        currentArtistString = artistString
                        fetchState = 0
                        mainHandler.post { buildUI() }

                        // Split by comma and ampersand to get individual artists!
                        val artists = artistString.split(",", "&").map { it.trim() }.filter { it.isNotEmpty() }

                        LiveRadarAPI.fetchTourDates(artists) { events ->
                            mainHandler.post {
                                currentEvents = events
                                fetchState = if (events.isNotEmpty()) 1 else 2
                                buildUI()
                            }
                        }
                    }
                }
            })
        } catch (e: Exception) {}
    }

    private fun extractAppleMusicColors(bitmap: Bitmap) {
        try {
            val thumb = Bitmap.createScaledBitmap(bitmap, 1, 1, true)
            val avgColor = thumb.getPixel(0, 0)
            thumb.recycle()

            val hsv = FloatArray(3)
            Color.colorToHSV(avgColor, hsv)

            hsv[1] = (hsv[1] * 1.3f).coerceIn(0f, 1f)
            hsv[2] = hsv[2].coerceAtMost(0.40f)
            val newTop = Color.HSVToColor(hsv)

            hsv[2] = hsv[2].coerceAtMost(0.15f)
            val newBottom = Color.HSVToColor(hsv)

            mainHandler.post {
                if (albumColorTop == Color.parseColor("#2C2C2C")) {
                    activeBackgroundView?.topColor = newTop
                    activeBackgroundView?.bottomColor = newBottom
                } else {
                    activeBackgroundView?.animateColorsTo(newTop, newBottom)
                }
                albumColorTop = newTop
                albumColorBottom = newBottom
            }
        } catch (e: Exception) {}
    }

    private fun ensureCardInjected() {
        val activity = currentActivity ?: return
        val widgetsId = activity.resources.getIdentifier("widgets_container", "id", activity.packageName).takeIf { it != 0 } ?: activity.resources.getIdentifier("widgets_container", "id", "com.spotify.music")
        val widgetsContainer = activity.findViewById<ViewGroup>(widgetsId) ?: return
        val verticalLayout = widgetsContainer.getChildAt(0) as? ViewGroup ?: return

        if (verticalLayout.findViewWithTag<FrameLayout>("LIVE_RADAR_CARD") == null) injectCard(activity, verticalLayout)
    }

    private fun injectCard(activity: Activity, verticalLayout: ViewGroup) {
        val cardHeight = (activity.resources.displayMetrics.heightPixels * 0.55).toInt()

        val cardContainer = FrameLayout(activity).apply {
            tag = "LIVE_RADAR_CARD"
            layoutParams = LinearLayout.LayoutParams(-1, cardHeight).apply { setMargins(20, 30, 20, 30) }
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() { override fun getOutline(view: View, outline: Outline) { outline.setRoundRect(0, 0, view.width, view.height, 45f) } }
        }

        activeBackgroundView = AnimatedBackgroundView(activity).apply {
            layoutParams = FrameLayout.LayoutParams(-1, -1)
            topColor = albumColorTop
            bottomColor = albumColorBottom
        }
        cardContainer.addView(activeBackgroundView)

        val titleText = TextView(activity).apply {
            text = "Live Radar"
            setTextColor(Color.parseColor("#FFD1E8")) // Light pink
            textSize = 16f
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            layoutParams = FrameLayout.LayoutParams(-2, -2).apply {
                gravity = Gravity.TOP or Gravity.START
                setMargins(40, 45, 0, 0)
            }
        }
        cardContainer.addView(titleText)

        val scrollWrapper = FrameLayout(activity).apply {
            layoutParams = FrameLayout.LayoutParams(-1, -1).apply { setMargins(0, 130, 0, 0) }
        }

        activeScrollView = ScrollView(activity).apply {
            layoutParams = FrameLayout.LayoutParams(-1, -1)
            isFillViewport = true
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setPadding(0, 0, 0, 100)
            setOnTouchListener { v, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> v.parent.requestDisallowInterceptTouchEvent(true)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.parent.requestDisallowInterceptTouchEvent(false)
                }
                false
            }
        }

        activeContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 40)
        }

        activeScrollView?.addView(activeContainer)
        scrollWrapper.addView(activeScrollView)
        cardContainer.addView(scrollWrapper)

        verticalLayout.addView(cardContainer)
        buildUI()
    }

    private fun buildUI() {
        val container = activeContainer ?: return
        val context = container.context

        activeScrollView?.scrollTo(0, 0)
        container.removeAllViews()
        container.alpha = 0f
        container.translationY = 60f

        if (fetchState == 0) {
            val loadingText = TextView(context).apply {
                text = "Scanning for tour dates..."
                setTextColor(Color.parseColor("#B3B3B3"))
                textSize = 16f
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            }
            container.addView(loadingText)

            ObjectAnimator.ofFloat(loadingText, "alpha", 0.4f, 1f).apply {
                duration = 800
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                start()
            }
        } else if (fetchState == 2) {
            container.addView(TextView(context).apply { text = "No upcoming tours found for these artists."; setTextColor(Color.parseColor("#B3B3B3")); textSize = 16f; typeface = Typeface.create("sans-serif-medium", Typeface.BOLD) })
        } else {
            for (event in currentEvents) {
                val block = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    background = GradientDrawable().apply { cornerRadius = 24f; setColor(Color.parseColor("#4D000000")) }
                    setPadding(35, 35, 35, 35)
                    layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 30 }
                }

                block.addView(TextView(context).apply { text = event.artist; setTextColor(Color.parseColor("#FFD1E8")); textSize = 13f; typeface = Typeface.create("sans-serif-black", Typeface.BOLD); isAllCaps = true; layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 10 } })
                block.addView(TextView(context).apply { text = event.date; setTextColor(Color.WHITE); textSize = 20f; typeface = Typeface.create("sans-serif-black", Typeface.BOLD) })
                block.addView(TextView(context).apply { text = event.venue; setTextColor(Color.WHITE); textSize = 15f; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = 5 } })
                block.addView(TextView(context).apply { text = event.location; setTextColor(Color.parseColor("#B3B3B3")); textSize = 14f; typeface = Typeface.create("sans-serif", Typeface.NORMAL); layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = 5; bottomMargin = 25 } })

                val tktBtn = TextView(context).apply {
                    text = "Find Tickets"
                    setTextColor(Color.BLACK)
                    textSize = 14f
                    typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
                    setPadding(45, 20, 45, 20)
                    background = GradientDrawable().apply { cornerRadius = 100f; setColor(Color.WHITE) }
                    layoutParams = LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.START }
                    setOnClickListener { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(event.ticketUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
                block.addView(tktBtn)
                container.addView(block)
            }
        }

        container.animate().alpha(1f).translationY(0f).setDuration(500).setInterpolator(DecelerateInterpolator(1.5f)).start()
    }

    private fun registerLifecycleCallbacks() {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) { if (activity.javaClass.name == NPV_ACTIVITY) { currentActivity = activity; mainHandler.post(syncRunnable) } }
            override fun onActivityPaused(activity: Activity) { if (activity == currentActivity) { mainHandler.removeCallbacks(syncRunnable); currentActivity = null } }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
    }

    inner class AnimatedBackgroundView(context: Context) : View(context) {
        var topColor: Int = Color.DKGRAY
        var bottomColor: Int = Color.BLACK
        private val orbPaint1 = Paint(Paint.ANTI_ALIAS_FLAG)
        private val orbPaint2 = Paint(Paint.ANTI_ALIAS_FLAG)

        fun animateColorsTo(newTop: Int, newBottom: Int) {
            val topAnim = ValueAnimator.ofArgb(topColor, newTop)
            topAnim.addUpdateListener { topColor = it.animatedValue as Int; invalidate() }
            val botAnim = ValueAnimator.ofArgb(bottomColor, newBottom)
            botAnim.addUpdateListener { bottomColor = it.animatedValue as Int; invalidate() }
            android.animation.AnimatorSet().apply { playTogether(topAnim, botAnim); duration = 1000; interpolator = DecelerateInterpolator(1.5f); start() }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val time = SystemClock.elapsedRealtime()
            canvas.drawColor(Color.parseColor("#0A0A0A"))
            val cx1 = width * 0.5f + (width * 0.4f * Math.sin(time / 6000.0)).toFloat()
            val cy1 = height * 0.4f + (height * 0.3f * Math.cos(time / 4500.0)).toFloat()
            val cx2 = width * 0.5f + (width * 0.4f * Math.cos(time / 7000.0)).toFloat()
            val cy2 = height * 0.6f + (height * 0.3f * Math.sin(time / 5500.0)).toFloat()
            val radius = (Math.max(width, height) * 1.5f)
            orbPaint1.shader = RadialGradient(cx1, cy1, radius, topColor, Color.TRANSPARENT, Shader.TileMode.CLAMP)
            orbPaint2.shader = RadialGradient(cx2, cy2, radius * 1.2f, bottomColor, Color.TRANSPARENT, Shader.TileMode.CLAMP)
            canvas.drawCircle(cx1, cy1, radius, orbPaint1)
            canvas.drawCircle(cx2, cy2, radius * 1.2f, orbPaint2)
        }
    }
}