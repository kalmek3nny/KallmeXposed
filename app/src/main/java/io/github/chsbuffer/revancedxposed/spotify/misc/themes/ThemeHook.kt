package io.github.chsbuffer.revancedxposed.spotify.misc.themes

import android.content.res.ColorStateList
import android.content.res.Resources
import android.content.res.TypedArray
import android.graphics.Color
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge
import io.github.chsbuffer.revancedxposed.MainActivity

object ThemeHook {

    private var isThemeEnabled = false
    private var isAmoledEnabled = false

    private var customColor = Color.parseColor("#1ED760")
    private var customColorPressed = Color.parseColor("#1ABC54")
    private val amoledPrimary = Color.parseColor("#000000")
    private val amoledSecondary = Color.parseColor("#121212")

    // Target Hex Codes (Cached as Ints for hyper-fast comparison)
    private val targetGreens = intArrayOf(
        Color.parseColor("#1ED760"), Color.parseColor("#1DB954"),
        Color.parseColor("#1ABC54"), Color.parseColor("#1CDF63"),
        Color.parseColor("#2ECA7F")
    )
    private val targetGrays = intArrayOf(
        Color.parseColor("#121212"), Color.parseColor("#181818"), Color.parseColor("#282828")
    )

    // Resource Names targeting exact ReVanced logic
    private val amoledPrimaryTargets = setOf(
        "gray_7", "gray_10", "dark_base_background_base", "dark_base_background_elevated_base",
        "bg_gradient_start_color", "bg_gradient_end_color", "sthlm_blk", "sthlm_blk_grad_start", "image_placeholder_color"
    )
    private val amoledSecondaryTargets = setOf(
        "gray_15", "track_credits_card_bg", "benefit_list_default_color", "merch_card_background",
        "opacity_white_10", "dark_base_background_tinted_highlight"
    )
    private val accentTargets = setOf("dark_brightaccent_background_base", "dark_base_text_brightaccent", "green_light", "spotify_green_157")
    private val accentPressedTargets = setOf("dark_brightaccent_background_press")

    // 🔥 CACHES: This prevents scrolling lag and Exception crashes! 🔥
    private val amoledPrimaryIds = mutableSetOf<Int>()
    private val amoledSecondaryIds = mutableSetOf<Int>()
    private val accentIds = mutableSetOf<Int>()
    private val accentPressedIds = mutableSetOf<Int>()
    private val ignoredIds = mutableSetOf<Int>()

    fun init(classLoader: ClassLoader) {
        val prefs = XSharedPreferences(MainActivity.PACKAGE_NAME, MainActivity.PREF_FILE)
        prefs.makeWorldReadable()

        isThemeEnabled = prefs.getBoolean("enable_custom_theme", false)
        isAmoledEnabled = prefs.getBoolean("enable_amoled_theme", false)
        customColor = prefs.getInt("custom_theme_color", Color.parseColor("#1ED760"))

        val hsv = FloatArray(3)
        Color.colorToHSV(customColor, hsv)
        hsv[2] = (hsv[2] * 0.85f).coerceIn(0f, 1f)
        customColorPressed = Color.HSVToColor(hsv)

        if (!isThemeEnabled && !isAmoledEnabled) return

        try {
            // Changed to an anonymous function so we can use standard 'return'
            val resourceInterceptor = fun(param: XC_MethodHook.MethodHookParam) {
                val id = param.args[0] as? Int ?: return

                // 1. FAST PATH: Check our caches first so we don't look up strings!
                if (!ignoredIds.contains(id)) {
                    if (isAmoledEnabled) {
                        if (amoledPrimaryIds.contains(id)) {
                            param.result = if (param.result is ColorStateList) ColorStateList.valueOf(amoledPrimary) else amoledPrimary
                            return
                        }
                        if (amoledSecondaryIds.contains(id)) {
                            param.result = if (param.result is ColorStateList) ColorStateList.valueOf(amoledSecondary) else amoledSecondary
                            return
                        }
                    }
                    if (isThemeEnabled) {
                        if (accentIds.contains(id)) {
                            param.result = if (param.result is ColorStateList) ColorStateList.valueOf(customColor) else customColor
                            return
                        }
                        if (accentPressedIds.contains(id)) {
                            param.result = if (param.result is ColorStateList) ColorStateList.valueOf(customColorPressed) else customColorPressed
                            return
                        }
                    }

                    // 2. SLOW PATH: First time seeing this ID. Look up its XML name.
                    try {
                        val res = param.thisObject as Resources
                        val resName = res.getResourceEntryName(id)

                        var matched = false
                        if (isAmoledEnabled) {
                            if (amoledPrimaryTargets.contains(resName)) { amoledPrimaryIds.add(id); param.result = if (param.result is ColorStateList) ColorStateList.valueOf(amoledPrimary) else amoledPrimary; matched = true }
                            else if (amoledSecondaryTargets.contains(resName)) { amoledSecondaryIds.add(id); param.result = if (param.result is ColorStateList) ColorStateList.valueOf(amoledSecondary) else amoledSecondary; matched = true }
                        }
                        if (isThemeEnabled && !matched) {
                            if (accentTargets.contains(resName)) { accentIds.add(id); param.result = if (param.result is ColorStateList) ColorStateList.valueOf(customColor) else customColor; matched = true }
                            else if (accentPressedTargets.contains(resName)) { accentPressedIds.add(id); param.result = if (param.result is ColorStateList) ColorStateList.valueOf(customColorPressed) else customColorPressed; matched = true }
                        }

                        // If it didn't match anything, add it to ignored cache so we NEVER check it again
                        if (!matched) ignoredIds.add(id)

                    } catch (e: Exception) {
                        ignoredIds.add(id) // Resource not found, ignore permanently
                    }
                }
            }

            // Hook ALL variants of getColor and getColorStateList (Catches Compose!)
            XposedBridge.hookAllMethods(Resources::class.java, "getColor", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) = resourceInterceptor(param)
            })
            XposedBridge.hookAllMethods(Resources::class.java, "getColorStateList", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) = resourceInterceptor(param)
            })

            // Hook TypedArray to catch colors pulled via Theme Attributes
            XposedBridge.hookAllMethods(TypedArray::class.java, "getColor", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val color = param.result as? Int ?: return
                    if (isThemeEnabled && color in targetGreens) param.result = customColor
                    if (isAmoledEnabled && color in targetGrays) param.result = amoledPrimary
                }
            })

            // Emulate ReVanced's Lottie/Compose String Parser Patch
            if (isThemeEnabled) {
                XposedBridge.hookAllMethods(Color::class.java, "parseColor", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val color = param.result as? Int ?: return
                        if (color in targetGreens) param.result = customColor
                    }
                })

                XposedBridge.hookAllMethods(Color::class.java, "argb", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val color = param.result as? Int ?: return
                        if (color in targetGreens) param.result = customColor
                    }
                })
            }

        } catch (e: Exception) {
            // Failsafe
        }
    }
}