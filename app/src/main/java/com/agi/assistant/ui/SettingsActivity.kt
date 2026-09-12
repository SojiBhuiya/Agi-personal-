package com.agi.assistant.ui

import android.app.Activity
import android.os.Bundle
import android.widget.*
import com.agi.assistant.AssistantApp
import com.agi.assistant.R
import com.agi.assistant.core.ai.*
import com.agi.assistant.core.tools.ToolSpec
import com.agi.assistant.core.update.UpdateError
import com.agi.assistant.core.update.UpdateManager
import com.agi.assistant.core.update.UpdateMessages
import com.agi.assistant.core.update.UpdateState
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
    private val updateDialog by lazy { UpdateDialog(this, app.updatePolicy) }
    /** Set when the user tapped "Check for updates" so the result can open the dialog. */
    private var manualCheck = false

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
        btnCheckUpdate.setOnClickListener {
            manualCheck = true
            app.updatePolicy.resetSession()
            app.updateManager.checkNow()
        }
    }

    override fun onStart() {
        super.onStart()
        app.updateManager.addObserver(updateObserver)
    }

    override fun onStop() {
        app.updateManager.removeObserver(updateObserver)
        super.onStop()
    }

    override fun onDestroy() {
        updateDialog.dismiss()
        super.onDestroy()
    }

    private fun renderUpdate(state: UpdateState) {
        val installed = app.installedVersion().versionName
        btnCheckUpdate.isEnabled = state !is UpdateState.Checking
        btnCheckUpdate.text = if (state is UpdateState.Checking) UpdateMessages.CHECKING else getString(R.string.update_check)
        btnViewRelease.visibility = View.GONE
        updateNotes.visibility = View.GONE
        updateStatus.text = UpdateMessages.statusLine(state, installed)
        if (state is UpdateState.UpdateAvailable) {
            val i = state.info
            val notes = UpdateMessages.whatsNew(i.releaseNotes, 6)
            if (notes.isNotBlank()) { updateNotes.text = "What’s New:\n$notes"; updateNotes.visibility = View.VISIBLE }
            btnViewRelease.visibility = View.VISIBLE
            btnViewRelease.text = "Update"
            btnViewRelease.setOnClickListener { updateDialog.show(i, installed) }
        }
        if (state is UpdateState.Error && state.reason != UpdateError.HTTP) {
            // Keep the technical detail reachable for bug reports without cluttering the main line.
            updateNotes.text = "Details: ${state.message}"; updateNotes.visibility = View.VISIBLE
        }
        if (manualCheck && state.isTerminal) {
            manualCheck = false
            when (state) {
                is UpdateState.UpdateAvailable -> updateDialog.show(state.info, installed)
                is UpdateState.UpToDate -> Toast.makeText(this, UpdateMessages.UP_TO_DATE, Toast.LENGTH_SHORT).show()
                is UpdateState.Error -> Toast.makeText(this, UpdateMessages.statusLine(state, installed), Toast.LENGTH_LONG).show()
                else -> {}
            }
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
