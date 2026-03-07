package io.github.chsbuffer.revancedxposed.spotify.misc.artists

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.app.Application
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.ColorDrawable
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
import android.view.Window
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

class BehindTheTracks(private val app: Application) {

    private val NPV_ACTIVITY = "com.spotify.nowplaying.musicinstallation.NowPlayingActivity"
    private var currentActivity: Activity? = null

    private var currentTrack: String = ""
    private var currentArtist: String = ""
    private var currentData: GeniusData? = null

    // Color Variables
    private var albumColorTop: Int = Color.parseColor("#2C2C2C")
    private var albumColorBottom: Int = Color.parseColor("#0A0A0A")

    private var fetchState = 0 // 0=Loading, 1=Done, 2=Failed
    private var activeContainer: LinearLayout? = null
    private var activeScrollView: ScrollView? = null
    private var activeBackgroundView: AnimatedBackgroundView? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val syncRunnable = object : Runnable {
        override fun run() {
            val activity = currentActivity ?: return
            if (activity::class.java.name == NPV_ACTIVITY) {
                ensureCardInjected()
                activeBackgroundView?.invalidate() // Keep the orbs spinning!
            }
            mainHandler.postDelayed(this, 16) // 60fps refresh for the background
        }
    }

    fun init(classLoader: ClassLoader) {
        registerLifecycleCallbacks()
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

                    // Extract Album Art Colors for the Background
                    val bitmap = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                        ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
                        ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)

                    if (bitmap != null) {
                        extractAppleMusicColors(bitmap)
                    }

                    if (title != currentTrack || artist != currentArtist) {
                        currentTrack = title
                        currentArtist = artist
                        fetchData(title, artist)
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

    private fun fetchData(track: String, artist: String) {
        fetchState = 0
        currentData = null
        mainHandler.post { buildUI() }

        GeniusAPI.fetchTrackData(app.applicationContext, track, artist) { data ->
            mainHandler.post {
                if (data != null) {
                    currentData = data
                    fetchState = 1
                } else {
                    fetchState = 2
                }
                buildUI()
            }
        }
    }

    private fun ensureCardInjected() {
        val activity = currentActivity ?: return
        val widgetsId = activity.resources.getIdentifier("widgets_container", "id", activity.packageName)
            .takeIf { it != 0 } ?: activity.resources.getIdentifier("widgets_container", "id", "com.spotify.music")

        val widgetsContainer = activity.findViewById<ViewGroup>(widgetsId) ?: return
        val verticalLayout = widgetsContainer.getChildAt(0) as? ViewGroup ?: return

        var cardContainer = verticalLayout.findViewWithTag<FrameLayout>("BEHIND_THE_TRACKS_CARD")
        if (cardContainer == null) {
            injectCard(activity, verticalLayout)
        }
    }

    private fun injectCard(activity: Activity, verticalLayout: ViewGroup) {
        val cardHeight = (activity.resources.displayMetrics.heightPixels * 0.55).toInt()

        val cardContainer = FrameLayout(activity).apply {
            tag = "BEHIND_THE_TRACKS_CARD"
            layoutParams = LinearLayout.LayoutParams(-1, cardHeight).apply {
                setMargins(20, 30, 20, 30) // Matches Beautiful Lyrics card size
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

        // Title Text
        val titleText = TextView(activity).apply {
            text = "Behind the Tracks"
            // Rich Dark Blue Color
            setTextColor(Color.parseColor("#3B82F6"))
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

        if (verticalLayout.childCount >= 1) {
            verticalLayout.addView(cardContainer, 1)
        } else {
            verticalLayout.addView(cardContainer)
        }

        buildUI()
    }

    private fun buildUI() {
        val container = activeContainer ?: return
        val context = container.context

        // Reset scroll position and setup the animation starting point
        activeScrollView?.scrollTo(0, 0)
        container.removeAllViews()
        container.alpha = 0f
        container.translationY = 60f

        if (fetchState == 0) {
            val loadingText = TextView(context).apply {
                text = "Asking Genius..."
                setTextColor(Color.parseColor("#B3B3B3"))
                textSize = 16f
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            }
            container.addView(loadingText)

            // Pulsing animation for loading text
            ObjectAnimator.ofFloat(loadingText, "alpha", 0.4f, 1f).apply {
                duration = 800
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                start()
            }

        } else if (fetchState == 2 || currentData == null) {
            container.addView(TextView(context).apply { text = "No trivia available for this track."; setTextColor(Color.parseColor("#B3B3B3")); textSize = 16f; typeface = Typeface.create("sans-serif-medium", Typeface.BOLD) })
        } else {
            val data = currentData!!

            // Metadata Text
            var metaString = ""
            if (data.releaseDate.isNotEmpty()) metaString += "Released ${data.releaseDate}\n"
            if (data.producers.isNotEmpty()) metaString += "Produced by ${data.producers}"

            if (metaString.isNotEmpty()) {
                container.addView(TextView(context).apply {
                    text = metaString.trim()
                    setTextColor(Color.parseColor("#B3B3B3"))
                    textSize = 13f
                    typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                    layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 40 }
                })
            }

            // About / Trivia Text
            if (data.aboutText.isNotEmpty() && data.aboutText != "?") {
                container.addView(TextView(context).apply { text = "Trivia"; setTextColor(Color.WHITE); textSize = 14f; typeface = Typeface.create("sans-serif-black", Typeface.BOLD); layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 10 } })
                container.addView(TextView(context).apply {
                    text = data.aboutText
                    setTextColor(Color.parseColor("#E0E0E0"))
                    textSize = 15f
                    setLineSpacing(0f, 1.2f)
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 60 }
                })
            }

            // Interactive Annotations
            if (data.annotations.isNotEmpty()) {
                container.addView(TextView(context).apply { text = "Top Annotations"; setTextColor(Color.WHITE); textSize = 14f; typeface = Typeface.create("sans-serif-black", Typeface.BOLD); layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 20 } })

                for (ann in data.annotations) {
                    val bubble = LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        background = GradientDrawable().apply { cornerRadius = 24f; setColor(Color.parseColor("#4D000000")) }
                        setPadding(35, 30, 35, 30)
                        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 25 }

                        addView(TextView(context).apply {
                            text = "\"${ann.fragment}\""
                            setTextColor(Color.WHITE)
                            textSize = 15f
                            typeface = Typeface.create("sans-serif-medium", Typeface.ITALIC)
                        })

                        setOnClickListener { showAnnotationPopup(context, ann) }
                    }
                    container.addView(bubble)
                }
            }

            // "VIEW FULL BIO" BUTTON
            if (data.url.isNotEmpty()) {
                val bioBtn = TextView(context).apply {
                    text = "View full bio"
                    setTextColor(Color.BLACK)
                    textSize = 14f
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                    setPadding(45, 20, 45, 20)
                    background = GradientDrawable().apply {
                        cornerRadius = 100f
                        setColor(Color.WHITE)
                    }
                    layoutParams = LinearLayout.LayoutParams(-2, -2).apply {
                        gravity = Gravity.START
                        topMargin = 15
                        bottomMargin = 40
                    }
                    setOnClickListener {
                        try {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(data.url))
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(intent)
                        } catch (e: Exception) {}
                    }
                }
                container.addView(bioBtn)
            }
        }

        // Beautiful Slide-Up & Fade-In Animation!
        container.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(500)
            .setInterpolator(DecelerateInterpolator(1.5f))
            .start()
    }

    private fun showAnnotationPopup(context: Context, ann: GeniusAnnotation) {
        val dialog = Dialog(context, android.R.style.Theme_Translucent_NoTitleBar)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.parseColor("#B3000000"))) // Dark blur effect

        val root = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(-1, -1)
            setPadding(60, 0, 60, 0)
            setOnClickListener { dialog.dismiss() }
        }

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { cornerRadius = 40f; setColor(Color.parseColor("#1A1A1A")) }
            setPadding(60, 60, 60, 60)
            layoutParams = FrameLayout.LayoutParams(-1, -2).apply { gravity = Gravity.CENTER }
            setOnClickListener { /* Consume clicks */ }
        }

        card.addView(TextView(context).apply {
            text = "Genius Annotation"
            setTextColor(Color.parseColor("#3B82F6")) // Matching Blue
            textSize = 12f
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            isAllCaps = true
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 20 }
        })

        card.addView(TextView(context).apply {
            text = "\"${ann.fragment}\""
            setTextColor(Color.WHITE)
            textSize = 18f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD_ITALIC)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 40 }
        })

        card.addView(TextView(context).apply {
            text = ann.explanation
            setTextColor(Color.parseColor("#D0D0D0"))
            textSize = 15f
            setLineSpacing(0f, 1.3f)
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        })

        root.addView(card)
        dialog.setContentView(root)

        // Slick pop-in animation
        card.scaleX = 0.8f; card.scaleY = 0.8f; card.alpha = 0f
        card.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(250).setInterpolator(DecelerateInterpolator(1.5f)).start()

        dialog.show()
    }

    private fun registerLifecycleCallbacks() {
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
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
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

            val set = android.animation.AnimatorSet()
            set.playTogether(topAnim, botAnim)
            set.duration = 1000
            set.interpolator = DecelerateInterpolator(1.5f)
            set.start()
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