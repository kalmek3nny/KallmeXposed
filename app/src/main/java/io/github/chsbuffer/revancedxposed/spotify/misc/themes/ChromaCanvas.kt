package io.github.chsbuffer.revancedxposed.spotify.misc.themes

import android.animation.AnimatorSet
import android.animation.ValueAnimator
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.media.MediaMetadata
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

class ChromaCanvas(private val app: Application) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var activeBackgroundView: FullscreenAnimatedBackground? = null
    private var currentActivity: Activity? = null

    private var albumColorTop: Int = Color.parseColor("#121212")
    private var albumColorBottom: Int = Color.parseColor("#000000")

    // Force these Spotify background colors to become translucent glass (65% opacity black)
    private val translucentGlassColor = Color.parseColor("#A6000000")
    private val glassTargets = setOf(
        "dark_base_background_base", "dark_base_background_elevated_base",
        "sthlm_blk", "gray_7", "bg_gradient_start_color", "bg_gradient_end_color"
    )

    private val syncRunnable = object : Runnable {
        override fun run() {
            activeBackgroundView?.invalidate()
            mainHandler.postDelayed(this, 16) // 60fps
        }
    }

    fun init(classLoader: ClassLoader) {
        hookTranslucentBackgrounds()
        hookMediaSession(classLoader)

        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                currentActivity = activity
                injectCanvas(activity)
                mainHandler.post(syncRunnable)
            }
            override fun onActivityPaused(activity: Activity) {
                if (currentActivity == activity) {
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
    }

    private fun hookTranslucentBackgrounds() {
        // Overrides standard ThemeHook logic to force transparency for the Canvas to show through
        val resourceInterceptor = fun(param: XC_MethodHook.MethodHookParam) {
            val id = param.args[0] as? Int ?: return
            try {
                val res = param.thisObject as Resources
                val resName = res.getResourceEntryName(id)
                if (glassTargets.contains(resName)) {
                    param.result = if (param.result is ColorStateList) ColorStateList.valueOf(translucentGlassColor) else translucentGlassColor
                }
            } catch (e: Exception) {}
        }

        XposedBridge.hookAllMethods(Resources::class.java, "getColor", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) = resourceInterceptor(param)
        })
        XposedBridge.hookAllMethods(Resources::class.java, "getColorStateList", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) = resourceInterceptor(param)
        })
    }

    private fun hookMediaSession(classLoader: ClassLoader) {
        try {
            val mediaSessionClass = XposedHelpers.findClass("android.media.session.MediaSession", classLoader)
            XposedBridge.hookAllMethods(mediaSessionClass, "setMetadata", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val metadata = param.args[0] as? MediaMetadata ?: return
                    val bitmap = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
                    if (bitmap != null) extractColors(bitmap)
                }
            })
        } catch (e: Exception) {}
    }

    private fun extractColors(bitmap: Bitmap) {
        try {
            val thumb = Bitmap.createScaledBitmap(bitmap, 1, 1, true)
            val avgColor = thumb.getPixel(0, 0)
            thumb.recycle()

            val hsv = FloatArray(3)
            Color.colorToHSV(avgColor, hsv)

            // Saturation boost, brightness cap
            hsv[1] = (hsv[1] * 1.5f).coerceIn(0f, 1f)
            hsv[2] = hsv[2].coerceAtMost(0.40f)
            val newTop = Color.HSVToColor(hsv)

            hsv[2] = hsv[2].coerceAtMost(0.15f)
            val newBottom = Color.HSVToColor(hsv)

            mainHandler.post {
                activeBackgroundView?.animateColorsTo(newTop, newBottom)
                albumColorTop = newTop
                albumColorBottom = newBottom
            }
        } catch (e: Exception) {}
    }

    private fun injectCanvas(activity: Activity) {
        val root = activity.window.decorView as? ViewGroup ?: return

        var canvas = root.findViewWithTag<FullscreenAnimatedBackground>("CHROMA_CANVAS")
        if (canvas == null) {
            canvas = FullscreenAnimatedBackground(activity).apply {
                tag = "CHROMA_CANVAS"
                layoutParams = FrameLayout.LayoutParams(-1, -1)
                topColor = albumColorTop
                bottomColor = albumColorBottom
            }
            // Inject at index 0 so it sits BEHIND all UI elements
            root.addView(canvas, 0)
        }
        activeBackgroundView = canvas
    }

    inner class FullscreenAnimatedBackground(context: Context) : View(context) {
        var topColor: Int = Color.DKGRAY
        var bottomColor: Int = Color.BLACK
        private val orbPaint1 = Paint(Paint.ANTI_ALIAS_FLAG)
        private val orbPaint2 = Paint(Paint.ANTI_ALIAS_FLAG)

        fun animateColorsTo(newTop: Int, newBottom: Int) {
            val topAnim = ValueAnimator.ofArgb(topColor, newTop)
            topAnim.addUpdateListener { topColor = it.animatedValue as Int }
            val botAnim = ValueAnimator.ofArgb(bottomColor, newBottom)
            botAnim.addUpdateListener { bottomColor = it.animatedValue as Int }
            AnimatorSet().apply { playTogether(topAnim, botAnim); duration = 1500; interpolator = DecelerateInterpolator(1.5f); start() }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val time = SystemClock.elapsedRealtime()

            // Draw absolute base black
            canvas.drawColor(Color.BLACK)

            val cx1 = width * 0.5f + (width * 0.4f * Math.sin(time / 8000.0)).toFloat()
            val cy1 = height * 0.4f + (height * 0.3f * Math.cos(time / 6500.0)).toFloat()
            val cx2 = width * 0.5f + (width * 0.4f * Math.cos(time / 9000.0)).toFloat()
            val cy2 = height * 0.6f + (height * 0.3f * Math.sin(time / 7500.0)).toFloat()
            val radius = (Math.max(width, height) * 1.5f)

            orbPaint1.shader = RadialGradient(cx1, cy1, radius, topColor, Color.TRANSPARENT, Shader.TileMode.CLAMP)
            orbPaint2.shader = RadialGradient(cx2, cy2, radius * 1.2f, bottomColor, Color.TRANSPARENT, Shader.TileMode.CLAMP)

            canvas.drawCircle(cx1, cy1, radius, orbPaint1)
            canvas.drawCircle(cx2, cy2, radius * 1.2f, orbPaint2)
        }
    }
}