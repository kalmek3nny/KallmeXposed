package io.github.chsbuffer.revancedxposed.spotify.misc.events

import okhttp3.*
import org.json.JSONArray
import java.io.IOException
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale

data class RadarEvent(val artist: String, val date: String, val venue: String, val location: String, val ticketUrl: String)

object LiveRadarAPI {
    private val client = OkHttpClient()

    fun fetchTourDates(artists: List<String>, callback: (List<RadarEvent>) -> Unit) {
        val results = mutableListOf<RadarEvent>()
        var pending = artists.size

        if (pending == 0) { callback(results); return }

        for (artist in artists) {
            val query = URLEncoder.encode(artist, "UTF-8")
            val url = "https://rest.bandsintown.com/artists/$query/events?app_id=revanced_spotify"
            val request = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build()

            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { checkDone() }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val bodyString = response.body?.string() ?: ""
                        if (response.isSuccessful) {
                            val jsonArray = JSONArray(bodyString)
                            if (jsonArray.length() > 0) {
                                val event = jsonArray.getJSONObject(0)
                                val venueObj = event.getJSONObject("venue")

                                val rawDate = event.getString("datetime")
                                val parsedDate = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).parse(rawDate)
                                val prettyDate = SimpleDateFormat("MMM dd, yyyy", Locale.US).format(parsedDate!!)

                                val venueName = venueObj.getString("name")
                                val location = "${venueObj.getString("city")}, ${venueObj.getString("country")}"
                                val tktUrl = event.getString("url")

                                synchronized(results) {
                                    results.add(RadarEvent(artist, prettyDate, venueName, location, tktUrl))
                                }
                            }
                        }
                    } catch (e: Exception) {}
                    checkDone()
                }

                private fun checkDone() {
                    synchronized(results) {
                        pending--
                        if (pending == 0) callback(results)
                    }
                }
            })
        }
    }
}