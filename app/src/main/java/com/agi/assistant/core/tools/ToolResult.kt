package com.agi.assistant.core.tools

/**
 * Outcome of a tool execution. [output] is what the model sees on the next
 * turn; [spoken] is an optional short phrase for the UI / TTS.
 */
data class ToolResult(
    val success: Boolean,
    val output: String,
    val spoken: String? = null,
    /** Set when the tool cannot run until the user grants something. */
    val needsPermission: PermissionNeed? = null,
    /** True when the action switched the user to another app/screen. */
    val leftApp: Boolean = false,
) {
    companion object {
        fun ok(output: String, spoken: String? = null, leftApp: Boolean = false) =
            ToolResult(true, output, spoken, leftApp = leftApp)

        fun fail(output: String, spoken: String? = null) = ToolResult(false, output, spoken)

        fun permission(need: PermissionNeed) =
            ToolResult(false, "Permission required: ${need.description}", need.description, need)
    }
}

/** Something the user must grant before a tool can run. */
data class PermissionNeed(
    val kind: Kind,
    val description: String,
    /** Runtime permission strings for Kind.RUNTIME. */
    val runtimePermissions: List<String> = emptyList(),
) {
    enum class Kind { RUNTIME, ACCESSIBILITY, NOTIFICATION_LISTENER, ALL_FILES, WRITE_SETTINGS, DND_ACCESS }
}
