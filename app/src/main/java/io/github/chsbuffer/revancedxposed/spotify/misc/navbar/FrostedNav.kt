package io.github.chsbuffer.revancedxposed.spotify.misc.navbar

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadata
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
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
import kotlin.concurrent.thread

object FrostedNavigation {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var currentActivity: Activity? = null
    private var currentNavColor: Int = Color.parseColor("#D9121212")

    private var cachedPlaylistBitmap: Bitmap? = null
    private var lastLoadedImageUrl: String = ""

    private val frostingRunnable = object : Runnable {
        override fun run() {
            currentActivity?.let { applyFrosting(it) }
            mainHandler.postDelayed(this, 500)
        }
    }

    fun init(app: Application, classLoader: ClassLoader) {
        val prefs = XSharedPreferences(MainActivity.PACKAGE_NAME, MainActivity.PREF_FILE)
        prefs.makeWorldReadable()
        if (!prefs.getBoolean("enable_frosted_nav", true)) return

        hookMediaSession(classLoader)

        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) { currentActivity = activity; mainHandler.post(frostingRunnable) }
            override fun onActivityPaused(activity: Activity) { if (currentActivity == activity) { mainHandler.removeCallbacks(frostingRunnable); currentActivity = null } }
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
            val thumb = Bitmap.createScaledBitmap(bitmap, 1, 1, true)
            val avgColor = thumb.getPixel(0, 0)
            thumb.recycle()

            val hsv = FloatArray(3)
            Color.colorToHSV(avgColor, hsv)
            hsv[1] = (hsv[1] * 1.5f).coerceIn(0f, 1f)
            hsv[2] = hsv[2].coerceAtMost(0.25f)

            val baseColor = Color.HSVToColor(hsv)
            currentNavColor = Color.argb(216, Color.red(baseColor), Color.green(baseColor), Color.blue(baseColor))

            currentActivity?.let { applyFrosting(it) }
        } catch (e: Exception) {}
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

            view.background = GradientDrawable().apply {
                setColor(currentNavColor)
                if (resName == "now_playing_bar_layout") {
                    // Fully rounded on all sides to look like an island
                    cornerRadii = floatArrayOf(45f, 45f, 45f, 45f, 45f, 45f, 45f, 45f)
                } else {
                    // Standard navigation bar styling (bottom flat)
                    cornerRadii = floatArrayOf(45f, 45f, 45f, 45f, 0f, 0f, 0f, 0f)
                }
            }

            // Only pad the navigation bar, do not stretch the island now playing bar
            if (view.paddingTop < 10 && resName != "now_playing_bar_layout") {
                view.setPadding(view.paddingLeft, view.paddingTop + 15, view.paddingRight, view.paddingBottom)
            }

            if (resName == "navigation_bar") {
                // Since our custom tab is ALWAYS visible now (either pinned or empty), always clip bounds
                if (view.isShown) {
                    val screenWidth = activity.resources.displayMetrics.widthPixels
                    val cutoff = (screenWidth * 0.75f).toInt()
                    view.clipBounds = Rect(0, 0, cutoff, view.height + 1000)
                } else {
                    view.clipBounds = null
                }
            }
        }

        if (view is RecyclerView || name.contains("ScrollView")) {
            (view as? ViewGroup)?.clipToPadding = false
            (view as? ViewGroup)?.clipChildren = false
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
                    setTextColor(Color.parseColor("#A7A7A7"))
                    textSize = 10.5f
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    gravity = Gravity.CENTER_HORIZONTAL
                    textAlignment = View.TEXT_ALIGNMENT_CENTER
                    isSingleLine = true
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(textPaddingHorizontal, 0, textPaddingHorizontal, 0)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                }

                addView(icon)
                addView(text)

                // Handlers for the 3-second hold to clear logic
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
                            mainHandler.postDelayed(holdRunnable!!, 3000) // Trigger after EXACTLY 3 seconds
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            holdRunnable?.let { mainHandler.removeCallbacks(it) }
                        }
                    }
                    false // Return false so onClick still receives the tap event
                }

                setOnClickListener {
                    if (isClearedByHold) return@setOnClickListener // Block click if we just cleared it

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

        // --- Handle Image State ---
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

        tab.layoutParams = FrameLayout.LayoutParams(tabWidth, navHeight).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            bottomMargin = bottomInset
        }

        tab.background = GradientDrawable().apply {
            setColor(currentNavColor)
            cornerRadii = floatArrayOf(0f, 0f, 45f, 45f, 0f, 0f, 0f, 0f)
        }

        // Apply dynamic exact title
        val titleToDisplay = if (pinnedUri.isEmpty()) "Pin Playlist" else pinnedTitle
        tab.findViewWithTag<TextView>("PINNED_TEXT")?.text = titleToDisplay
    }
}