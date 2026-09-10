package com.agi.assistant.core.tools.impl

import android.app.SearchManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import com.agi.assistant.core.tools.*
import java.util.Locale

/** Well-known app aliases -> package names (first that is installed wins). */
internal val APP_ALIASES: Map<String, List<String>> = mapOf(
    "youtube" to listOf("com.google.android.youtube"),
    "chrome" to listOf("com.android.chrome", "com.chrome.beta"),
    "browser" to listOf("com.android.chrome", "org.mozilla.firefox", "com.brave.browser", "com.opera.browser", "com.sec.android.app.sbrowser"),
    "whatsapp" to listOf("com.whatsapp", "com.whatsapp.w4b"),
    "facebook" to listOf("com.facebook.katana", "com.facebook.lite"),
    "messenger" to listOf("com.facebook.orca", "com.facebook.mlite"),
    "instagram" to listOf("com.instagram.android"),
    "telegram" to listOf("org.telegram.messenger"),
    "imo" to listOf("com.imo.android.imoim"),
    "gmail" to listOf("com.google.android.gm"),
    "maps" to listOf("com.google.android.apps.maps"),
    "google maps" to listOf("com.google.android.apps.maps"),
    "camera" to listOf("com.google.android.GoogleCamera", "com.android.camera", "com.sec.android.app.camera", "com.oplus.camera", "com.miui.camera"),
    "gallery" to listOf("com.google.android.apps.photos", "com.sec.android.gallery3d", "com.miui.gallery", "com.coloros.gallery3d"),
    "photos" to listOf("com.google.android.apps.photos"),
    "play store" to listOf("com.android.vending"),
    "playstore" to listOf("com.android.vending"),
    "calculator" to listOf("com.google.android.calculator", "com.android.calculator2", "com.sec.android.app.popupcalculator", "com.miui.calculator"),
    "clock" to listOf("com.google.android.deskclock", "com.android.deskclock", "com.sec.android.app.clockpackage"),
    "calendar" to listOf("com.google.android.calendar", "com.android.calendar"),
    "contacts" to listOf("com.google.android.contacts", "com.android.contacts", "com.samsung.android.app.contacts"),
    "phone" to listOf("com.google.android.dialer", "com.android.dialer", "com.samsung.android.dialer"),
    "dialer" to listOf("com.google.android.dialer", "com.android.dialer", "com.samsung.android.dialer"),
    "messages" to listOf("com.google.android.apps.messaging", "com.android.mms", "com.samsung.android.messaging"),
    "sms" to listOf("com.google.android.apps.messaging", "com.android.mms", "com.samsung.android.messaging"),
    "files" to listOf("com.google.android.apps.nbu.files", "com.android.documentsui", "com.sec.android.app.myfiles", "com.mi.android.globalFileexplorer"),
    "file manager" to listOf("com.google.android.apps.nbu.files", "com.android.documentsui", "com.sec.android.app.myfiles"),
    "spotify" to listOf("com.spotify.music"),
    "tiktok" to listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill"),
    "bkash" to listOf("com.bKash.customerapp"),
    "nagad" to listOf("com.konasl.nagad"),
    "settings" to listOf("com.android.settings"),
    "google" to listOf("com.google.android.googlequicksearchbox"),
    "drive" to listOf("com.google.android.apps.docs"),
    "netflix" to listOf("com.netflix.mediaclient"),
    "twitter" to listOf("com.twitter.android"),
    "x" to listOf("com.twitter.android"),
    "linkedin" to listOf("com.linkedin.android"),
    "zoom" to listOf("us.zoom.videomeetings"),
    "uber" to listOf("com.ubercab"),
    "pathao" to listOf("com.pathao.user"),
)

class OpenAppTool : Tool {
    override val category = "Apps"
    override val spec = ToolSpec(
        "open_app",
        "Open (launch) an installed app by its name, e.g. 'YouTube', 'WhatsApp', 'Chrome', 'Camera'.",
        listOf(ToolParam("app", ParamType.STRING, "App name as the user said it")),
    )

    override suspend fun execute(args: Map<String, Any?>, ctx: ToolContext): ToolResult {
        val name = args.str("app").trim().lowercase(Locale.ROOT).removeSuffix(" app").trim()
        if (name.isEmpty()) return ToolResult.fail("No app name given.")
        val pm = ctx.context.packageManager

        // 1) alias table
        APP_ALIASES[name]?.forEach { pkg ->
            pm.getLaunchIntentForPackage(pkg)?.let { return launch(ctx, it, name) }
        }
        // 2) exact/partial label match among launchable apps
        val launchables = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        val labelled = launchables.map { it to it.loadLabel(pm).toString() }
        val match = labelled.firstOrNull { it.second.equals(name, true) }
            ?: labelled.firstOrNull { it.second.lowercase(Locale.ROOT).startsWith(name) }
            ?: labelled.firstOrNull { it.second.lowercase(Locale.ROOT).contains(name) }
            ?: labelled.firstOrNull { name.contains(it.second.lowercase(Locale.ROOT)) && it.second.length > 3 }
        if (match != null) {
            val intent = pm.getLaunchIntentForPackage(match.first.activityInfo.packageName)
                ?: return ToolResult.fail("Found ${match.second} but it has no launcher activity.")
            return launch(ctx, intent, match.second)
        }
        // 3) Play Store fallback so the user can install it
        val play = Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=" + Uri.encode(name))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return if (play.resolveActivity(pm) != null) {
            ToolResult.fail("'$name' is not installed. I could not find it on this phone; opening Play Store search instead.").also {
                ctx.context.startActivity(play)
            }
        } else ToolResult.fail("App '$name' is not installed on this phone.")
    }

    private fun launch(ctx: ToolContext, intent: Intent, label: String): ToolResult {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return try {
            ctx.context.startActivity(intent)
            ToolResult.ok("Opened $label.", "Opening $label", leftApp = true)
        } catch (e: Exception) {
            ToolResult.fail("Could not open $label: ${e.message}")
        }
    }
}

class OpenUrlTool : Tool {
    override val category = "Apps"
    override val spec = ToolSpec(
        "open_url",
        "Open a website / URL in the browser (or the app that handles the link).",
        listOf(ToolParam("url", ParamType.STRING, "Full URL or domain, e.g. https://example.com or bbc.com")),
    )

    override suspend fun execute(args: Map<String, Any?>, ctx: ToolContext): ToolResult {
        var url = args.str("url").trim()
        if (url.isEmpty()) return ToolResult.fail("No URL given.")
        if (!url.startsWith("http://") && !url.startsWith("https://")) url = "https://$url"
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            ctx.context.startActivity(intent)
            ToolResult.ok("Opened $url.", "Opening the page", leftApp = true)
        } catch (e: Exception) {
            ToolResult.fail("No app can open $url: ${e.message}")
        }
    }
}

class WebSearchTool : Tool {
    override val category = "Apps"
    override val spec = ToolSpec(
        "web_search",
        "Search the web in the browser. Use read_screen afterwards to read the results if the user wants to know what was found.",
        listOf(
            ToolParam("query", ParamType.STRING, "Search query"),
            ToolParam("engine", ParamType.STRING, "google (default), bing, duckduckgo or youtube", required = false, enumValues = listOf("google", "bing", "duckduckgo", "youtube")),
        ),
    )

    override suspend fun execute(args: Map<String, Any?>, ctx: ToolContext): ToolResult {
        val q = args.str("query").trim()
        if (q.isEmpty()) return ToolResult.fail("Empty search query.")
        val url = when (args.str("engine", "google").lowercase(Locale.ROOT)) {
            "bing" -> "https://www.bing.com/search?q="
            "duckduckgo" -> "https://duckduckgo.com/?q="
            "youtube" -> "https://www.youtube.com/results?search_query="
            else -> "https://www.google.com/search?q="
        } + Uri.encode(q)
        val pm = ctx.context.packageManager
        // Prefer Chrome explicitly when the user mentions it and it is installed.
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (pm.getLaunchIntentForPackage("com.android.chrome") != null) intent.setPackage("com.android.chrome")
        return try {
            ctx.context.startActivity(intent)
            ToolResult.ok("Searched the web for \"$q\" in the browser.", "Searching for $q", leftApp = true)
        } catch (e: Exception) {
            intent.setPackage(null)
            runCatching { ctx.context.startActivity(intent) }
                .map { ToolResult.ok("Searched for \"$q\".", leftApp = true) }
                .getOrElse {
                    val ws = Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, q).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { ctx.context.startActivity(ws); ToolResult.ok("Searched for \"$q\".", leftApp = true) }
                        .getOrElse { ToolResult.fail("No browser available: ${it.message}") }
                }
        }
    }
}

class OpenSettingsTool : Tool {
    override val category = "Apps"
    private val screens = mapOf(
        "main" to Settings.ACTION_SETTINGS,
        "wifi" to Settings.ACTION_WIFI_SETTINGS,
        "wi-fi" to Settings.ACTION_WIFI_SETTINGS,
        "bluetooth" to Settings.ACTION_BLUETOOTH_SETTINGS,
        "display" to Settings.ACTION_DISPLAY_SETTINGS,
        "sound" to Settings.ACTION_SOUND_SETTINGS,
        "battery" to Settings.ACTION_BATTERY_SAVER_SETTINGS,
        "apps" to Settings.ACTION_APPLICATION_SETTINGS,
        "storage" to Settings.ACTION_INTERNAL_STORAGE_SETTINGS,
        "location" to Settings.ACTION_LOCATION_SOURCE_SETTINGS,
        "security" to Settings.ACTION_SECURITY_SETTINGS,
        "date" to Settings.ACTION_DATE_SETTINGS,
        "language" to Settings.ACTION_LOCALE_SETTINGS,
        "accessibility" to Settings.ACTION_ACCESSIBILITY_SETTINGS,
        "notification" to Settings.ACTION_APP_NOTIFICATION_SETTINGS,
        "mobile data" to Settings.ACTION_DATA_ROAMING_SETTINGS,
        "data" to Settings.ACTION_DATA_USAGE_SETTINGS,
        "airplane" to Settings.ACTION_AIRPLANE_MODE_SETTINGS,
        "hotspot" to Settings.ACTION_WIRELESS_SETTINGS,
        "network" to Settings.ACTION_WIRELESS_SETTINGS,
        "developer" to Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS,
        "about" to Settings.ACTION_DEVICE_INFO_SETTINGS,
    )

    override val spec = ToolSpec(
        "open_settings",
        "Open the Android Settings app, optionally a specific screen.",
        listOf(ToolParam("screen", ParamType.STRING, "main, wifi, bluetooth, display, sound, battery, apps, storage, location, security, date, language, accessibility, notification, mobile data, airplane, network, developer, about", required = false)),
    )

    override suspend fun execute(args: Map<String, Any?>, ctx: ToolContext): ToolResult {
        val key = args.str("screen", "main").lowercase(Locale.ROOT).trim()
        val action = screens[key] ?: screens.entries.firstOrNull { key.contains(it.key) }?.value ?: Settings.ACTION_SETTINGS
        val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            ctx.context.startActivity(intent)
            ToolResult.ok("Opened ${if (action == Settings.ACTION_SETTINGS) "Settings" else "$key settings"}.", leftApp = true)
        } catch (e: Exception) {
            ctx.context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            ToolResult.ok("Opened the main Settings screen ($key screen not available).", leftApp = true)
        }
    }
}
