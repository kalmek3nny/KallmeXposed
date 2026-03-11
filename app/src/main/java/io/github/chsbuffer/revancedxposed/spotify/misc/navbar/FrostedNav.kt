package io.github.chsbuffer.revancedxposed.spotify.misc.navbar

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.Drawable
import android.media.MediaMetadata
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import io.github.chsbuffer.revancedxposed.MainActivity
import java.net.URL
import java.util.WeakHashMap
import kotlin.concurrent.thread

object FrostedNavigation {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var currentActivity: Activity? = null

    var c1 = Color.parseColor("#1A1A1A")
    var c2 = Color.parseColor("#2A2A2A")
    var c3 = Color.parseColor("#0A0A0A")
    var c4 = Color.parseColor("#111111")
    var dynamicOutlineColor = Color.parseColor("#22FFFFFF") // Subtle default

    private var colorAnimator: ValueAnimator? = null
    private val managedBackgrounds = WeakHashMap<View, FluidMeshDrawable>()

    private var cachedPlaylistBitmap: Bitmap? = null
    private var lastLoadedImageUrl: String = ""

    enum class BarType { NPV, NAVBAR, TAB }

    private val structureRunnable = object : Runnable {
        override fun run() {
            currentActivity?.let { applyFrosting(it) }
            mainHandler.postDelayed(this, 1000)
        }
    }

    private val renderRunnable = object : Runnable {
        override fun run() {
            if (currentActivity != null) {
                managedBackgrounds.keys.forEach { it.invalidate() }
            }
            mainHandler.postDelayed(this, 16)
        }
    }

    fun init(app: Application, classLoader: ClassLoader) {
        val prefs = XSharedPreferences(MainActivity.PACKAGE_NAME, MainActivity.PREF_FILE)
        prefs.makeWorldReadable()
        if (!prefs.getBoolean("enable_frosted_nav", true)) return

        hookMediaSession(classLoader)

        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                currentActivity = activity
                mainHandler.post(structureRunnable)
                mainHandler.post(renderRunnable)
            }
            override fun onActivityPaused(activity: Activity) {
                if (currentActivity == activity) {
                    mainHandler.removeCallbacks(structureRunnable)
                    mainHandler.removeCallbacks(renderRunnable)
                    currentActivity = null
                }
            }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
    }

    private fun hookMediaSession(classLoader: ClassLoader) {
        try {
            val mediaSessionClass = XposedHelpers.findClass("android.media.session.MediaSession", classLoader)
            XposedBridge.hookAllMethods(mediaSessionClass, "setMetadata", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val metadata = param.args[0] as? MediaMetadata ?: return
                    val bitmap = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
                    if (bitmap != null) extractGlassColor(bitmap)
                }
            })
        } catch (e: Exception) {}
    }

    private fun extractGlassColor(bitmap: Bitmap) {
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

            // 🎯 SUBTLE OUTLINE: Desaturated, low opacity (40% max)
            val newOutlineColor = if (lum > 127) {
                outHsv[1] = (outHsv[1] * 0.8f).coerceAtMost(1f) // Less saturated
                outHsv[2] = 0.2f // Much darker
                Color.HSVToColor(90, outHsv) // ~35% opacity
            } else {
                outHsv[1] = (outHsv[1] * 0.4f).coerceAtMost(1f) // Near white/pastel
                outHsv[2] = 0.95f // Bright
                Color.HSVToColor(100, outHsv) // ~40% opacity
            }

            val hsv = FloatArray(3)
            Color.colorToHSV(avgColor, hsv)
            val baseHue = hsv[0]
            val baseSat = hsv[1].coerceIn(0.5f, 1f)

            hsv[0] = baseHue; hsv[1] = baseSat; hsv[2] = 0.90f; val nc1 = Color.HSVToColor(hsv)
            hsv[0] = (baseHue + 40f) % 360f; hsv[1] = baseSat * 0.9f; hsv[2] = 0.80f; val nc2 = Color.HSVToColor(hsv)
            hsv[0] = (baseHue - 30f + 360f) % 360f; hsv[1] = baseSat; hsv[2] = 0.70f; val nc3 = Color.HSVToColor(hsv)
            hsv[0] = (baseHue + 15f) % 360f; hsv[1] = baseSat * 1.0f; hsv[2] = 0.60f; val nc4 = Color.HSVToColor(hsv)

            mainHandler.post {
                animateColorChange(nc1, nc2, nc3, nc4, newOutlineColor)
            }
        } catch (e: Exception) {}
    }

    private fun animateColorChange(t1: Int, t2: Int, t3: Int, t4: Int, tOut: Int) {
        colorAnimator?.cancel()
        val a1 = android.animation.ValueAnimator.ofArgb(c1, t1).apply { addUpdateListener { c1 = it.animatedValue as Int } }
        val a2 = android.animation.ValueAnimator.ofArgb(c2, t2).apply { addUpdateListener { c2 = it.animatedValue as Int } }
        val a3 = android.animation.ValueAnimator.ofArgb(c3, t3).apply { addUpdateListener { c3 = it.animatedValue as Int } }
        val a4 = android.animation.ValueAnimator.ofArgb(c4, t4).apply { addUpdateListener { c4 = it.animatedValue as Int } }
        val aOut = android.animation.ValueAnimator.ofArgb(dynamicOutlineColor, tOut).apply { addUpdateListener { dynamicOutlineColor = it.animatedValue as Int } }

        android.animation.AnimatorSet().apply {
            playTogether(a1, a2, a3, a4, aOut)
            duration = 1500
            interpolator = DecelerateInterpolator(1.5f)
            start()
        }
    }

    private fun applyFrosting(activity: Activity) {
        val root = activity.window.decorView as? ViewGroup ?: return

        var navBarHeight = 160
        var isNavVisible = false

        val navContainerId = activity.resources.getIdentifier("navigation_bar", "id", activity.packageName)
        if (navContainerId != 0) {
            val navView = root.findViewById<View>(navContainerId)
            if (navView != null && navView.isShown && navView.height > 0) {
                navBarHeight = navView.height
                isNavVisible = true
            }
        }

        forceTranslucent(root, activity)

        val tab = root.findViewWithTag<LinearLayout>("PINNED_TAB")
        if (isNavVisible) {
            injectCustomTab(activity, root, navBarHeight)
            tab?.visibility = View.VISIBLE
        } else {
            tab?.visibility = View.GONE
        }
    }

    private fun forceTranslucent(view: View, activity: Activity) {
        val name = view.javaClass.simpleName
        val resName = try { view.resources.getResourceEntryName(view.id) } catch (e: Exception) { "" }

        if (name.contains("BottomNavigationView") || resName == "navigation_bar" || resName == "now_playing_bar_layout") {

            val screenW = activity.resources.displayMetrics.widthPixels

            var drawable = managedBackgrounds[view]
            if (drawable == null || view.background !== drawable) {
                val type = if (resName == "now_playing_bar_layout") BarType.NPV else BarType.NAVBAR
                val radii = if (type == BarType.NPV) {
                    floatArrayOf(45f, 45f, 45f, 45f, 45f, 45f, 45f, 45f)
                } else {
                    floatArrayOf(45f, 45f, 0f, 0f, 0f, 0f, 0f, 0f)
                }
                drawable = FluidMeshDrawable(radii, type, screenW)
                view.background = drawable
                managedBackgrounds[view] = drawable
            }

            if (view.paddingTop < 10 && resName != "now_playing_bar_layout") {
                view.setPadding(view.paddingLeft, view.paddingTop + 15, view.paddingRight, view.paddingBottom)
            }

            if (resName == "navigation_bar") {
                if (view.isShown) {
                    val cutoff = (screenW * 0.75f).toInt()
                    val currentClip = view.clipBounds
                    if (currentClip == null || currentClip.right != cutoff) {
                        view.clipBounds = Rect(0, 0, cutoff, view.height + 1000)
                    }
                } else if (view.clipBounds != null) {
                    view.clipBounds = null
                }
            }
        }

        if (view is RecyclerView || name.contains("ScrollView")) {
            val vg = view as? ViewGroup
            if (vg != null && (vg.clipToPadding || vg.clipChildren)) {
                vg.clipToPadding = false; vg.clipChildren = false
            }
        }

        if (view is ViewGroup) {
            for (i in 0 until view.childCount) forceTranslucent(view.getChildAt(i), activity)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun injectCustomTab(activity: Activity, root: ViewGroup, navHeight: Int) {
        val prefs = activity.getSharedPreferences("revanced_pinned", Context.MODE_PRIVATE)
        val pinnedUri = prefs.getString("pinned_uri", "") ?: ""
        val pinnedTitle = prefs.getString("pinned_title", "Pin Playlist") ?: "Pin Playlist"
        val imageUrl = prefs.getString("pinned_image", "") ?: ""

        var tab = root.findViewWithTag<LinearLayout>("PINNED_TAB")
        val screenWidth = activity.resources.displayMetrics.widthPixels
        val tabWidth = (screenWidth * 0.25f).toInt()

        val density = activity.resources.displayMetrics.density
        val iconSizePx = (26 * density).toInt()
        val iconRadiusPx = 4 * density
        val iconMarginTop = (6 * density).toInt()
        val iconMarginBottom = (4 * density).toInt()
        val textPaddingHorizontal = (4 * density).toInt()

        if (tab == null) {
            tab = LinearLayout(activity).apply {
                tag = "PINNED_TAB"
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                isClickable = true
                elevation = 1000f
                translationZ = 1000f

                val icon = ImageView(activity).apply {
                    tag = "PINNED_ICON"
                    layoutParams = LinearLayout.LayoutParams(iconSizePx, iconSizePx).apply {
                        topMargin = iconMarginTop
                        bottomMargin = iconMarginBottom
                    }
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    clipToOutline = true
                    outlineProvider = object : ViewOutlineProvider() {
                        override fun getOutline(view: View, outline: Outline) {
                            outline.setRoundRect(0, 0, view.width, view.height, iconRadiusPx)
                        }
                    }
                    setBackgroundColor(Color.parseColor("#282828"))
                }

                val text = TextView(activity).apply {
                    tag = "PINNED_TEXT"
                    setTextColor(Color.parseColor("#E5E5E5"))
                    textSize = 10.5f
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    gravity = Gravity.CENTER_HORIZONTAL
                    textAlignment = View.TEXT_ALIGNMENT_CENTER
                    isSingleLine = true
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(textPaddingHorizontal, 0, textPaddingHorizontal, 0)
                    layoutParams = LinearLayout.LayoutParams(-1, -2)
                }

                addView(icon)
                addView(text)

                var holdRunnable: Runnable? = null
                var isClearedByHold = false

                setOnTouchListener { _, event ->
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            isClearedByHold = false
                            holdRunnable = Runnable {
                                isClearedByHold = true
                                prefs.edit().clear().apply()
                                cachedPlaylistBitmap = null
                                lastLoadedImageUrl = ""

                                findViewWithTag<ImageView>("PINNED_ICON")?.setImageBitmap(null)
                                findViewWithTag<ImageView>("PINNED_ICON")?.setBackgroundColor(Color.parseColor("#282828"))
                                findViewWithTag<TextView>("PINNED_TEXT")?.text = "Pin Playlist"

                                Toast.makeText(activity, "🗑️ Pinned playlist cleared!\nCopy a new link to pin.", Toast.LENGTH_LONG).show()
                            }
                            mainHandler.postDelayed(holdRunnable!!, 3000)
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            holdRunnable?.let { mainHandler.removeCallbacks(it) }
                        }
                    }
                    false
                }

                setOnClickListener {
                    if (isClearedByHold) return@setOnClickListener

                    val currentUri = prefs.getString("pinned_uri", "") ?: ""
                    if (currentUri.isEmpty()) {
                        Toast.makeText(activity, "📌 To pin a playlist, simply copy its link!", Toast.LENGTH_LONG).show()
                    } else {
                        val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(currentUri))
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        activity.startActivity(intent)
                    }
                }
            }
            root.addView(tab)
        }

        if (imageUrl.isNotEmpty() && imageUrl != lastLoadedImageUrl) {
            lastLoadedImageUrl = imageUrl
            thread {
                try {
                    val stream = URL(imageUrl).openStream()
                    cachedPlaylistBitmap = BitmapFactory.decodeStream(stream)
                    mainHandler.post {
                        tab?.findViewWithTag<ImageView>("PINNED_ICON")?.setImageBitmap(cachedPlaylistBitmap)
                    }
                } catch (e: Exception) {}
            }
        } else if (imageUrl.isEmpty()) {
            tab.findViewWithTag<ImageView>("PINNED_ICON")?.setImageBitmap(null)
        } else if (cachedPlaylistBitmap != null) {
            tab.findViewWithTag<ImageView>("PINNED_ICON")?.setImageBitmap(cachedPlaylistBitmap)
        }

        val insets = root.rootWindowInsets
        val bottomInset = insets?.systemWindowInsetBottom ?: 0
        val currentParams = tab.layoutParams as? FrameLayout.LayoutParams

        if (currentParams == null || currentParams.width != tabWidth || currentParams.height != navHeight || currentParams.bottomMargin != bottomInset) {
            tab.layoutParams = FrameLayout.LayoutParams(tabWidth, navHeight).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                bottomMargin = bottomInset
            }
        }

        var drawable = managedBackgrounds[tab]
        if (drawable == null || tab.background !== drawable) {
            drawable = FluidMeshDrawable(floatArrayOf(0f, 0f, 45f, 45f, 0f, 0f, 0f, 0f), BarType.TAB, screenWidth)
            tab.background = drawable
            managedBackgrounds[tab] = drawable
        }

        val titleToDisplay = if (pinnedUri.isEmpty()) "Pin Playlist" else pinnedTitle
        tab.findViewWithTag<TextView>("PINNED_TEXT")?.text = titleToDisplay
    }

    class FluidMeshDrawable(private val radii: FloatArray, private val type: BarType, private val screenW: Int) : Drawable() {
        private val paint1 = Paint(Paint.ANTI_ALIAS_FLAG)
        private val paint2 = Paint(Paint.ANTI_ALIAS_FLAG)
        private val paint3 = Paint(Paint.ANTI_ALIAS_FLAG)
        private val paint4 = Paint(Paint.ANTI_ALIAS_FLAG)

        private val overlayPaint = Paint().apply { color = Color.parseColor("#A6000000") } // 65% Black

        // 🎯 SUBTLE OUTLINE
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f // Thinner, more elegant line
        }

        private val clipPath = Path()
        private var lastW = 0f
        private var lastH = 0f

        private fun transparentColor(c: Int) = Color.argb(0, Color.red(c), Color.green(c), Color.blue(c))
        private fun applyAlpha(c: Int, alpha: Int) = Color.argb(alpha, Color.red(c), Color.green(c), Color.blue(c))

        override fun draw(canvas: Canvas) {
            val w = bounds.width().toFloat()
            val h = bounds.height().toFloat()
            if (w <= 0f || h <= 0f) return

            if (w != lastW || h != lastH) {
                clipPath.reset()
                clipPath.addRoundRect(bounds.left.toFloat(), bounds.top.toFloat(), bounds.right.toFloat(), bounds.bottom.toFloat(), radii, Path.Direction.CW)
                lastW = w; lastH = h
            }

            canvas.save()
            canvas.clipPath(clipPath)

            val time = SystemClock.elapsedRealtime()
            val sW = screenW.toFloat()
            val rad = Math.max(sW, h) * 1.5f

            val x1 = sW * 0.3f + (sW * 0.5f * Math.sin(time / 6100.0)).toFloat()
            val y1 = h * 0.2f + (h * 0.4f * Math.cos(time / 4700.0)).toFloat()

            val x2 = sW * 0.7f + (sW * 0.4f * Math.cos(time / 7300.0)).toFloat()
            val y2 = h * 0.3f + (h * 0.5f * Math.sin(time / 5900.0)).toFloat()

            val x3 = sW * 0.4f + (sW * 0.5f * Math.sin(time / 5300.0 + Math.PI)).toFloat()
            val y3 = h * 0.8f + (h * 0.4f * Math.cos(time / 6700.0)).toFloat()

            val x4 = sW * 0.8f + (sW * 0.4f * Math.cos(time / 8300.0 + Math.PI)).toFloat()
            val y4 = h * 0.7f + (h * 0.5f * Math.sin(time / 4100.0)).toFloat()

            paint1.shader = RadialGradient(x1, y1, rad, applyAlpha(FrostedNavigation.c1, 50), transparentColor(FrostedNavigation.c1), Shader.TileMode.CLAMP)
            paint2.shader = RadialGradient(x2, y2, rad, applyAlpha(FrostedNavigation.c2, 50), transparentColor(FrostedNavigation.c2), Shader.TileMode.CLAMP)
            paint3.shader = RadialGradient(x3, y3, rad, applyAlpha(FrostedNavigation.c3, 50), transparentColor(FrostedNavigation.c3), Shader.TileMode.CLAMP)
            paint4.shader = RadialGradient(x4, y4, rad, applyAlpha(FrostedNavigation.c4, 50), transparentColor(FrostedNavigation.c4), Shader.TileMode.CLAMP)

            canvas.save()
            if (type == BarType.TAB) canvas.translate(-(sW * 0.75f), 0f)

            canvas.drawCircle(x1, y1, rad, paint1)
            canvas.drawCircle(x2, y2, rad, paint2)
            canvas.drawCircle(x3, y3, rad, paint3)
            canvas.drawCircle(x4, y4, rad, paint4)
            canvas.restore()

            canvas.drawRect(bounds, overlayPaint)

            strokePaint.color = FrostedNavigation.dynamicOutlineColor
            canvas.save()

            // 🎯 BOTTOM CLIP & SEAMLESS TAB FIX
            when (type) {
                BarType.NAVBAR -> {
                    // Cuts off the right edge (where the tab goes) and the bottom entirely!
                    canvas.clipRect(-10f, -10f, w, h - 5f)
                }
                BarType.TAB -> {
                    // Cuts off the left edge (to mate with the navbar) and the bottom!
                    canvas.clipRect(1f, -10f, w + 10f, h - 5f)
                }
                BarType.NPV -> {
                    // NPV floats, so it needs a full border. No clip needed!
                }
            }

            canvas.drawPath(clipPath, strokePaint)
            canvas.restore()

            canvas.restore()
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }
}