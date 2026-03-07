package io.github.chsbuffer.revancedxposed.spotify.misc.sampled

import android.content.Context
import okhttp3.*
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class SampleData(
    val type: String,
    val trackName: String,
    val artist: String,
    val url: String
)

object WhoSampledAPI {
    private val client = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private fun log(context: Context, msg: String) {
        try {
            val file = File(context.getExternalFilesDir(null), "whosampled_debug.txt")
            file.appendText("[${SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())}] $msg\n")
        } catch (e: Exception) {}
    }

    private fun stripHtml(html: String): String {
        return html.replace(Regex("""<[^>]*>"""), "")
            .replace("&amp;", "&")
            .replace("&#39;", "'")
            .replace("&quot;", "\"")
            .trim()
    }

    // Custom DOM Parser that doesn't rely on brittle Regex
    private fun extractTracksFromHtml(html: String, maxItems: Int = 100): List<SampleData> {
        val results = mutableListOf<SampleData>()
        var idx = 0

        while (results.size < maxItems) {
            // 1. Find the track link
            idx = html.indexOf("class=\"trackName", idx, ignoreCase = true)
            if (idx == -1) {
                idx = html.indexOf("class='trackName", idx, ignoreCase = true)
                if (idx == -1) break
            }

            val aStart = html.lastIndexOf("<a ", idx, ignoreCase = true)
            val aEnd = html.indexOf("</a>", idx, ignoreCase = true)
            if (aStart == -1 || aEnd == -1) { idx += 10; continue }

            val aTag = html.substring(aStart, aEnd + 4)

            // Extract URL
            val hrefMatch = Regex("""href=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(aTag)
            var path = hrefMatch?.groupValues?.get(1) ?: ""
            if (path.isNotEmpty() && !path.startsWith("/")) path = "/$path"

            // Extract Track Name
            val textStart = aTag.indexOf('>') + 1
            val textEnd = aTag.length - 4
            val trackText = stripHtml(aTag.substring(textStart, textEnd))

            // 2. Find the Artist link that immediately follows it
            var artistText = "Unknown Artist"
            val artistClassIdx = html.indexOf("trackArtist", aEnd, ignoreCase = true)

            // Ensure the artist tag belongs to this track (should be very close in the HTML)
            if (artistClassIdx != -1 && artistClassIdx - aEnd < 300) {
                val artistAStart = html.indexOf("<a", artistClassIdx, ignoreCase = true)
                val artistAEnd = html.indexOf("</a>", artistAStart, ignoreCase = true)

                if (artistAStart != -1 && artistAEnd != -1) {
                    val artistATag = html.substring(artistAStart, artistAEnd + 4)
                    val artTextStart = artistATag.indexOf('>') + 1
                    val artTextEnd = artistATag.length - 4
                    artistText = stripHtml(artistATag.substring(artTextStart, artTextEnd))
                }
            }

            if (path.isNotEmpty() && trackText.isNotEmpty()) {
                val fullUrl = "https://www.whosampled.com$path"
                results.add(SampleData("SEARCH_RESULT", trackText, artistText, fullUrl))
            }

            idx = aEnd
        }
        return results
    }

    fun fetchSamples(context: Context, title: String, artist: String, callback: (List<SampleData>) -> Unit) {
        val cleanArtist = artist.split(",")[0].split("&")[0].trim()
        val cleanTitle = title.replace(Regex("(?i)\\s*\\(.*?(feat|ft|with|prod).*?\\)"), "")
            .replace(Regex("(?i)\\s*-.*"), "")
            .trim()

        val query = URLEncoder.encode("$cleanTitle $cleanArtist", "UTF-8")
        performSearch(context, query, true, cleanTitle, cleanArtist, callback)
    }

    private fun performSearch(context: Context, query: String, isFirstAttempt: Boolean, targetTitle: String, targetArtist: String, callback: (List<SampleData>) -> Unit) {
        val searchUrl = "https://www.whosampled.com/search/?q=$query"

        log(context, "===================================")
        log(context, "${if(isFirstAttempt) "1" else "2"}. Searching: $query")
        log(context, "URL: $searchUrl")

        val request = Request.Builder()
            .url(searchUrl)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                log(context, "❌ Search Network Failure: ${e.message}")
                callback(emptyList())
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val html = response.body?.string() ?: ""
                    val currentUrl = response.request.url.toString()

                    if (!response.isSuccessful) {
                        log(context, "❌ HTTP Error: ${response.code}")
                        callback(emptyList())
                        return
                    }

                    if (!currentUrl.contains("/search/")) {
                        log(context, "➡️ Auto-Redirected to exact match: $currentUrl")
                        parseTrackDetailsHtml(context, html, callback)
                        return
                    }

                    val parsedResults = extractTracksFromHtml(html)

                    var bestUrl: String? = null
                    var bestName = ""
                    var bestScore = -999

                    val lowerTargetTitle = targetTitle.lowercase()
                    val lowerTargetArtist = targetArtist.lowercase()

                    for (res in parsedResults) {
                        val lowerText = res.trackName.lowercase()
                        val lowerArt = res.artist.lowercase()
                        var score = 0

                        // Score the Title
                        if (lowerText == lowerTargetTitle) score += 100
                        else if (lowerText.contains(lowerTargetTitle)) score += 50

                        // Score the Artist (CRITICAL FIX: Solves the Jon Connor bug)
                        if (lowerArt == lowerTargetArtist) score += 100
                        else if (lowerArt.contains(lowerTargetArtist) || lowerTargetArtist.contains(lowerArt)) score += 80
                        else score -= 150 // Massive penalty if it's the wrong artist!

                        // Penalties for garbage versions
                        if (lowerText.contains("unreleased") && !lowerTargetTitle.contains("unreleased")) score -= 100
                        if (lowerText.contains("remix") && !lowerTargetTitle.contains("remix")) score -= 80
                        if (lowerText.contains("live") && !lowerTargetTitle.contains("live")) score -= 50

                        log(context, "   -> Found: '${res.trackName}' by '${res.artist}' | Score: $score | Path: ${res.url}")

                        if (score > bestScore) {
                            bestScore = score
                            bestUrl = res.url
                            bestName = "${res.trackName} by ${res.artist}"
                        }
                    }

                    if (bestUrl != null && bestScore >= 0) {
                        log(context, "✅ Selected Track: '$bestName' (Winning Score: $bestScore)")
                        fetchTrackDetails(context, bestUrl, callback)
                    } else {
                        log(context, "❌ Search returned 0 viable tracks (All scores < 0).")

                        if (isFirstAttempt) {
                            log(context, "🔄 Fallback: Searching just the track title...")
                            val fallbackQuery = URLEncoder.encode(targetTitle, "UTF-8")
                            performSearch(context, fallbackQuery, false, targetTitle, targetArtist, callback)
                        } else {
                            callback(emptyList())
                        }
                    }
                } catch (e: Exception) {
                    log(context, "❌ Search Parse Exception: ${e.message}")
                    callback(emptyList())
                }
            }
        })
    }

    private fun fetchTrackDetails(context: Context, url: String, callback: (List<SampleData>) -> Unit) {
        log(context, "3. Fetching Details from: $url")
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { callback(emptyList()) }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val html = response.body?.string() ?: ""
                    if (!response.isSuccessful) {
                        callback(emptyList())
                        return
                    }
                    parseTrackDetailsHtml(context, html, callback)
                } catch (e: Exception) { callback(emptyList()) }
            }
        })
    }

    private fun parseTrackDetailsHtml(context: Context, html: String, callback: (List<SampleData>) -> Unit) {
        val finalResults = mutableListOf<SampleData>()
        try {
            fun scrapeSection(headerRegexStr: String, typeName: String) {
                val headerMatch = Regex(headerRegexStr).find(html)
                if (headerMatch != null) {
                    val blockStartIndex = headerMatch.range.last
                    val nextHeaderIndex = html.indexOf("section-header-title", blockStartIndex)
                    val sectionHtml = if (nextHeaderIndex != -1) html.substring(blockStartIndex, nextHeaderIndex) else html.substring(blockStartIndex)

                    // Reuse our rock-solid string parser, but limit it to 4 results per section
                    val items = extractTracksFromHtml(sectionHtml, 4)

                    log(context, "-> Found ${items.size} items for '$typeName'")
                    for (item in items) {
                        finalResults.add(SampleData(typeName, item.trackName, item.artist, item.url))
                    }
                }
            }

            scrapeSection("""(?i)Contains (?:a )?samples? of""", "Contains Sample Of")
            scrapeSection("""(?i)Was sampled in""", "Sampled In")
            scrapeSection("""(?i)Covered in""", "Covered By")
            scrapeSection("""(?i)Contains (?:an )?interpolations? of""", "Interpolates")

            log(context, "✅ SUCCESS! Extracted ${finalResults.size} total connections.")
            callback(finalResults)
        } catch (e: Exception) {
            log(context, "❌ Parse Details Error: ${e.message}")
            callback(finalResults)
        }
    }
}