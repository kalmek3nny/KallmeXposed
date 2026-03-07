package io.github.chsbuffer.revancedxposed.spotify.misc.artists

import android.content.Context
import okhttp3.*
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object GeniusAPI {
    private val client = OkHttpClient()

    private fun log(context: Context, msg: String) {
        try {
            val file = File(context.getExternalFilesDir(null), "genius_debug.txt")
            file.appendText("[${SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())}] $msg\n")
        } catch (e: Exception) {}
    }

    fun fetchTrackData(context: Context, title: String, artist: String, callback: (GeniusData?) -> Unit) {
        val cleanArtist = artist.split(",")[0].split("&")[0].trim()
        val cleanTitle = title.replace(Regex("(?i)\\s*\\(.*?(feat|ft|with|prod).*?\\)"), "").trim()
        val query = URLEncoder.encode("$cleanTitle $cleanArtist", "UTF-8")

        val searchUrl = "https://genius.com/api/search/multi?per_page=1&q=$query"
        log(context, "===================================")
        log(context, "1. Fetching Search: $cleanTitle - $cleanArtist")
        log(context, "URL: $searchUrl")

        val request = Request.Builder()
            .url(searchUrl)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
            .header("Accept", "application/json")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                log(context, "❌ Search Network Failure: ${e.message}")
                callback(null)
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val bodyString = response.body?.string() ?: ""
                    if (!response.isSuccessful) {
                        log(context, "❌ Search HTTP Error: ${response.code} - $bodyString")
                        callback(null)
                        return
                    }

                    val json = JSONObject(bodyString)
                    val sections = json.getJSONObject("response").getJSONArray("sections")

                    var songId = ""
                    var url = ""

                    // Safely find the "song" section (Genius sometimes randomizes the order)
                    for (i in 0 until sections.length()) {
                        val section = sections.getJSONObject(i)
                        if (section.getString("type") == "song") {
                            val hits = section.getJSONArray("hits")
                            if (hits.length() > 0) {
                                val result = hits.getJSONObject(0).getJSONObject("result")
                                songId = result.getString("id")
                                url = result.getString("url")
                                break
                            }
                        }
                    }

                    if (songId.isNotEmpty()) {
                        log(context, "✅ Found Song ID: $songId")
                        fetchSongDetails(context, songId, url, callback)
                    } else {
                        log(context, "❌ Search returned 0 song hits.")
                        callback(null)
                    }
                } catch (e: Exception) {
                    log(context, "❌ Search Parse Exception: ${e.message}")
                    callback(null)
                }
            }
        })
    }

    private fun fetchSongDetails(context: Context, songId: String, url: String, callback: (GeniusData?) -> Unit) {
        val detailUrl = "https://genius.com/api/songs/$songId?text_format=plain"
        log(context, "2. Fetching Details...")

        val request = Request.Builder()
            .url(detailUrl)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
            .header("Accept", "application/json")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                log(context, "❌ Details Network Failure: ${e.message}")
                callback(null)
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val bodyString = response.body?.string() ?: ""
                    if (!response.isSuccessful) {
                        log(context, "❌ Details HTTP Error: ${response.code}")
                        callback(null)
                        return
                    }

                    val songObj = JSONObject(bodyString).getJSONObject("response").getJSONObject("song")

                    val aboutText = songObj.optJSONObject("description")?.optString("plain", "")?.trim() ?: ""
                    val releaseDate = songObj.optString("release_date_for_display", "Unknown Date")

                    val producersArray = songObj.optJSONArray("producer_artists")
                    val producers = mutableListOf<String>()
                    if (producersArray != null) {
                        for (i in 0 until producersArray.length()) {
                            producers.add(producersArray.getJSONObject(i).getString("name"))
                        }
                    }

                    log(context, "✅ Parsed Details successfully. Fetching Annotations...")
                    fetchAnnotations(context, songId, url, aboutText, releaseDate, producers.joinToString(", "), callback)
                } catch (e: Exception) {
                    log(context, "❌ Details Parse Exception: ${e.message}")
                    callback(null)
                }
            }
        })
    }

    private fun fetchAnnotations(context: Context, songId: String, url: String, aboutText: String, releaseDate: String, producers: String, callback: (GeniusData?) -> Unit) {
        val annUrl = "https://genius.com/api/referents?song_id=$songId&text_format=plain"
        log(context, "3. Fetching Annotations...")

        val request = Request.Builder()
            .url(annUrl)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
            .header("Accept", "application/json")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                log(context, "❌ Annotations Network Failure: ${e.message}")
                callback(null)
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val bodyString = response.body?.string() ?: ""
                    if (!response.isSuccessful) {
                        log(context, "❌ Annotations HTTP Error: ${response.code}")
                        callback(null)
                        return
                    }

                    val refArray = JSONObject(bodyString).getJSONObject("response").getJSONArray("referents")
                    val annotations = mutableListOf<GeniusAnnotation>()

                    val limit = Math.min(refArray.length(), 5)
                    for (i in 0 until limit) {
                        val refObj = refArray.getJSONObject(i)
                        val fragment = refObj.getString("fragment")

                        val annArray = refObj.optJSONArray("annotations")
                        if (annArray != null && annArray.length() > 0) {
                            val explanation = annArray.getJSONObject(0).getJSONObject("body").getString("plain")
                            if (explanation.isNotEmpty() && !explanation.contains("?")) {
                                annotations.add(GeniusAnnotation(fragment, explanation))
                            }
                        }
                    }

                    log(context, "✅ SUCCESS! Formatted ${annotations.size} annotations.")
                    callback(GeniusData(songId, url, aboutText, releaseDate, producers, annotations))
                } catch (e: Exception) {
                    log(context, "❌ Annotations Parse Exception: ${e.message}")
                    callback(null)
                }
            }
        })
    }
}