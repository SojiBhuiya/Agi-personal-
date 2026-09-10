package com.agi.assistant.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.*
import com.agi.assistant.AssistantApp
import com.agi.assistant.R
import com.agi.assistant.core.agent.AgentEvent
import com.agi.assistant.core.ai.ProviderType
import com.agi.assistant.core.ai.Role
import com.agi.assistant.core.tools.PermissionNeed
import com.agi.assistant.util.mainScope
import com.agi.assistant.voice.Speaker
import com.agi.assistant.voice.VoiceInput
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.agi.assistant.util.MainDispatcher

/**
 * Assistant home screen: chat transcript, text composer, push-to-talk mic,
 * quick command chips and live permission prompts.
 */
class MainActivity : Activity(), VoiceInput.Listener {

    private val app get() = application as AssistantApp
    private val scope = mainScope()
    private var job: Job? = null

    private lateinit var chatList: ListView
    private lateinit var adapter: ChatAdapter
    private lateinit var input: EditText
    private lateinit var btnSend: ImageButton
    private lateinit var btnMic: ImageButton
    private lateinit var liveTranscript: TextView
    private lateinit var statusText: TextView
    private lateinit var banner: View
    private lateinit var bannerText: TextView

    private var voice: VoiceInput? = null
    private var pendingNeed: PermissionNeed? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        chatList = findViewById(R.id.chatList)
        input = findViewById(R.id.input)
        btnSend = findViewById(R.id.btnSend)
        btnMic = findViewById(R.id.btnMic)
        liveTranscript = findViewById(R.id.liveTranscript)
        statusText = findViewById(R.id.statusText)
        banner = findViewById(R.id.banner)
        bannerText = findViewById(R.id.bannerText)

        adapter = ChatAdapter(this)
        val header = LayoutInflater.from(this).inflate(R.layout.view_welcome, chatList, false)
        header.findViewById<TextView>(R.id.welcomeExamples).text = EXAMPLES.joinToString("\n") { "•  “$it”" }
        chatList.addHeaderView(header, null, false)
        chatList.adapter = adapter

        // Restore conversation context.
        app.conversation.all.forEach { m ->
            when (m.role) {
                Role.USER -> adapter.add(ChatItem.user(m.content))
                Role.ASSISTANT -> if (m.content.isNotBlank()) adapter.add(ChatItem.assistant(m.content))
                Role.TOOL -> adapter.add(ChatItem.tool(m.toolName ?: "tool", m.content, !m.content.startsWith("Permission required") && !m.content.contains("failed", true) && !m.content.startsWith("No ") && !m.content.startsWith("Couldn't")))
                else -> {}
            }
        }
        scrollToBottom()

        btnSend.setOnClickListener { submit(input.text.toString()) }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) { submit(input.text.toString()); true } else false
        }
        input.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val has = !s.isNullOrBlank()
                btnSend.visibility = if (has) View.VISIBLE else View.GONE
                btnMic.visibility = if (has) View.GONE else View.VISIBLE
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        btnMic.setOnClickListener { toggleVoice() }
        findViewById<View>(R.id.btnSettings).setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        findViewById<View>(R.id.btnPermissions).setOnClickListener { startActivity(Intent(this, PermissionsActivity::class.java)) }
        findViewById<View>(R.id.btnClear).setOnClickListener {
            app.conversation.clear(); adapter.clear(); Speaker.stop()
            Toast.makeText(this, "Conversation cleared", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.bannerAction).setOnClickListener { resolvePending() }

        buildChips()
        Speaker.init(this)

        // Launched as the device assistant (ASSIST intent) -> start listening immediately.
        if (intent?.action == Intent.ACTION_ASSIST || intent?.action == Intent.ACTION_VOICE_COMMAND) {
            chatList.post { toggleVoice() }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent?.action == Intent.ACTION_ASSIST || intent?.action == Intent.ACTION_VOICE_COMMAND) toggleVoice()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    override fun onDestroy() {
        voice?.stop()
        scope.cancel()
        super.onDestroy()
    }

    // ---- Status / banner -------------------------------------------------------

    private fun refreshStatus() {
        val s = app.settings
        statusText.text = when (s.providerType) {
            ProviderType.LOCAL -> "Offline planner • no API key"
            ProviderType.OPENAI_COMPATIBLE -> "API • ${s.model.ifBlank { "model not set" }}"
            ProviderType.GEMINI -> "Gemini • ${s.model.ifBlank { "model not set" }}"
        }
        if (pendingNeed == null) {
            if (!app.permissions.isAccessibilityEnabled()) {
                showBanner("Enable screen control (Accessibility) to use back, scroll, tap, type and screenshots.") {
                    startActivity(app.permissions.settingsIntent(com.agi.assistant.core.permissions.Capability.Special.ACCESSIBILITY))
                }
            } else banner.visibility = View.GONE
        }
    }

    private var bannerClick: (() -> Unit)? = null
    private fun showBanner(text: String, onClick: () -> Unit) {
        bannerText.text = text
        bannerClick = onClick
        banner.visibility = View.VISIBLE
    }

    private fun resolvePending() {
        val need = pendingNeed
        if (need == null) { bannerClick?.invoke(); return }
        pendingNeed = null
        when (need.kind) {
            PermissionNeed.Kind.RUNTIME -> requestPermissions(need.runtimePermissions.toTypedArray(), REQ_TOOL_PERMISSION)
            PermissionNeed.Kind.ACCESSIBILITY -> startActivity(app.permissions.settingsIntent(com.agi.assistant.core.permissions.Capability.Special.ACCESSIBILITY))
            PermissionNeed.Kind.NOTIFICATION_LISTENER -> startActivity(app.permissions.settingsIntent(com.agi.assistant.core.permissions.Capability.Special.NOTIFICATION_LISTENER))
            PermissionNeed.Kind.ALL_FILES -> startActivity(app.permissions.settingsIntent(com.agi.assistant.core.permissions.Capability.Special.ALL_FILES))
            PermissionNeed.Kind.WRITE_SETTINGS -> startActivity(app.permissions.settingsIntent(com.agi.assistant.core.permissions.Capability.Special.WRITE_SETTINGS))
            PermissionNeed.Kind.DND_ACCESS -> startActivity(app.permissions.settingsIntent(com.agi.assistant.core.permissions.Capability.Special.DND_ACCESS))
        }
        banner.visibility = View.GONE
    }

    // ---- Chips ---------------------------------------------------------------------

    private fun buildChips() {
        val row = findViewById<LinearLayout>(R.id.chipRow)
        CHIPS.forEach { text ->
            val chip = TextView(this).apply {
                this.text = text
                setTextColor(getColor(R.color.text))
                textSize = 13f
                setBackgroundResource(R.drawable.bg_chip)
                setPadding(dp(14), dp(8), dp(14), dp(8))
                setOnClickListener { submit(text) }
            }
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.marginEnd = dp(8)
            row.addView(chip, lp)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ---- Sending ---------------------------------------------------------------------

    private fun submit(raw: String) {
        val text = raw.trim()
        if (text.isEmpty()) return
        if (job?.isActive == true) { Toast.makeText(this, "Still working on the previous request…", Toast.LENGTH_SHORT).show(); return }
        input.setText("")
        Speaker.stop()
        adapter.add(ChatItem.user(text))
        val thinking = ChatItem.status("Thinking…")
        adapter.add(thinking)
        scrollToBottom()

        job = scope.launch {
            app.agent.handle(text) { event ->
                withContext(MainDispatcher) { render(event, thinking) }
            }
        }
    }

    private fun render(event: AgentEvent, thinking: ChatItem) {
        when (event) {
            is AgentEvent.Thinking -> { thinking.text = event.detail; adapter.notifyDataSetChanged() }
            is AgentEvent.ToolStarted -> {
                thinking.text = "Running ${event.name.replace('_', ' ')}…"
                adapter.notifyDataSetChanged()
            }
            is AgentEvent.ToolFinished -> {
                adapter.insertBefore(thinking, ChatItem.tool(event.name, event.result.output, event.result.success))
            }
            is AgentEvent.Reply -> {
                adapter.insertBefore(thinking, ChatItem.assistant(event.text))
                if (event.text.isNotBlank()) Speaker.speak(this, event.text)
            }
            is AgentEvent.NeedsPermission -> {
                pendingNeed = event.need
                showBanner(event.need.description) { resolvePending() }
                findViewById<Button>(R.id.bannerAction).text = "Grant"
            }
            is AgentEvent.Error -> adapter.insertBefore(thinking, ChatItem.error(event.message))
            AgentEvent.Done -> adapter.remove(thinking)
        }
        scrollToBottom()
    }

    private fun scrollToBottom() {
        chatList.post { chatList.setSelection(adapter.count) }
    }

    // ---- Voice -------------------------------------------------------------------------

    private fun toggleVoice() {
        val v = voice ?: VoiceInput(this, this).also { voice = it }
        if (v.isListening) { v.stop(); onEnd(); return }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            return
        }
        Speaker.stop()
        liveTranscript.text = getString(R.string.listening)
        liveTranscript.visibility = View.VISIBLE
        btnMic.setBackgroundResource(R.drawable.bg_mic_active)
        v.start()
    }

    override fun onReady() {}
    override fun onPartial(text: String) { liveTranscript.text = text }
    override fun onLevel(rms: Float) { btnMic.scaleX = 1f + (rms.coerceIn(0f, 10f) / 40f); btnMic.scaleY = btnMic.scaleX }
    override fun onResult(text: String) { submit(text) }
    override fun onError(message: String) { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
    override fun onEnd() {
        liveTranscript.visibility = View.GONE
        btnMic.setBackgroundResource(R.drawable.bg_mic)
        btnMic.scaleX = 1f; btnMic.scaleY = 1f
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val granted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        when (requestCode) {
            REQ_MIC -> if (granted) toggleVoice() else Toast.makeText(this, "Microphone permission is needed for voice input.", Toast.LENGTH_LONG).show()
            REQ_TOOL_PERMISSION -> Toast.makeText(this, if (granted) "Granted — ask me again." else "Permission denied.", Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val REQ_MIC = 11
        private const val REQ_TOOL_PERMISSION = 12
        val EXAMPLES = listOf("Open YouTube", "Call Rahim", "Send a message to Rahim saying I'm on my way", "Open Chrome and search for Bangladesh weather, then tell me what you find", "Turn the volume up", "Find my downloaded PDF", "Read my notifications", "Go back", "Scroll down", "Take a screenshot")
        val CHIPS = listOf("Open YouTube", "Read my notifications", "Volume up", "Take a screenshot", "Find my downloaded PDF", "Open WhatsApp", "Go to Settings", "What time is it?")
    }
}
