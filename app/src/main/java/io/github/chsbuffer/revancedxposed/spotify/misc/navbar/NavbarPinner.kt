package io.github.chsbuffer.revancedxposed.spotify.misc.navbar

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

object NavbarPinner {

    fun init() {
        XposedBridge.hookAllMethods(ClipboardManager::class.java, "setPrimaryClip", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val clip = param.args[0] as? ClipData ?: return
                if (clip.itemCount == 0) return

                val text = clip.getItemAt(0).text?.toString() ?: return

                val match = Regex("https://open\\.spotify\\.com/playlist/([a-zA-Z0-9]+)").find(text)
                if (match != null) {
                    val playlistId = match.groupValues[1]
                    val uri = "spotify:playlist:$playlistId"
                    val playlistUrl = "https://open.spotify.com/playlist/$playlistId"

                    try {
                        val mContextField = ClipboardManager::class.java.getDeclaredField("mContext")
                        mContextField.isAccessible = true
                        val context = mContextField.get(param.thisObject) as? Context ?: return

                        val handler = android.os.Handler(context.mainLooper)
                        handler.post { Toast.makeText(context, "📌 Fetching Playlist Art...", Toast.LENGTH_SHORT).show() }

                        // Scrape the Cover Art silently!
                        thread {
                            try {
                                val connection = URL(playlistUrl).openConnection() as HttpURLConnection
                                connection.requestMethod = "GET"
                                connection.setRequestProperty("User-Agent", "Mozilla/5.0")

                                val html = connection.inputStream.bufferedReader().use { it.readText() }

                                // Extract the high-res cover art from the <meta property="og:image"> tag
                                val imgMatch = Regex("<meta property=\"og:image\" content=\"([^\"]+)\"").find(html)
                                val imgUrl = imgMatch?.groupValues?.get(1) ?: ""

                                // Extract the Playlist Title from the <meta property="og:title"> tag to guarantee exact case sensitivity
                                // This avoids HTML <title> suffixes like "- playlist by..." or "| Spotify"
                                val titleMatch = Regex("<meta property=\"og:title\" content=\"([^\"]+)\"").find(html)
                                val rawTitle = titleMatch?.groupValues?.get(1)?.trim() ?: "Pinned"

                                // Decode basic HTML entities (like &amp; to &) if they exist in the title
                                val title = rawTitle.replace("&amp;", "&").replace("&quot;", "\"").replace("&#039;", "'")

                                val prefs = context.getSharedPreferences("revanced_pinned", Context.MODE_PRIVATE)
                                prefs.edit()
                                    .putString("pinned_uri", uri)
                                    .putString("pinned_title", title)
                                    .putString("pinned_image", imgUrl)
                                    .apply()

                                handler.post { Toast.makeText(context, "✅ Pinned '$title' to Navbar!", Toast.LENGTH_LONG).show() }
                            } catch (e: Exception) {
                                handler.post { Toast.makeText(context, "Failed to scrape playlist data.", Toast.LENGTH_SHORT).show() }
                            }
                        }
                    } catch (e: Exception) {}
                }
            }
        })
    }
}