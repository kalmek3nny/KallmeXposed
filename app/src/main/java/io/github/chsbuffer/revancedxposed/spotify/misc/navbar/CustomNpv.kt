package io.github.chsbuffer.revancedxposed.spotify.misc.themes

import android.animation.ValueAnimator
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.*
import android.media.MediaMetadata
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import kotlin.math.abs

class CustomNpv(private val app: Application) {

    private val NPV_ACTIVITY = "com.spotify.nowplaying.musicinstallation.NowPlayingActivity"
    private var currentActivity: Activity? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var activeBackgroundView: NpvFluidBackgroundView? = null

    // Colors
    private var c1 = Color.BLACK
    private var c2 = Color.BLACK
    private var c3 = Color.BLACK
    private var c4 = Color.BLACK
    private var playButtonColor = Color.WHITE

    private val syncRunnable = object : Runnable {
        override fun run() {
            val activity = currentActivity ?: return

            // 1. Check for ChromaCanvas
            val root = activity.window.decorView as? ViewGroup
            val hasCanvas = root?.findViewWithTag<View>("CHROMA_CANVAS") != null

            if (activeBackgroundView != null) {
                if (hasCanvas) {
                    activeBackgroundView?.visibility = View.INVISIBLE
                } else {
                    activeBackgroundView?.visibility = View.VISIBLE
                    activeBackgroundView?.invalidate() // Keep orbs spinning
                }
            }

            // 2. Force Play Button Color constantly (Spotify likes to reset it when state changes)
            forcePlayButtonColors(root)

            mainHandler.postDelayed(this, 16)
        }
    }

    fun init(classLoader: ClassLoader) {
        hookMediaSession(classLoader)

        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                if (activity.javaClass.name == NPV_ACTIVITY) {
                    currentActivity = activity
                    injectNpvMods(activity)
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
    }

    private fun injectNpvMods(activity: Activity) {
        val root = activity.window.decorView as? ViewGroup ?: return

        // Wait a tiny bit for the XML layout to inflate
        mainHandler.postDelayed({
            try {
                val containerId = activity.resources.getIdentifier("now_playing_container", "id", activity.packageName)
                if (containerId != 0) {
                    val container = root.findViewById<ViewGroup>(containerId)

                    if (container != null && container.findViewWithTag<View>("V_NPV_GRADIENT_BALLS") == null) {
                        activeBackgroundView = NpvFluidBackgroundView(activity).apply {
                            tag = "V_NPV_GRADIENT_BALLS"
                            layoutParams = FrameLayout.LayoutParams(-1, -1)
                        }

                        // Inject at the very back of the NPV container
                        container.addView(activeBackgroundView, 0)
                        activeBackgroundView?.setColors(c1, c2, c3, c4)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("V-CUSTOM-NPV", "Failed to inject NPV background: ${e.message}")
            }
        }, 300)
    }

    private fun forcePlayButtonColors(view: View?) {
        if (view == null) return

        try {
            // Hunt down the play buttons by ID
            val resId = view.resources.getIdentifier("nowplaying_elements_playpause_button", "id", view.context.packageName)
            if (resId != 0 && view.id == resId && view is ImageView) {
                // Apply the extracted contrast color
                view.imageTintList = ColorStateList.valueOf(playButtonColor)
            }
        } catch (e: Exception) {}

        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                forcePlayButtonColors(view.getChildAt(i))
            }
        }
    }

    private fun hookMediaSession(classLoader: ClassLoader) {
        try {
            val mediaSessionClass = XposedHelpers.findClass("android.media.session.MediaSession", classLoader)
            XposedBridge.hookAllMethods(mediaSessionClass, "setMetadata", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val metadata = param.args[0] as? MediaMetadata ?: return
                    val bitmap = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                        ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)

                    if (bitmap != null) {
                        extractColors(bitmap)
                    }
                }
            })
        } catch (e: Exception) {}
    }

    private fun extractColors(bitmap: Bitmap) {
        try {
            // Downscale to analyze colors fast
            val thumb = Bitmap.createScaledBitmap(bitmap, 4, 4, true)
            val pixels = IntArray(16)
            thumb.getPixels(pixels, 0, 4, 0, 0, 4, 4)

            // 1. Find Average Background Color
            var rSum = 0; var gSum = 0; var bSum = 0
            for (p in pixels) { rSum += Color.red(p); gSum += Color.green(p); bSum += Color.blue(p) }
            val avgColor = Color.rgb(rSum / 16, gSum / 16, bSum / 16)

            // 2. Find Primary Accent (Furthest from Average)
            var maxDist1 = -1
            var accentColor1 = avgColor
            for (p in pixels) {
                val dist = abs(Color.red(p) - Color.red(avgColor)) + abs(Color.green(p) - Color.green(avgColor)) + abs(Color.blue(p) - Color.blue(avgColor))
                if (dist > maxDist1) { maxDist1 = dist; accentColor1 = p }
            }

            // 3. Find Tertiary Contrast Color for the Play Button (Furthest from BOTH Average and Accent 1)
            var maxDist2 = -1
            var accentColor2 = Color.WHITE
            for (p in pixels) {
                val distFromAvg = abs(Color.red(p) - Color.red(avgColor)) + abs(Color.green(p) - Color.green(avgColor)) + abs(Color.blue(p) - Color.blue(avgColor))
                val distFromAcc1 = abs(Color.red(p) - Color.red(accentColor1)) + abs(Color.green(p) - Color.green(accentColor1)) + abs(Color.blue(p) - Color.blue(accentColor1))

                // Weight it heavily so it forces a unique color
                val totalDist = distFromAvg + (distFromAcc1 * 2)

                if (totalDist > maxDist2) { maxDist2 = totalDist; accentColor2 = p }
            }
            thumb.recycle()

            // Process Play Button Color to make sure it pops
            val playHsv = FloatArray(3)
            Color.colorToHSV(accentColor2, playHsv)
            playHsv[1] = (playHsv[1] * 1.5f).coerceIn(0.5f, 1f) // High saturation
            playHsv[2] = (playHsv[2] * 1.5f).coerceIn(0.8f, 1f) // High brightness
            val newPlayColor = Color.HSVToColor(playHsv)

            // Process Gradient Orb Colors
            val bgHsv = FloatArray(3)
            Color.colorToHSV(avgColor, bgHsv)
            val baseHue = bgHsv[0]
            val baseSat = bgHsv[1].coerceIn(0.4f, 1f)

            bgHsv[0] = baseHue; bgHsv[1] = baseSat; bgHsv[2] = 0.85f; val newC1 = Color.HSVToColor(bgHsv)
            bgHsv[0] = (baseHue + 40f) % 360f; bgHsv[1] = baseSat * 0.9f; bgHsv[2] = 0.75f; val newC2 = Color.HSVToColor(bgHsv)
            bgHsv[0] = (baseHue - 30f + 360f) % 360f; bgHsv[1] = baseSat; bgHsv[2] = 0.65f; val newC3 = Color.HSVToColor(bgHsv)
            bgHsv[0] = (baseHue + 15f) % 360f; bgHsv[1] = baseSat * 1.0f; bgHsv[2] = 0.55f; val newC4 = Color.HSVToColor(bgHsv)

            mainHandler.post {
                playButtonColor = newPlayColor
                activeBackgroundView?.animateColorsTo(newC1, newC2, newC3, newC4)
                c1 = newC1; c2 = newC2; c3 = newC3; c4 = newC4
            }
        } catch (e: Exception) {}
    }

    // ====================================================================
    // V'S NPV GRADIENT MESH
    // ====================================================================
    inner class NpvFluidBackgroundView(context: Context) : View(context) {
        private var topL = Color.BLACK; private var topR = Color.BLACK
        private var botL = Color.BLACK; private var botR = Color.BLACK

        private val paint1 = Paint(Paint.ANTI_ALIAS_FLAG)
        private val paint2 = Paint(Paint.ANTI_ALIAS_FLAG)
        private val paint3 = Paint(Paint.ANTI_ALIAS_FLAG)
        private val paint4 = Paint(Paint.ANTI_ALIAS_FLAG)

        private fun transparentColor(c: Int) = Color.argb(0, Color.red(c), Color.green(c), Color.blue(c))
        private fun applyAlpha(c: Int, alpha: Int) = Color.argb(alpha, Color.red(c), Color.green(c), Color.blue(c))

        fun setColors(c1: Int, c2: Int, c3: Int, c4: Int) {
            topL = c1; topR = c2; botL = c3; botR = c4; invalidate()
        }

        fun animateColorsTo(c1: Int, c2: Int, c3: Int, c4: Int) {
            val a1 = ValueAnimator.ofArgb(topL, c1).apply { addUpdateListener { topL = it.animatedValue as Int; invalidate() } }
            val a2 = ValueAnimator.ofArgb(topR, c2).apply { addUpdateListener { topR = it.animatedValue as Int; invalidate() } }
            val a3 = ValueAnimator.ofArgb(botL, c3).apply { addUpdateListener { botL = it.animatedValue as Int; invalidate() } }
            val a4 = ValueAnimator.ofArgb(botR, c4).apply { addUpdateListener { botR = it.animatedValue as Int; invalidate() } }

            android.animation.AnimatorSet().apply {
                playTogether(a1, a2, a3, a4)
                duration = 2000
                interpolator = DecelerateInterpolator(1.5f)
                start()
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            // Using a standard speed modifier here
            val time = SystemClock.elapsedRealtime() * 1.0

            // Base NPV Background
            canvas.drawColor(Color.parseColor("#121212"))

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

            paint1.shader = RadialGradient(x1, y1, rad, applyAlpha(topL, 50), transparentColor(topL), Shader.TileMode.CLAMP)
            paint2.shader = RadialGradient(x2, y2, rad, applyAlpha(topR, 50), transparentColor(topR), Shader.TileMode.CLAMP)
            paint3.shader = RadialGradient(x3, y3, rad, applyAlpha(botL, 50), transparentColor(botL), Shader.TileMode.CLAMP)
            paint4.shader = RadialGradient(x4, y4, rad, applyAlpha(botR, 50), transparentColor(botR), Shader.TileMode.CLAMP)

            canvas.drawCircle(x1, y1, rad, paint1)
            canvas.drawCircle(x2, y2, rad, paint2)
            canvas.drawCircle(x3, y3, rad, paint3)
            canvas.drawCircle(x4, y4, rad, paint4)
        }
    }
}