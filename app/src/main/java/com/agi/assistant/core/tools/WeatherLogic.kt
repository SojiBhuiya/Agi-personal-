package com.agi.assistant.core.tools

import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Pure (Android-free) weather logic backed by Open-Meteo (https://open-meteo.com, no API key,
 * HTTPS). Geocoding for named cities, current conditions + today's range for coordinates, and a
 * bilingual one-paragraph summary. JVM-tested; the Android tool only supplies location + HTTP.
 */
object WeatherLogic {
    const val GEOCODE_BASE = "https://geocoding-api.open-meteo.com/v1/search"
    const val FORECAST_BASE = "https://api.open-meteo.com/v1/forecast"

    data class Place(val name: String, val admin: String?, val country: String?, val lat: Double, val lon: Double) {
        val label: String get() = listOfNotNull(name, admin?.takeIf { it != name }, country).joinToString(", ")
    }

    data class Current(
        val tempC: Double, val feelsC: Double?, val humidity: Int?, val windKmh: Double?, val code: Int,
        val maxC: Double?, val minC: Double?, val rainChance: Int?, val isDay: Boolean,
    )

    fun geocodeUrl(city: String, language: String = "en") =
        "$GEOCODE_BASE?name=${URLEncoder.encode(city.trim(), "UTF-8")}&count=1&language=$language&format=json"

    fun forecastUrl(lat: Double, lon: Double) =
        "$FORECAST_BASE?latitude=${"%.4f".format(Locale.US, lat)}&longitude=${"%.4f".format(Locale.US, lon)}" +
            "&current=temperature_2m,relative_humidity_2m,apparent_temperature,is_day,weather_code,wind_speed_10m" +
            "&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max&forecast_days=1&timezone=auto"

    fun parseGeocode(json: String): Place? {
        val r = JSONObject(json).optJSONArray("results")?.optJSONObject(0) ?: return null
        return Place(r.optString("name"), r.optString("admin1").takeIf { it.isNotBlank() }, r.optString("country").takeIf { it.isNotBlank() }, r.getDouble("latitude"), r.getDouble("longitude"))
    }

    fun parseForecast(json: String): Current {
        val o = JSONObject(json)
        val c = o.getJSONObject("current")
        val d = o.optJSONObject("daily")
        fun JSONObject.dbl(k: String) = if (has(k) && !isNull(k)) getDouble(k) else null
        return Current(
            tempC = c.getDouble("temperature_2m"), feelsC = c.dbl("apparent_temperature"),
            humidity = c.dbl("relative_humidity_2m")?.roundToInt(), windKmh = c.dbl("wind_speed_10m"), code = c.optInt("weather_code", 0),
            maxC = d?.optJSONArray("temperature_2m_max")?.optDouble(0)?.takeIf { !it.isNaN() },
            minC = d?.optJSONArray("temperature_2m_min")?.optDouble(0)?.takeIf { !it.isNaN() },
            rainChance = d?.optJSONArray("precipitation_probability_max")?.optDouble(0)?.takeIf { !it.isNaN() }?.roundToInt(),
            isDay = c.optInt("is_day", 1) == 1,
        )
    }

    /** WMO weather code → short description. */
    fun describe(code: Int, bangla: Boolean): String = when (code) {
        0 -> if (bangla) "পরিষ্কার আকাশ" else "clear sky"
        1 -> if (bangla) "মূলত পরিষ্কার" else "mainly clear"
        2 -> if (bangla) "আংশিক মেঘলা" else "partly cloudy"
        3 -> if (bangla) "মেঘলা" else "overcast"
        45, 48 -> if (bangla) "কুয়াশা" else "fog"
        51, 53, 55, 56, 57 -> if (bangla) "গুঁড়ি গুঁড়ি বৃষ্টি" else "drizzle"
        61, 63, 65, 66, 67 -> if (bangla) "বৃষ্টি" else "rain"
        71, 73, 75, 77 -> if (bangla) "তুষারপাত" else "snow"
        80, 81, 82 -> if (bangla) "বৃষ্টির ঝাপটা" else "rain showers"
        85, 86 -> if (bangla) "তুষার ঝাপটা" else "snow showers"
        95 -> if (bangla) "বজ্রঝড়" else "thunderstorm"
        96, 99 -> if (bangla) "শিলাবৃষ্টিসহ বজ্রঝড়" else "thunderstorm with hail"
        else -> if (bangla) "মিশ্র আবহাওয়া" else "mixed conditions"
    }

    /** Natural-language summary; [placeLabel] is a city or "your current location" phrase. */
    fun summary(placeLabel: String, w: Current, bangla: Boolean): String {
        val t = w.tempC.roundToInt(); val cond = describe(w.code, bangla)
        val sb = StringBuilder()
        if (bangla) {
            sb.append("এখন $placeLabel-এ তাপমাত্রা $t°C, $cond")
            w.feelsC?.let { if ((it.roundToInt() - t).let { d -> d >= 2 || d <= -2 }) sb.append(" (অনুভূত ${it.roundToInt()}°C)") }
            sb.append("।")
            if (w.maxC != null && w.minC != null) sb.append(" আজ সর্বোচ্চ ${w.maxC.roundToInt()}°C, সর্বনিম্ন ${w.minC.roundToInt()}°C।")
            w.rainChance?.let { sb.append(if (it >= 50) " বৃষ্টির সম্ভাবনা $it%, ছাতা সঙ্গে রাখুন।" else " বৃষ্টির সম্ভাবনা $it%।") }
            w.humidity?.let { sb.append(" আর্দ্রতা $it%।") }
        } else {
            sb.append("It's $t°C in $placeLabel right now with $cond")
            w.feelsC?.let { if ((it.roundToInt() - t).let { d -> d >= 2 || d <= -2 }) sb.append(" (feels like ${it.roundToInt()}°C)") }
            sb.append(".")
            if (w.maxC != null && w.minC != null) sb.append(" Today: high ${w.maxC.roundToInt()}°C, low ${w.minC.roundToInt()}°C.")
            w.rainChance?.let { sb.append(if (it >= 50) " $it% chance of rain – take an umbrella." else " $it% chance of rain.") }
            w.humidity?.let { sb.append(" Humidity $it%.") }
        }
        return sb.toString()
    }

    /** Machine-readable line for the model (compact, English keys). */
    fun modelLine(placeLabel: String, w: Current): String =
        "Weather for $placeLabel: ${w.tempC.roundToInt()}°C, ${describe(w.code, false)}" +
            (w.feelsC?.let { ", feels like ${it.roundToInt()}°C" } ?: "") +
            (if (w.maxC != null && w.minC != null) ", today high ${w.maxC.roundToInt()}°C / low ${w.minC.roundToInt()}°C" else "") +
            (w.rainChance?.let { ", rain chance $it%" } ?: "") + (w.humidity?.let { ", humidity $it%" } ?: "") +
            (w.windKmh?.let { ", wind ${it.roundToInt()} km/h" } ?: "") + "."

    /** True when the text contains Bengali script. */
    fun isBangla(text: String) = text.any { it in '\u0980'..'\u09FF' }
}
