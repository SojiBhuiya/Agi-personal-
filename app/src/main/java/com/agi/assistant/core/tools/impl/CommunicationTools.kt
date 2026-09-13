package com.agi.assistant.core.tools.impl

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.telephony.SmsManager
import com.agi.assistant.core.tools.*
import java.util.Locale

internal data class ContactHit(val name: String, val number: String)

/** Shared contact resolution: name -> phone number(s), best match first. */
internal fun lookupContacts(ctx: ToolContext, query: String, limit: Int = 5): List<ContactHit> {
    val cr = ctx.context.contentResolver
    val q = query.trim().lowercase(Locale.ROOT)
    val out = LinkedHashMap<String, ContactHit>()
    val proj = arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER)
    cr.query(
        ContactsContract.CommonDataKinds.Phone.CONTENT_URI, proj,
        "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?", arrayOf("%$query%"),
        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC",
    )?.use { c ->
        while (c.moveToNext()) {
            val name = c.getString(0) ?: continue
            val num = c.getString(1) ?: continue
            out.putIfAbsent(name + "|" + num.filter { it.isDigit() || it == '+' }, ContactHit(name, num))
        }
    }
    return out.values.sortedBy { h ->
        val n = h.name.lowercase(Locale.ROOT)
        when { n == q -> 0; n.startsWith(q) -> 1; n.split(' ').any { it == q } -> 2; else -> 3 }
    }.take(limit)
}

private fun looksLikeNumber(s: String) = s.count { it.isDigit() } >= 5 && s.all { it.isDigit() || it in "+ -()" }

class CallContactTool : Tool {
    override val category = "Communication"
    override val spec = ToolSpec(
        "call_contact",
        "Place a phone call to a contact name or phone number.",
        listOf(ToolParam("contact", ParamType.STRING, "Contact name (e.g. 'Rahim') or a phone number")),
    )

    override suspend fun execute(args: Map<String, Any?>, ctx: ToolContext): ToolResult {
        val who = args.str("contact").trim()
        if (who.isEmpty()) return ToolResult.fail("Who should I call?")
        val number: String
        val label: String
        if (looksLikeNumber(who)) { number = who; label = who } else {
            if (ctx.context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED)
                return ToolResult.permission(PermissionNeed(PermissionNeed.Kind.RUNTIME, "Contacts access to find $who", listOf(Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE)))
            val hits = lookupContacts(ctx, who)
            if (hits.isEmpty()) return ToolResult.fail("I couldn't find a contact named \"$who\".")
            number = hits.first().number; label = hits.first().name
        }
        val canCall = ctx.context.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        val intent = Intent(if (canCall) Intent.ACTION_CALL else Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.context.startActivity(intent)
        return ToolResult.ok(if (canCall) "Calling $label ($number)." else "Opened the dialer with $label ($number). Grant the Phone permission to call directly.", "Calling $label", leftApp = true)
    }
}

class SendSmsTool : Tool {
    override val category = "Communication"
    override val spec = ToolSpec(
        "send_sms",
        "Send an SMS text message. If 'message' is empty the Messages app opens with the recipient so the user can type.",
        listOf(
            ToolParam("contact", ParamType.STRING, "Contact name or phone number"),
            ToolParam("message", ParamType.STRING, "Message text", required = false),
            ToolParam("send_directly", ParamType.BOOLEAN, "true to send silently in the background (needs SMS permission); false opens the SMS app pre-filled", required = false),
        ),
    )

    override suspend fun execute(args: Map<String, Any?>, ctx: ToolContext): ToolResult {
        val who = args.str("contact").trim()
        val msg = args.str("message").trim()
        if (who.isEmpty()) return ToolResult.fail("Who should I message?")
        val number: String; val label: String
        if (looksLikeNumber(who)) { number = who; label = who } else {
            if (ctx.context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED)
                return ToolResult.permission(PermissionNeed(PermissionNeed.Kind.RUNTIME, "Contacts access to find $who", listOf(Manifest.permission.READ_CONTACTS)))
            val hits = lookupContacts(ctx, who)
            if (hits.isEmpty()) return ToolResult.fail("No contact named \"$who\" found.")
            number = hits.first().number; label = hits.first().name
        }
        val direct = args.bool("send_directly", false)
        if (direct && msg.isNotEmpty()) {
            if (ctx.context.checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED)
                return ToolResult.permission(PermissionNeed(PermissionNeed.Kind.RUNTIME, "SMS permission to send the message", listOf(Manifest.permission.SEND_SMS)))
            @Suppress("DEPRECATION")
            val sm = if (android.os.Build.VERSION.SDK_INT >= 31) ctx.context.getSystemService(SmsManager::class.java) else SmsManager.getDefault()
            sm.sendMultipartTextMessage(number, null, sm.divideMessage(msg), null, null)
            return ToolResult.ok("Sent SMS to $label: \"$msg\"", "Message sent to $label")
        }
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(number))).putExtra("sms_body", msg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.context.startActivity(intent)
        return ToolResult.ok("Opened Messages for $label${if (msg.isNotEmpty()) " with the text pre-filled. Tap send to confirm." else "."}", "Ready to message $label", leftApp = true)
    }
}

class SendWhatsAppTool : Tool {
    override val category = "Communication"
    override val spec = ToolSpec(
        "send_whatsapp",
        "Open a WhatsApp chat with a contact, optionally pre-filled with a message (the user taps send).",
        listOf(
            ToolParam("contact", ParamType.STRING, "Contact name or phone number with country code"),
            ToolParam("message", ParamType.STRING, "Message text", required = false),
        ),
    )

    override suspend fun execute(args: Map<String, Any?>, ctx: ToolContext): ToolResult {
        val who = args.str("contact").trim(); val msg = args.str("message").trim()
        val pm = ctx.context.packageManager
        val pkg = listOf("com.whatsapp", "com.whatsapp.w4b").firstOrNull { pm.getLaunchIntentForPackage(it) != null }
            ?: return ToolResult.fail("WhatsApp is not installed.")
        var number: String? = null; var label = who
        if (looksLikeNumber(who)) number = who else if (who.isNotEmpty()) {
            if (ctx.context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED)
                return ToolResult.permission(PermissionNeed(PermissionNeed.Kind.RUNTIME, "Contacts access to find $who", listOf(Manifest.permission.READ_CONTACTS)))
            lookupContacts(ctx, who).firstOrNull()?.let { number = it.number; label = it.name }
        }
        val digits = number?.filter { it.isDigit() }
        val intent = if (digits != null) {
            Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$digits" + if (msg.isNotEmpty()) "?text=" + Uri.encode(msg) else "")).setPackage(pkg)
        } else {
            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, msg).setPackage(pkg)
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.context.startActivity(intent)
        return ToolResult.ok("Opened WhatsApp chat with $label${if (msg.isNotEmpty()) " with your message ready to send" else ""}.", "Opening WhatsApp", leftApp = true)
    }
}

class FindContactTool : Tool {
    override val category = "Communication"
    override val spec = ToolSpec(
        "find_contact",
        "Look up a contact's phone number(s) by name.",
        listOf(ToolParam("name", ParamType.STRING, "Partial or full contact name")),
        intent = ToolIntent.INFORMATION,
    )

    override suspend fun execute(args: Map<String, Any?>, ctx: ToolContext): ToolResult {
        val name = args.str("name").trim()
        if (ctx.context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED)
            return ToolResult.permission(PermissionNeed(PermissionNeed.Kind.RUNTIME, "Contacts access", listOf(Manifest.permission.READ_CONTACTS)))
        val hits = lookupContacts(ctx, name, 8)
        if (hits.isEmpty()) return ToolResult.fail("No contact matching \"$name\".")
        return ToolResult.ok(hits.joinToString("\n") { "${it.name}: ${it.number}" })
    }
}
