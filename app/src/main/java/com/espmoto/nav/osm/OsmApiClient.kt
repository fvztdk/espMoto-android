package com.espmoto.nav.osm

import com.espmoto.nav.ble.ManeuverType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale

object OsmApiClient {
    private const val USER_AGENT = "espMoto/0.2.0 (Android; OSM)"
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 12_000

    suspend fun resolveSharedLocation(
        rawText: String,
        subject: String? = null
    ): Result<NominatimPlace> = withContext(Dispatchers.IO) {
        runCatching {
            val trimmed = rawText.trim()
            require(trimmed.isNotEmpty()) { "Shared location text is empty" }

            var resolvedUrl: String? = null
            var coord = extractCoordsFromText(trimmed)

            val urlMatch = Regex("""https?://\S+""").find(trimmed)
            if (coord == null && urlMatch != null) {
                val (finalUrl, body) = followRedirectsAndPeek(urlMatch.value)
                resolvedUrl = finalUrl
                coord = extractCoordsFromText(finalUrl)
                    ?: body?.let { extractCoordsFromText(it) }
            }

            val label = extractSharedLabel(
                rawText = trimmed,
                subject = subject,
                resolvedUrl = resolvedUrl
            )

            if (coord != null) {
                NominatimPlace(
                    displayName = label,
                    shortName = label,
                    coord = coord
                )
            } else {
                val query = extractSearchQueryFallback(trimmed, resolvedUrl, label)
                val results = searchAddress(query).getOrThrow()
                results.firstOrNull()
                    ?: throw IllegalStateException("Could not resolve shared location: $query")
            }
        }
    }

    private fun extractCoordsFromText(text: String): GeoCoord? {
        // 1. Google Maps exact pin (!3dLAT!4dLON)
        Regex("""!3d(-?\d+\.\d+)!4d(-?\d+\.\d+)""").find(text)?.let { m ->
            val lat = m.groupValues[1].toDoubleOrNull()
            val lon = m.groupValues[2].toDoubleOrNull()
            if (isValidNonZeroCoord(lat, lon)) return GeoCoord(lat!!, lon!!)
        }

        // 2. geo:LAT,LON URI
        Regex("""geo:(-?\d+\.\d+),(-?\d+\.\d+)""").find(text)?.let { m ->
            val lat = m.groupValues[1].toDoubleOrNull()
            val lon = m.groupValues[2].toDoubleOrNull()
            if (isValidNonZeroCoord(lat, lon)) return GeoCoord(lat!!, lon!!)
        }

        // 3. Query params q=LAT,LON / query=LAT,LON / ll=LAT,LON / center=LAT,LON
        Regex("""[?&](?:q|query|ll|center)=(-?\d+\.\d+)(?:,|%2C)(-?\d+\.\d+)""", RegexOption.IGNORE_CASE)
            .find(text)?.let { m ->
                val lat = m.groupValues[1].toDoubleOrNull()
                val lon = m.groupValues[2].toDoubleOrNull()
                if (isValidNonZeroCoord(lat, lon)) return GeoCoord(lat!!, lon!!)
            }

        // 4. Google Maps viewport @LAT,LON
        Regex("""@(-?\d+\.\d+),(-?\d+\.\d+)""").find(text)?.let { m ->
            val lat = m.groupValues[1].toDoubleOrNull()
            val lon = m.groupValues[2].toDoubleOrNull()
            if (isValidNonZeroCoord(lat, lon)) return GeoCoord(lat!!, lon!!)
        }

        return null
    }

    private fun isValidNonZeroCoord(lat: Double?, lon: Double?): Boolean {
        if (lat == null || lon == null) return false
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return false
        return !(lat == 0.0 && lon == 0.0)
    }

    private fun followRedirectsAndPeek(startUrl: String): Pair<String, String?> {
        var current = startUrl
        repeat(6) {
            val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
            }
            try {
                val code = conn.responseCode
                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location") ?: return current to null
                    current = URL(URL(current), loc).toString()
                } else {
                    val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                    val snippet = stream?.use { input ->
                        val buf = CharArray(65536)
                        val reader = InputStreamReader(input, Charsets.UTF_8)
                        val read = reader.read(buf)
                        if (read > 0) String(buf, 0, read) else null
                    }
                    return current to snippet
                }
            } finally {
                conn.disconnect()
            }
        }
        return current to null
    }

    private fun extractSharedLabel(
        rawText: String,
        subject: String?,
        resolvedUrl: String?
    ): String {
        val cleanSubject = subject?.trim()?.takeIf {
            it.isNotEmpty() && !it.startsWith("http", ignoreCase = true)
        }
        if (cleanSubject != null) return cleanSubject

        // geo:lat,lon?q=lat,lon(Label)
        Regex("""\(([^)]+)\)""").find(rawText)?.let { m ->
            val decoded = runCatching { URLDecoder.decode(m.groupValues[1], "UTF-8") }.getOrNull()?.trim()
            if (!decoded.isNullOrEmpty()) return decoded
        }

        val textLines = rawText.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("http", ignoreCase = true) && !it.startsWith("geo:", ignoreCase = true) }
        if (textLines.isNotEmpty()) {
            return textLines.first()
        }

        if (resolvedUrl != null) {
            Regex("""/maps/place/([^/]+)/""").find(resolvedUrl)?.let { m ->
                val decoded = runCatching { URLDecoder.decode(m.groupValues[1], "UTF-8") }.getOrNull()
                    ?.replace('+', ' ')?.trim()
                if (!decoded.isNullOrEmpty()) return decoded
            }
            Regex("""[?&](?:q|query)=([^&]+)""").find(resolvedUrl)?.let { m ->
                val decoded = runCatching { URLDecoder.decode(m.groupValues[1], "UTF-8") }.getOrNull()
                    ?.replace('+', ' ')?.trim()
                if (!decoded.isNullOrEmpty() && !Regex("""^-?\d+\.\d+,-?\d+\.\d+$""").matches(decoded)) {
                    return decoded
                }
            }
        }

        return "Shared Location"
    }

    private fun extractSearchQueryFallback(
        rawText: String,
        resolvedUrl: String?,
        label: String
    ): String {
        if (label != "Shared Location") return label
        val target = resolvedUrl ?: rawText
        Regex("""[?&](?:q|query)=([^&]+)""").find(target)?.let { m ->
            val decoded = runCatching { URLDecoder.decode(m.groupValues[1], "UTF-8") }.getOrNull()
                ?.replace('+', ' ')?.trim()
            if (!decoded.isNullOrEmpty()) return decoded
        }
        return rawText.replace(Regex("""https?://\S+"""), "").trim().ifEmpty { rawText }
    }

    suspend fun searchAddress(query: String): Result<List<NominatimPlace>> = withContext(Dispatchers.IO) {
        runCatching {
            val trimmed = query.trim()
            require(trimmed.isNotEmpty()) { "Enter a destination address" }

            val encoded = URLEncoder.encode(trimmed, "UTF-8")
            val url = "https://nominatim.openstreetmap.org/search" +
                "?format=jsonv2&addressdetails=1&limit=5&q=$encoded"
            val body = httpGet(url)
            val arr = JSONArray(body)

            val results = ArrayList<NominatimPlace>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val lat = obj.getString("lat").toDouble()
                val lon = obj.getString("lon").toDouble()
                val displayName = obj.optString("display_name", trimmed)
                val shortName = buildShortPlaceName(obj, displayName)
                results.add(
                    NominatimPlace(
                        displayName = displayName,
                        shortName = shortName,
                        coord = GeoCoord(lat, lon)
                    )
                )
            }
            results
        }
    }

    suspend fun fetchRoute(
        start: GeoCoord,
        end: GeoCoord,
        destinationName: String
    ): Result<OsmRoute> = withContext(Dispatchers.IO) {
        runCatching {
            val url = String.format(
                Locale.US,
                "https://router.project-osrm.org/route/v1/driving/%.6f,%.6f;%.6f,%.6f" +
                    "?overview=full&geometries=polyline&steps=true",
                start.lon,
                start.lat,
                end.lon,
                end.lat
            )
            val body = httpGet(url)
            val root = JSONObject(body)
            val code = root.optString("code", "Error")
            if (!code.equals("Ok", ignoreCase = true)) {
                throw IllegalStateException(root.optString("message", "OSRM routing error: $code"))
            }

            val routes = root.getJSONArray("routes")
            if (routes.length() == 0) {
                throw IllegalStateException("No driving route found")
            }

            val routeObj = routes.getJSONObject(0)
            val totalDistance = routeObj.optDouble("distance", 0.0)
            val totalDuration = routeObj.optDouble("duration", 0.0)
            val overviewGeom = decodePolyline(routeObj.optString("geometry", ""))

            val legs = routeObj.getJSONArray("legs")
            val steps = ArrayList<RouteStep>()
            if (legs.length() > 0) {
                val stepsArr = legs.getJSONObject(0).getJSONArray("steps")
                for (i in 0 until stepsArr.length()) {
                    val stepObj = stepsArr.getJSONObject(i)
                    val maneuverObj = stepObj.getJSONObject("maneuver")
                    val mType = maneuverObj.optString("type", "")
                    val mMod = maneuverObj.optString("modifier", "")
                    val exitNum = maneuverObj.optInt("exit", 0)

                    val locArr = maneuverObj.getJSONArray("location")
                    val loc = GeoCoord(locArr.getDouble(1), locArr.getDouble(0))
                    val maneuver = mapOsmManeuver(mType, mMod)

                    val streetName = formatStreetName(
                        stepObj = stepObj,
                        maneuverType = mType,
                        exitNum = exitNum,
                        mappedManeuver = maneuver,
                        destinationName = destinationName
                    )

                    steps.add(
                        RouteStep(
                            index = i,
                            maneuver = maneuver,
                            maneuverTypeRaw = mType,
                            maneuverModifierRaw = mMod,
                            streetName = streetName,
                            distanceMeters = stepObj.optDouble("distance", 0.0),
                            durationSeconds = stepObj.optDouble("duration", 0.0),
                            maneuverLocation = loc,
                            geometry = decodePolyline(stepObj.optString("geometry", ""))
                        )
                    )
                }
            }

            OsmRoute(
                destinationName = destinationName.ifBlank { "Destination" },
                origin = start,
                destination = end,
                totalDistanceMeters = totalDistance,
                totalDurationSeconds = totalDuration,
                geometry = overviewGeom,
                steps = steps
            )
        }
    }

    private fun buildShortPlaceName(obj: JSONObject, displayName: String): String {
        val name = obj.optString("name", "").trim()
        val parts = displayName.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        return when {
            name.isNotEmpty() && parts.size >= 2 && !parts[1].equals(name, ignoreCase = true) ->
                "$name, ${parts[1]}"
            parts.size >= 2 -> "${parts[0]}, ${parts[1]}"
            parts.isNotEmpty() -> parts[0]
            else -> displayName
        }
    }

    private fun formatStreetName(
        stepObj: JSONObject,
        maneuverType: String,
        exitNum: Int,
        mappedManeuver: ManeuverType,
        destinationName: String
    ): String {
        if (maneuverType == "arrive") {
            return destinationName.ifBlank { "Destination" }
        }

        val name = stepObj.optString("name", "").trim()
        val ref = stepObj.optString("ref", "").trim()
        val rotaryName = stepObj.optString("rotary_name", "").trim()

        val road = when {
            name.isNotEmpty() && ref.isNotEmpty() && !name.contains(ref, ignoreCase = true) ->
                "$name ($ref)"
            name.isNotEmpty() -> name
            ref.isNotEmpty() -> ref
            rotaryName.isNotEmpty() -> rotaryName
            else -> ""
        }

        val isRoundabout = maneuverType == "roundabout" ||
            maneuverType == "rotary" ||
            maneuverType == "roundabout turn"
        if (isRoundabout && exitNum > 0) {
            return if (road.isNotEmpty()) "Exit $exitNum • $road" else "Roundabout Exit $exitNum"
        }

        return road.ifBlank { mappedManeuver.label }
    }

    fun mapOsmManeuver(type: String, modifier: String): ManeuverType {
        val t = type.lowercase(Locale.US).trim()
        val m = modifier.lowercase(Locale.US).trim()

        return when (t) {
            "arrive" -> ManeuverType.DESTINATION
            "roundabout", "rotary", "roundabout turn" -> ManeuverType.ROUNDABOUT
            "merge" -> ManeuverType.MERGE
            "depart" -> ManeuverType.STRAIGHT
            else -> {
                if ("uturn" in m) {
                    if ("right" in m) ManeuverType.UTURN_RIGHT else ManeuverType.UTURN_LEFT
                } else {
                    when (m) {
                        "sharp right" -> ManeuverType.SHARP_RIGHT
                        "right" -> ManeuverType.TURN_RIGHT
                        "slight right" -> ManeuverType.SLIGHT_RIGHT
                        "sharp left" -> ManeuverType.SHARP_LEFT
                        "left" -> ManeuverType.TURN_LEFT
                        "slight left" -> ManeuverType.SLIGHT_LEFT
                        "straight", "" -> ManeuverType.STRAIGHT
                        else -> ManeuverType.STRAIGHT
                    }
                }
            }
        }
    }

    fun decodePolyline(encoded: String): List<GeoCoord> {
        if (encoded.isEmpty()) return emptyList()
        val poly = ArrayList<GeoCoord>()
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
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20 && index < len)
            val dlat = if ((result and 1) != 0) (result shr 1).inv() else (result shr 1)
            lat += dlat

            if (index >= len) break

            shift = 0
            result = 0
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20 && index < len)
            val dlng = if ((result and 1) != 0) (result shr 1).inv() else (result shr 1)
            lng += dlng

            poly.add(GeoCoord(lat / 1E5, lng / 1E5))
        }
        return poly
    }

    private fun httpGet(urlStr: String): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/json")
        }
        return try {
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.use { input ->
                BufferedReader(InputStreamReader(input, Charsets.UTF_8)).readText()
            }.orEmpty()
            if (status !in 200..299) {
                throw IllegalStateException("HTTP $status from ${URL(urlStr).host}")
            }
            text
        } finally {
            conn.disconnect()
        }
    }
}
