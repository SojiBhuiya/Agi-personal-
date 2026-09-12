package com.agi.assistant.ui

import android.app.Activity
import android.os.Bundle
import android.widget.*
import com.agi.assistant.AssistantApp
import com.agi.assistant.R
import com.agi.assistant.core.ai.*
import com.agi.assistant.core.tools.ToolSpec
import com.agi.assistant.core.update.UpdateManager
import com.agi.assistant.core.update.UpdateState
import android.content.Intent
import android.net.Uri
import android.view.View
import com.agi.assistant.util.MainDispatcher
import com.agi.assistant.util.mainScope
import com.agi.assistant.voice.Speaker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Provider / behaviour settings. API keys are stored encrypted (see SecureSettings). */
class SettingsActivity : Activity() {
    private val app get() = application as AssistantApp
    private val scope = mainScope()

    private lateinit var preset: Spinner
    private lateinit var providerType: Spinner
    private lateinit var baseUrl: EditText
    private lateinit var model: EditText
    private lateinit var apiKey: EditText
    private lateinit var presetNote: TextView
    private lateinit var testResult: TextView
    private lateinit var swFallback: Switch
    private lateinit var swSpeak: Switch
    private lateinit var updateInstalled: TextView
    private lateinit var updateStatus: TextView
    private lateinit var updateNotes: TextView
    private lateinit var btnCheckUpdate: Button
    private lateinit var btnViewRelease: Button
    private val updateObserver = UpdateManager.Observer { renderUpdate(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        preset = findViewById(R.id.preset)
        providerType = findViewById(R.id.providerType)
        baseUrl = findViewById(R.id.baseUrl)
        model = findViewById(R.id.model)
        apiKey = findViewById(R.id.apiKey)
        presetNote = findViewById(R.id.presetNote)
        testResult = findViewById(R.id.testResult)
        swFallback = findViewById(R.id.swFallback)
        swSpeak = findViewById(R.id.swSpeak)

        val presetNames = listOf("Custom…") + ProviderPresets.all.map { it.name }
        preset.adapter = spinnerAdapter(presetNames)
        providerType.adapter = spinnerAdapter(ProviderType.values().map { it.label })

        val s = app.settings
        providerType.setSelection(s.providerType.ordinal)
        baseUrl.setText(s.baseUrl)
        model.setText(s.model)
        apiKey.setText(s.apiKey)
        swFallback.isChecked = s.fallbackToLocal
        swSpeak.isChecked = s.speakReplies
        findViewById<TextView>(R.id.version).text = "AGI Assistant ${packageManager.getPackageInfo(packageName, 0).versionName} • provider-independent AI layer"

        preset.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                if (position == 0) { presetNote.text = "Enter any OpenAI-compatible or Gemini endpoint."; return }
                val p = ProviderPresets.all[position - 1]
                providerType.setSelection(p.type.ordinal)
                baseUrl.setText(p.baseUrl)
                model.setText(p.model)
                presetNote.text = p.note
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        findViewById<Button>(R.id.btnSave).setOnClickListener { save(); finish() }
        findViewById<Button>(R.id.btnTest).setOnClickListener { test() }

        updateInstalled = findViewById(R.id.updateInstalled)
        updateStatus = findViewById(R.id.updateStatus)
        updateNotes = findViewById(R.id.updateNotes)
        btnCheckUpdate = findViewById(R.id.btnCheckUpdate)
        btnViewRelease = findViewById(R.id.btnViewRelease)
        val v = app.installedVersion()
        updateInstalled.text = "Installed: ${v.versionName} (build ${v.versionCode})"
        btnCheckUpdate.setOnClickListener { app.updateManager.checkNow() }
    }

    override fun onStart() {
        super.onStart()
        app.updateManager.addObserver(updateObserver)
    }

    override fun onStop() {
        app.updateManager.removeObserver(updateObserver)
        super.onStop()
    }

    private fun renderUpdate(state: UpdateState) {
        btnCheckUpdate.isEnabled = state !is UpdateState.Checking
        btnViewRelease.visibility = View.GONE
        updateNotes.visibility = View.GONE
        when (state) {
            UpdateState.Idle -> updateStatus.text = "Updates are fetched from GitHub Releases (SojiBhuiya/Agi-personal-)."
            UpdateState.Checking -> updateStatus.text = "Checking GitHub for the latest release…"
            is UpdateState.UpToDate -> updateStatus.text = "You are up to date." +
                (state.latest?.let { " Latest release: ${it.releaseTag}." } ?: "")
            is UpdateState.UpdateAvailable -> {
                val i = state.info
                updateStatus.text = "Update available: ${i.releaseName} (${i.versionName})" +
                    (if (i.apkSizeBytes > 0) " • ${i.apkSizeBytes / 1024 / 1024} MB" else "") +
                    "\nIn-app download & install arrives in the next phase; you can open the release page for now."
                if (i.releaseNotes.isNotBlank()) { updateNotes.text = i.releaseNotes.trim(); updateNotes.visibility = View.VISIBLE }
                if (i.htmlUrl.startsWith("https://")) {
                    btnViewRelease.visibility = View.VISIBLE
                    btnViewRelease.setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(i.htmlUrl))) }
                }
            }
            is UpdateState.Error -> updateStatus.text = "Update check failed (${state.reason.name.lowercase().replace('_', ' ')}): ${state.message}"
        }
    }

    private fun spinnerAdapter(items: List<String>) =
        ArrayAdapter(this, android.R.layout.simple_spinner_item, items).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

    private fun currentConfig() = ProviderConfig(
        ProviderType.values()[providerType.selectedItemPosition],
        baseUrl.text.toString().trim(), model.text.toString().trim(), apiKey.text.toString().trim(),
    )

    private fun save() {
        val c = currentConfig()
        app.settings.apply {
            providerType = c.type; baseUrl = c.baseUrl; model = c.model; apiKey = c.apiKey
            fallbackToLocal = swFallback.isChecked; speakReplies = swSpeak.isChecked
        }
        Speaker.enabled = swSpeak.isChecked
        Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
    }

    private fun test() {
        val c = currentConfig()
        if (c.type == ProviderType.LOCAL) { testResult.text = "Offline planner needs no connection."; return }
        if (c.type != ProviderType.LOCAL && c.baseUrl.isBlank()) { testResult.text = "Base URL is required."; return }
        testResult.text = "Testing…"
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val provider = AiProviderFactory.create(c)
                    val r = provider.complete(AiRequest("Reply with the single word OK.", listOf(ChatMessage(Role.USER, "ping")), emptyList<ToolSpec>()))
                    "✓ ${provider.displayName} responded: ${(r.text ?: "(tool call)").take(80)}"
                }.getOrElse { "✗ ${it.message}" }
            }
            withContext(MainDispatcher) { testResult.text = result }
        }
    }
}
