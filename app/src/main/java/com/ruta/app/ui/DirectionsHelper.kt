package com.ruta.app.ui

import com.google.android.gms.maps.model.LatLng
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

object DirectionsHelper {

    // Builds the URL string required for Google Directions API requests
    fun getDirectionsUrl(origin: LatLng, destination: LatLng, apiKey: String): String {
        val strOrigin = "origin=${origin.latitude},${origin.longitude}"
        val strDest = "destination=${destination.latitude},${destination.longitude}"
        val mode = "mode=driving"
        val params = "$strOrigin&$strDest&$mode&key=$apiKey"
        return "https://maps.googleapis.com/maps/api/directions/json?$params"
    }

    // Fetches JSON response from Google Directions API
    fun downloadUrl(urlString: String): String {
        var data = ""
        val url = URL(urlString)
        val urlConnection = url.openConnection() as HttpURLConnection
        try {
            urlConnection.connect()
            val inputStream = urlConnection.inputStream
            val reader = BufferedReader(InputStreamReader(inputStream))
            val sb = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                sb.append(line)
            }
            data = sb.toString()
            reader.close()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            urlConnection.disconnect()
        }
        return data
    }

    // Parses JSON and extracts list of LatLng coordinates for the road route
    fun parseDirections(jsonString: String): List<LatLng> {
        val path = ArrayList<LatLng>()
        try {
            val jsonObject = JSONObject(jsonString)
            val routes = jsonObject.getJSONArray("routes")
            if (routes.length() == 0) return path

            val points = routes.getJSONObject(0)
                .getJSONObject("overview_polyline")
                .getString("points")

            return decodePolyline(points)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return path
    }

    // Parses the turn-by-turn steps (first route, first leg) for voice guidance.
    fun parseSteps(jsonString: String): List<com.ruta.app.util.NavigationLogic.Step> {
        val out = ArrayList<com.ruta.app.util.NavigationLogic.Step>()
        try {
            val routes = JSONObject(jsonString).getJSONArray("routes")
            if (routes.length() == 0) return out
            val legs = routes.getJSONObject(0).getJSONArray("legs")
            for (l in 0 until legs.length()) {
                val steps = legs.getJSONObject(l).getJSONArray("steps")
                for (i in 0 until steps.length()) {
                    val st = steps.getJSONObject(i)
                    val a = st.getJSONObject("start_location")
                    val b = st.getJSONObject("end_location")
                    out.add(
                        com.ruta.app.util.NavigationLogic.Step(
                            a.getDouble("lat"), a.getDouble("lng"),
                            b.getDouble("lat"), b.getDouble("lng"),
                            st.optJSONObject("distance")?.optInt("value", 0) ?: 0,
                            st.optString("maneuver", ""),
                            st.optString("html_instructions", "")
                        )
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return out
    }

    // Decodes Google's encoded polyline string format
    fun decodePolyline(encoded: String): List<LatLng> {
        val poly = ArrayList<LatLng>()
        var index = 0
        val len = encoded.length
        var lat = 0
        var lng = 0

        while (index < len) {
            var b: Int
            var shift = 0
            var result = 0
            do {
                b = encoded[index++].code - 63
                result = result or (b and 0x1f shl shift)
                shift += 5
            } while (b >= 0x20)
            val dlat = if (result and 1 != 0) (result shr 1).inv() else result shr 1
            lat += dlat

            shift = 0
            result = 0
            do {
                b = encoded[index++].code - 63
                result = result or (b and 0x1f shl shift)
                shift += 5
            } while (b >= 0x20)
            val dlng = if (result and 1 != 0) (result shr 1).inv() else result shr 1
            lng += dlng

            val p = LatLng(lat.toDouble() / 1E5, lng.toDouble() / 1E5)
            poly.add(p)
        }
        return poly
    }
}