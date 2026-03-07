package io.github.chsbuffer.revancedxposed.spotify

import android.app.Application
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import io.github.chsbuffer.revancedxposed.BaseHook
import io.github.chsbuffer.revancedxposed.MainActivity
import de.robv.android.xposed.XSharedPreferences
import io.github.chsbuffer.revancedxposed.injectHostClassLoaderToSelf
import io.github.chsbuffer.revancedxposed.spotify.misc.UnlockPremium
import io.github.chsbuffer.revancedxposed.spotify.misc.privacy.SanitizeSharingLinks
import io.github.chsbuffer.revancedxposed.spotify.misc.widgets.FixThirdPartyLaunchersWidgets
import io.github.chsbuffer.revancedxposed.spotify.misc.spoofs.InstallDeviceSpoofer
import io.github.chsbuffer.revancedxposed.spotify.misc.themes.ThemeHook
import io.github.chsbuffer.revancedxposed.spotify.misc.navbar.FrostedNavigation
import io.github.chsbuffer.revancedxposed.spotify.misc.events.LiveRadar
import io.github.chsbuffer.revancedxposed.spotify.misc.navbar.NavbarPinner
import io.github.chsbuffer.revancedxposed.spotify.misc.themes.ChromaCanvas
import io.github.chsbuffer.revancedxposed.spotify.misc.sampled.WhoSampled

@Suppress("UNCHECKED_CAST")
class SpotifyHook(val app: Application, lpparam: LoadPackageParam) : BaseHook(app, lpparam) {

    // 🎯 FIX: Initialize once, but create a helper to reload it before every use
    private val sharedPrefs by lazy {
        XSharedPreferences(MainActivity.PACKAGE_NAME, MainActivity.PREF_FILE).apply {
            makeWorldReadable()
        }
    }

    private fun getFreshPrefs(): XSharedPreferences {
        sharedPrefs.reload() // Force read from disk
        return sharedPrefs
    }

    override val hooks = arrayOf(
        ::Extension,
        ::HookUnlockPremium,
        ::HookPrivacyMisc,
        ::HookInterfaceInjections,
        ::HookBeautifulLyrics,
        ::HookAppTheme
    )

    fun Extension() {
        injectHostClassLoaderToSelf(this::class.java.classLoader!!, classLoader)
    }

    fun HookUnlockPremium() {
        val p = getFreshPrefs()
        if (p.getBoolean("enable_premium", true)) {
            UnlockPremium(p) // Pass fresh prefs to the premium payload
        }
    }

    fun HookPrivacyMisc() {
        val p = getFreshPrefs()
        if (p.getBoolean("enable_device_spoofer", true)) this.InstallDeviceSpoofer()
        if (p.getBoolean("enable_sanitize_links", true)) SanitizeSharingLinks()
        if (p.getBoolean("enable_widget_fix", true)) FixThirdPartyLaunchersWidgets()
    }

    fun HookInterfaceInjections() {
        val p = getFreshPrefs()
        if (p.getBoolean("enable_behind_the_tracks", true)) {
            io.github.chsbuffer.revancedxposed.spotify.misc.artists.BehindTheTracks(app).init(classLoader)
        }
        if (p.getBoolean("enable_live_radar", true)) {
            io.github.chsbuffer.revancedxposed.spotify.misc.events.LiveRadar(app).init(classLoader)
        }
        if (p.getBoolean("enable_frosted_nav", true)) {
            io.github.chsbuffer.revancedxposed.spotify.misc.navbar.FrostedNavigation.init(app, classLoader)
        }
        if (p.getBoolean("enable_navbar_pinner", true)) {
            io.github.chsbuffer.revancedxposed.spotify.misc.navbar.NavbarPinner.init()
        }
        if (p.getBoolean("enable_who_sampled", true)) {
            WhoSampled(app).init(classLoader)
        }
    }

    fun HookBeautifulLyrics() {
        val p = getFreshPrefs()
        if (p.getBoolean("enable_lyrics", true)) {
            io.github.chsbuffer.revancedxposed.spotify.misc.lyrics.BeautifulLyrics(app).init(classLoader)
        }
    }

    fun HookAppTheme() {
        val p = getFreshPrefs()
        io.github.chsbuffer.revancedxposed.spotify.misc.themes.ThemeHook.init(classLoader)

        if (p.getBoolean("enable_chroma_canvas", false)) {
            ChromaCanvas(app).init(classLoader)
        }
    }
}
