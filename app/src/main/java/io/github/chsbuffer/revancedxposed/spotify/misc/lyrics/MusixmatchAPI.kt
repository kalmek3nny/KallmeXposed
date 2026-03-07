package io.github.chsbuffer.revancedxposed.spotify.misc.lyrics

import android.content.Context
import okhttp3.*
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object MusixmatchAPI {
    private val client = OkHttpClient()

    private var USER_TOKEN = ""
    private const val APP_ID = "web-desktop-app-v1.0"

    fun getLyrics(context: Context, title: String, artist: String, callback: (List<LyricLine>?) -> Unit) {
        log(context, "--- REQUEST: $title - $artist ---")

        if (USER_TOKEN.isEmpty() || USER_TOKEN == "Token not generated") {
            fetchNewToken(context) { success ->
                if (success) {
                    performSearch(context, title, artist, callback)
                } else {
                    log(context, "❌ Token Generation Failed")
                    callback(null)
                }
            }
        } else {
            performSearch(context, title, artist, callback)
        }
    }

    private fun fetchNewToken(context: Context, callback: (Boolean) -> Unit) {
        log(context, "🔄 Fetching fresh Musixmatch Token...")
        val url = "https://apic-desktop.musixmatch.com/ws/1.1/token.get?app_id=$APP_ID"

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { callback(false) }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val body = response.body?.string() ?: ""
                    val json = JSONObject(body)
                    val token = json.getJSONObject("message").getJSONObject("body").getString("user_token")

                    if (token != "Token not generated") {
                        USER_TOKEN = token
                        log(context, "✅ Got New Token: $USER_TOKEN")
                        callback(true)
                    } else {
                        callback(false)
                    }
                } catch (e: Exception) { callback(false) }
            }
        })
    }

    private fun performSearch(context: Context, title: String, artist: String, callback: (List<LyricLine>?) -> Unit) {
        val cleanArtist = artist.split(",")[0].split("&")[0].trim()
        val cleanTitle = title.replace(Regex("(?i)\\s*\\(.*?(feat|ft|with|prod).*?\\)"), "")
            .replace(Regex("(?i)\\s*-.*"), "")
            .trim()

        val qTrack = URLEncoder.encode(cleanTitle, "UTF-8")
        val qArtist = URLEncoder.encode(cleanArtist, "UTF-8")

        val url = "https://apic-desktop.musixmatch.com/ws/1.1/track.search?" +
                "app_id=$APP_ID&usertoken=$USER_TOKEN" +
                "&q_track=$qTrack&q_artist=$qArtist" +
                "&page_size=1&page=1&s_track_rating=desc&quorum_factor=1"

        val request = Request.Builder()
            .url(url)
            .header("Cookie", "x-mxm-token-guid=$USER_TOKEN")
            .header("User-Agent", "Mozilla/5.0")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { callback(null) }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val body = response.body?.string() ?: ""
                    val message = JSONObject(body).getJSONObject("message")
                    val header = message.getJSONObject("header")

                    if (header.getInt("status_code") == 401) {
                        log(context, "⚠️ Token Expired or Banned! Regenerating...")
                        USER_TOKEN = ""
                        getLyrics(context, title, artist, callback)
                        return
                    }

                    val bodyObj = message.optJSONObject("body")
                    val trackList = bodyObj?.optJSONArray("track_list")

                    if (trackList != null && trackList.length() > 0) {
                        val id = trackList.getJSONObject(0).getJSONObject("track").getString("track_id")
                        log(context, "✅ Found MXM Track ID: $id (Using: $cleanTitle - $cleanArtist)")
                        fetchMusixmatchLyrics(context, id, title, artist, callback)
                    } else {
                        log(context, "❌ Search returned 0 results for: $cleanTitle - $cleanArtist")
                        callback(null)
                    }
                } catch (e: Exception) {
                    log(context, "❌ Parse error in search: ${e.message}")
                    callback(null)
                }
            }
        })
    }

    private fun fetchMusixmatchLyrics(context: Context, trackId: String, originalTitle: String, originalArtist: String, callback: (List<LyricLine>?) -> Unit) {
        val url = "https://apic-desktop.musixmatch.com/ws/1.1/track.subtitles.get?" +
                "app_id=$APP_ID&usertoken=$USER_TOKEN&track_id=$trackId&subtitle_format=lrc"

        val request = Request.Builder()
            .url(url)
            .header("Cookie", "x-mxm-token-guid=$USER_TOKEN")
            .header("User-Agent", "Mozilla/5.0")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { callback(null) }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val body = response.body?.string() ?: ""
                    val message = JSONObject(body).getJSONObject("message")
                    val header = message.getJSONObject("header")

                    if (header.getInt("status_code") == 401) {
                        log(context, "⚠️ Token Expired during fetch! Regenerating...")
                        USER_TOKEN = ""
                        getLyrics(context, originalTitle, originalArtist, callback)
                        return
                    }

                    val bodyObj = message.optJSONObject("body")
                    var rawLyrics = ""

                    val list = bodyObj?.optJSONArray("subtitle_list")
                    if (list != null && list.length() > 0) {
                        val subtitle = list.getJSONObject(0).optJSONObject("subtitle")
                        rawLyrics = subtitle?.optString("subtitle_body", "") ?: ""
                    }
                    else if (bodyObj?.has("subtitle_body") == true) {
                        rawLyrics = bodyObj.optString("subtitle_body", "")
                    }

                    if (rawLyrics.isNotEmpty()) {
                        log(context, "✅ SUCCESS! Parsed LRC data.")
                        callback(parseLrc(rawLyrics))
                    } else {
                        log(context, "❌ Song found, but LRC string was empty.")
                        callback(null)
                    }
                } catch (e: Exception) {
                    log(context, "❌ Crash during LRC fetch: ${e.message}")
                    callback(null)
                }
            }
        })
    }

    private fun parseLrc(lrc: String): List<LyricLine> {
        val rawLines = mutableListOf<Pair<Long, String>>()
        val regex = Regex("\\[(\\d{2}):(\\d{2})\\.(\\d{2,3})\\](.*)")

        // Pass 1: Extract all standard timestamps and string content
        for (line in lrc.split("\n")) {
            val match = regex.find(line)
            if (match != null) {
                val min = match.groupValues[1].toLong()
                val sec = match.groupValues[2].toLong()
                var msStr = match.groupValues[3]
                if (msStr.length == 2) msStr += "0"
                val ms = msStr.toLong()
                val text = match.groupValues[4].trim()

                if (text.isNotEmpty()) {
                    val timeMs = (min * 60000) + (sec * 1000) + ms
                    rawLines.add(Pair(timeMs, text))
                }
            }
        }

        val lines = mutableListOf<LyricLine>()
        // Regex to separate text inside parentheses from text outside parentheses
        val partRegex = Regex("\\(.*?\\)|[^()]+")

        // Pass 2: Break lines into parts (Main lyrics vs (Adlibs))
        for (i in rawLines.indices) {
            val currentLine = rawLines[i]
            val nextTimeMs = if (i + 1 < rawLines.size) rawLines[i + 1].first else currentLine.first + 5000L

            val parts = partRegex.findAll(currentLine.second)
                .map { it.value.trim() }
                // This completely prevents stray commas and punctuation from becoming their own lyric line!
                .filter { it.any { char -> char.isLetterOrDigit() } }
                .toList()

            // If there are no adlibs on this line, add normally
            if (parts.size <= 1) {
                lines.add(LyricLine(currentLine.first, currentLine.second))
            } else {
                // Inline adlibs found! Calculate their character proportions so
                // we can dynamically offset their timestamps for perfect sweeping.
                val totalLength = parts.sumOf { it.length }.toFloat()
                var currentTime = currentLine.first
                val availableTime = (nextTimeMs - currentLine.first).coerceAtLeast(parts.size * 250L)

                for (part in parts) {
                    lines.add(LyricLine(currentTime, part))
                    // Give each string segment a proportional chunk of the available sweeping time
                    val durationForPart = ((part.length / totalLength) * availableTime).toLong().coerceAtLeast(150L)
                    currentTime += durationForPart
                }
            }
        }
        return lines
    }

    private fun log(context: Context, msg: String) {
        try {
            val file = File(context.getExternalFilesDir(null), "musixmatch_debug.txt")
            file.appendText("[${SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())}] $msg\n")
        } catch (e: Exception) {}
    }
}