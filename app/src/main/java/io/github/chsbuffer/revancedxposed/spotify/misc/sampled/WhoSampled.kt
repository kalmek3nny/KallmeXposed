package io.github.chsbuffer.revancedxposed.spotify.misc.sampled

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
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

class WhoSampled(private val app: Application) {

    private val NPV_ACTIVITY = "com.spotify.nowplaying.musicinstallation.NowPlayingActivity"
    private var currentActivity: Activity? = null

    private var currentTrack: String = ""
    private var currentArtist: String = ""
    private var fetchState = 0 // 0=Loading, 1=Found, 2=Not Found

    private var currentSampleData: List<SampleData> = emptyList()

    // Color Variables for the Animated Background
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
                activeBackgroundView?.invalidate() // Keep orbs spinning
            }
            mainHandler.postDelayed(this, 16)
        }
    }

    fun init(classLoader: ClassLoader) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                if (activity::class.java.name == NPV_ACTIVITY) {
                    currentActivity = activity
                    mainHandler.post(syncRunnable)
                }
            }
            override fun onActivityPaused(activity: Activity) {
                if (activity == currentActivity) {
                    mainHandler.removeCallbacks(syncRunnable)
                    currentActivity = null
                }
            }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
        hookMediaSession(classLoader)
    }

    private fun hookMediaSession(classLoader: ClassLoader) {
        try {
            val mediaSessionClass = XposedHelpers.findClass("android.media.session.MediaSession", classLoader)
            XposedBridge.hookAllMethods(mediaSessionClass, "setMetadata", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val metadata = param.args[0] as? MediaMetadata ?: return
                    val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE) ?: return
                    val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""

                    // Extract colors for the background
                    val bitmap = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                        ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
                        ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)

                    if (bitmap != null) {
                        extractAppleMusicColors(bitmap)
                    }

                    if (title != currentTrack || artist != currentArtist) {
                        currentTrack = title
                        currentArtist = artist
                        fetchState = 0
                        mainHandler.post { buildUI() }

                        // Pass Application Context here for the logger
                        WhoSampledAPI.fetchSamples(app.applicationContext, title, artist) { results ->
                            mainHandler.post {
                                currentSampleData = results
                                fetchState = if (results.isNotEmpty()) 1 else 2
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
        val widgetsId = activity.resources.getIdentifier("widgets_container", "id", activity.packageName)
            .takeIf { it != 0 } ?: activity.resources.getIdentifier("widgets_container", "id", "com.spotify.music")

        val widgetsContainer = activity.findViewById<ViewGroup>(widgetsId) ?: return
        val verticalLayout = widgetsContainer.getChildAt(0) as? ViewGroup ?: return

        if (verticalLayout.findViewWithTag<FrameLayout>("WHO_SAMPLED_CARD") == null) {
            injectCard(activity, verticalLayout)
        }
    }

    private fun injectCard(activity: Activity, verticalLayout: ViewGroup) {
        val cardHeight = (activity.resources.displayMetrics.heightPixels * 0.55).toInt()

        val cardContainer = FrameLayout(activity).apply {
            tag = "WHO_SAMPLED_CARD"
            layoutParams = LinearLayout.LayoutParams(-1, cardHeight).apply {
                setMargins(20, 30, 20, 30)
            }
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, 45f)
                }
            }
        }

        activeBackgroundView = AnimatedBackgroundView(activity).apply {
            layoutParams = FrameLayout.LayoutParams(-1, -1)
            topColor = albumColorTop
            bottomColor = albumColorBottom
        }
        cardContainer.addView(activeBackgroundView)

        val titleText = TextView(activity).apply {
            text = "Who Sampled" // <--- RENAMED HERE
            setTextColor(Color.parseColor("#FFB300")) // Amber/Gold Accent
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

        // Inject below lyrics and radar
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
                text = "Searching WhoSampled archives..."
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
            container.addView(TextView(context).apply {
                text = "No known samples or interpolations found."
                setTextColor(Color.parseColor("#B3B3B3"))
                textSize = 16f
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            })
        } else {
            for (sample in currentSampleData) {
                val block = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    background = GradientDrawable().apply { cornerRadius = 24f; setColor(Color.parseColor("#4D000000")) }
                    setPadding(35, 30, 35, 30)
                    layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 25 }
                }

                block.addView(TextView(context).apply {
                    text = sample.type
                    setTextColor(Color.parseColor("#FFB300"))
                    textSize = 12f
                    typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
                    isAllCaps = true
                    layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 10 }
                })

                block.addView(TextView(context).apply {
                    text = sample.trackName
                    setTextColor(Color.WHITE)
                    textSize = 18f
                    typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
                })

                block.addView(TextView(context).apply {
                    text = sample.artist
                    setTextColor(Color.parseColor("#B3B3B3"))
                    textSize = 14f
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = 5; bottomMargin = 25 }
                })

                val viewBtn = TextView(context).apply {
                    text = "View on WhoSampled"
                    setTextColor(Color.BLACK)
                    textSize = 14f
                    typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
                    setPadding(45, 20, 45, 20)
                    background = GradientDrawable().apply { cornerRadius = 100f; setColor(Color.WHITE) }
                    layoutParams = LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.START }
                    setOnClickListener {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(sample.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
                block.addView(viewBtn)

                container.addView(block)
            }
        }

        // Pop-in animation
        container.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(500)
            .setInterpolator(DecelerateInterpolator(1.5f))
            .start()
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

            android.animation.AnimatorSet().apply {
                playTogether(topAnim, botAnim)
                duration = 1000
                interpolator = DecelerateInterpolator(1.5f)
                start()
            }
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