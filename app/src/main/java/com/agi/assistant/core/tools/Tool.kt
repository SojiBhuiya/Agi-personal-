package com.agi.assistant.core.tools

import android.content.Context

/** Runtime information available to every tool invocation. */
open class ToolContext(
    context: Context?,
    /** Called by long-running tools to report progress to the UI. */
    val progress: (String) -> Unit = {},
) {
    private val _context = context

    /** Android context; only absent in pure-JVM tests that use fake tools. */
    val context: Context get() = _context ?: error("ToolContext has no Android Context (JVM test)")
}

/**
 * A capability the assistant can perform on the phone. Tools are pure
 * Kotlin classes registered in [ToolRegistry]; the LLM (or the offline
 * planner) picks a tool by [spec].name and supplies the arguments.
 */
interface Tool {
    val spec: ToolSpec

    /** Category used to group tools in the UI and in the system prompt. */
    val category: String get() = "General"

    /** Performs the action. Runs on a background dispatcher. */
    suspend fun execute(args: Map<String, Any?>, ctx: ToolContext): ToolResult
}

// -- Argument helpers ---------------------------------------------------------

fun Map<String, Any?>.str(key: String, default: String = ""): String =
    this[key]?.toString()?.takeIf { it != "null" } ?: default

fun Map<String, Any?>.int(key: String, default: Int = 0): Int =
    when (val v = this[key]) {
        is Number -> v.toInt()
        is String -> v.trim().toDoubleOrNull()?.toInt() ?: default
        else -> default
    }

fun Map<String, Any?>.bool(key: String, default: Boolean = false): Boolean =
    when (val v = this[key]) {
        is Boolean -> v
        is String -> v.equals("true", true) || v == "1" || v.equals("yes", true)
        is Number -> v.toInt() != 0
        else -> default
    }
