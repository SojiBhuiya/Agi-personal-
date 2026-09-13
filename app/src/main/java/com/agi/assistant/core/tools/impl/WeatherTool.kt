package com.agi.assistant.core.tools.impl

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import com.agi.assistant.core.tools.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/** Minimal HTTPS GET seam so WeatherTool logic is testable without network. */
fun interface HttpGet { fun get(url: String): String }

object UrlHttpGet : HttpGet {
    override fun get(url: String): String {
        require(url.startsWith("https://")) { "HTTPS only" }
        val conn = (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 8000; readTimeout = 8000; setRequestProperty("User-Agent", "AGI-Assistant-Android") }
        try {
            val code = conn.responseCode
            val text = BufferedReader(InputStreamReader(if (code in 200..299) conn.inputStream else conn.errorStream ?: conn.inputStream, Charsets.UTF_8)).use { it.readText() }
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            return text
        } catch (e: Exception) { conn.disconnect(); throw e }
    }
}

/** Provides the device's last known position; null when unavailable or not permitted. */
fun interface LocationSource { fun lastKnown(): Pair<Double, Double>? }

class AndroidLocationSource(private val context: Context) : LocationSource {
    fun hasPermission() = context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    override fun lastKnown(): Pair<Double, Double>? {
        if (!hasPermission()) return null
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        var best: Location? = null
        for (p in lm.allProviders) {
            val l = runCatching { @Suppress("MissingPermission") lm.getLastKnownLocation(p) }.getOrNull() ?: continue
            if (best == null || l.time > best!!.time) best = l
        }
        return best?.let { it.latitude to it.longitude }
    }
}

/**
 * INFORMATION tool: current weather for a named city or, when no city is given, for the device's
 * current location. Direct data source (Open-Meteo) – never the browser, never read_screen.
 */
class WeatherTool(
    private val http: HttpGet = UrlHttpGet,
    private val locationSource: ((ToolContext) -> LocationSource) = { AndroidLocationSource(it.context) },
) : Tool {
    override val category = "Information"
    override val spec = ToolSpec(
        "get_weather",
        "Get the current weather and today's forecast directly (no browser). Use for any weather, rain or temperature question. " +
            "Omit 'city' to use the phone's current location.",
        listOf(
            ToolParam("city", ParamType.STRING, "City name (e.g. Dhaka). Leave empty for the user's current location.", required = false),
            ToolParam("language", ParamType.STRING, "bn or en for the spoken summary (default: match the user)", required = false, enumValues = listOf("bn", "en")),
        ),
        intent = ToolIntent.INFORMATION,
    )

    override suspend fun execute(args: Map<String, Any?>, ctx: ToolContext): ToolResult {
        val city = args.str("city").trim()
        val bangla = args.str("language") == "bn" || (args.str("language").isBlank() && WeatherLogic.isBangla(city))
        return try {
            val (label, lat, lon) = if (city.isNotEmpty()) {
                val place = WeatherLogic.parseGeocode(http.get(WeatherLogic.geocodeUrl(city, if (bangla) "bn" else "en")))
                    ?: return ToolResult.fail(if (bangla) "\"$city\" নামের কোনো জায়গা খুঁজে পাইনি। শহরের নামটি আবার বলুন।" else "I couldn't find a place called \"$city\". Please check the city name.")
                Triple(place.label, place.lat, place.lon)
            } else {
                val loc = locationSource(ctx).lastKnown()
                    ?: return locationUnavailable(ctx, bangla)
                Triple(if (bangla) "আপনার বর্তমান এলাকা" else "your current location", loc.first, loc.second)
            }
            val w = WeatherLogic.parseForecast(http.get(WeatherLogic.forecastUrl(lat, lon)))
            val spoken = WeatherLogic.summary(label, w, bangla)
            ToolResult.ok(WeatherLogic.modelLine(label, w) + "\nSuggested reply: " + spoken, spoken)
        } catch (e: Exception) {
            ToolResult.fail(if (bangla) "এই মুহূর্তে আবহাওয়ার তথ্য আনা যাচ্ছে না (${e.javaClass.simpleName})। ইন্টারনেট সংযোগ দেখে আবার চেষ্টা করুন।" else "Weather data is unavailable right now (${e.javaClass.simpleName}). Check the internet connection and try again.")
        }
    }

    private fun locationUnavailable(ctx: ToolContext, bangla: Boolean): ToolResult {
        val src = runCatching { locationSource(ctx) as? AndroidLocationSource }.getOrNull()
        if (src != null && !src.hasPermission()) {
            return ToolResult.permission(PermissionNeed(PermissionNeed.Kind.RUNTIME, if (bangla) "আপনার এলাকার আবহাওয়া জানাতে লোকেশন অনুমতি দরকার" else "Location permission to get weather for your area", listOf(Manifest.permission.ACCESS_COARSE_LOCATION)))
        }
        return ToolResult.fail(
            if (bangla) "আপনার বর্তমান লোকেশন পাওয়া যাচ্ছে না। কোন শহরের আবহাওয়া জানতে চান বলুন (যেমন: ঢাকা)।"
            else "Your current location isn't available. Which city should I check (e.g. Dhaka)?",
        )
    }
}
