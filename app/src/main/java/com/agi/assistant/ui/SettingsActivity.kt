package com.agi.assistant.ui

import android.app.Activity
import android.os.Bundle
import android.widget.*
import com.agi.assistant.AssistantApp
import com.agi.assistant.R
import com.agi.assistant.core.ai.*
import com.agi.assistant.core.update.UpdateError
import com.agi.assistant.core.update.InstallError
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

    private lateinit var simpleProvider: Spinner
    private lateinit var apiName: EditText
    private lateinit var advancedToggle: TextView
    private lateinit var advancedPanel: View
    private lateinit var btnTest: Button
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
    private val updateDialog by lazy { UpdateDialog(this, app.updatePolicy, app.updateManager) }
    /** Set when the user tapped "Check for updates" so the result can open the dialog. */
    private var manualCheck = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        simpleProvider = findViewById(R.id.simpleProvider)
        apiName = findViewById(R.id.apiName)
        advancedToggle = findViewById(R.id.advancedToggle)
        advancedPanel = findViewById(R.id.advancedPanel)
        btnTest = findViewById(R.id.btnTest)
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
        // Simple choices + "Custom (Advanced)" for anything that is not a one-key setup.
        simpleProvider.adapter = spinnerAdapter(SimpleProvider.values().map { it.label } + "Custom / OpenAI-compatible (Advanced)")

        val s = app.settings
        providerType.setSelection(s.providerType.ordinal)
        baseUrl.setText(s.baseUrl)
        model.setText(s.model)
        apiKey.setText(s.apiKey)
        apiName.setText(s.apiName)
        val stored = s.providerConfig()
        val simple = SimpleProvider.forConfig(stored)
        simpleProvider.setSelection(simple?.ordinal ?: SimpleProvider.values().size)
        // Existing custom/OpenAI-compatible users land directly in Advanced so nothing looks lost.
        if (simple == null) showAdvanced(true)
        simpleProvider.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                val p = SimpleProvider.values().getOrNull(position)
                if (p == null) { showAdvanced(true); presetNote.text = "Fill in the Advanced settings below."; return }
                presetNote.text = p.keyHint
                apiKey.isEnabled = p != SimpleProvider.OFFLINE
                findViewById<TextView>(R.id.apiKeyLabel).text = if (p == SimpleProvider.OFFLINE) "API Key (not needed)" else "API Key"
                // Mirror the internal defaults into the Advanced fields so both views agree; a valid
                // custom Gemini model already stored is kept (SimpleSetup.toConfig does the same).
                val c = SimpleSetup.toConfig(SimpleSetupInput(p, apiName.text.toString(), apiKey.text.toString()), app.settings.providerConfig())
                providerType.setSelection(c.type.ordinal); baseUrl.setText(c.baseUrl); model.setText(c.model)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        advancedToggle.setOnClickListener { showAdvanced(advancedPanel.visibility != View.VISIBLE) }
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
        btnTest.setOnClickListener { testAndSave() }

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

    override fun onResume() {
        super.onResume()
        updateDialog.installer.onActivityResumed()
    }

    override fun onStop() {
        app.updateManager.removeObserver(updateObserver)
        super.onStop()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        if (updateDialog.installer.onActivityResult(requestCode, resultCode, data)) return
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
    }

    override fun onDestroy() {
        updateDialog.dismiss()
        super.onDestroy()
    }

    private fun renderUpdate(state: UpdateState) {
        val installed = app.installedVersion().versionName
        btnCheckUpdate.isEnabled = state !is UpdateState.Checking && state !is UpdateState.Downloading
        btnCheckUpdate.text = if (state is UpdateState.Checking) UpdateMessages.CHECKING else getString(R.string.update_check)
        btnViewRelease.visibility = View.GONE
        updateNotes.visibility = View.GONE
        updateStatus.text = UpdateMessages.statusLine(state, installed)
        if (state is UpdateState.UpdateAvailable) {
            val i = state.info
            val notes = UpdateMessages.whatsNew(i.releaseNotes, 6)
            if (notes.isNotBlank()) { updateNotes.text = "What's new:\n$notes"; updateNotes.visibility = View.VISIBLE }
            btnViewRelease.visibility = View.VISIBLE
            btnViewRelease.text = "Update"
            // One tap: download → verify → Android installer (the dialog shows progress and is the fallback UI).
            btnViewRelease.setOnClickListener { updateDialog.show(i, installed); app.updateManager.startDownload(i) }
        }
        when (state) {
            is UpdateState.Downloading -> { btnViewRelease.visibility = View.VISIBLE; btnViewRelease.text = "View progress"; btnViewRelease.setOnClickListener { updateDialog.show(state.info, installed) } }
            is UpdateState.ReadyToInstall -> {
                btnViewRelease.visibility = View.VISIBLE; btnViewRelease.text = "Install update"
                // Fallback when the installer did not open automatically: launch it directly from Settings.
                btnViewRelease.setOnClickListener { updateDialog.installer.install(state) }
            }
            is UpdateState.DownloadFailed -> { btnViewRelease.visibility = View.VISIBLE; btnViewRelease.text = "Retry"; btnViewRelease.setOnClickListener { updateDialog.show(state.info, installed) } }
            is UpdateState.InstallerLaunched -> { btnViewRelease.visibility = View.VISIBLE; btnViewRelease.text = "Install again"; btnViewRelease.setOnClickListener { updateDialog.show(state.info, installed) } }
            is UpdateState.InstallationError -> {
                btnViewRelease.visibility = View.VISIBLE
                btnViewRelease.text = if (state.reason == InstallError.PERMISSION_REQUIRED) "Allow & install" else "Details"
                btnViewRelease.setOnClickListener { updateDialog.show(state.info, installed) }
            }
            else -> {}
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

    private fun showAdvanced(show: Boolean) {
        advancedPanel.visibility = if (show) View.VISIBLE else View.GONE
        advancedToggle.text = if (show) "Advanced settings  ▾" else "Advanced settings  ▸"
    }

    /** True while the user is on a one-key simple choice (Gemini/Offline) with Advanced collapsed. */
    private fun simpleMode(): SimpleProvider? =
        SimpleProvider.values().getOrNull(simpleProvider.selectedItemPosition)?.takeIf { advancedPanel.visibility != View.VISIBLE }

    private fun spinnerAdapter(items: List<String>) =
        ArrayAdapter(this, android.R.layout.simple_spinner_item, items).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

    private fun currentConfig() = ProviderConfig(
        ProviderType.values()[providerType.selectedItemPosition],
        baseUrl.text.toString().trim(), model.text.toString().trim(), apiKey.text.toString().trim(),
    )

    /**
     * Save button: behaviour switches always; provider settings are written as shown (simple or
     * advanced). Test & Save is the recommended path because it verifies the key first.
     */
    private fun save() {
        val c = simpleMode()?.let { SimpleSetup.toConfig(SimpleSetupInput(it, apiName.text.toString(), apiKey.text.toString()), app.settings.providerConfig()) } ?: currentConfig()
        app.settings.apply {
            providerType = c.type; baseUrl = c.baseUrl; model = c.model; apiKey = c.apiKey
            apiName = this@SettingsActivity.apiName.text.toString()
            fallbackToLocal = swFallback.isChecked; speakReplies = swSpeak.isChecked
        }
        Speaker.enabled = swSpeak.isChecked
        Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
    }

    /** Test & Save: real request first, persist only when it succeeds (SimpleSetup / ConnectionTester). */
    private fun testAndSave() {
        val simple = simpleMode()
        val label = apiName.text.toString()
        btnTest.isEnabled = false
        testResult.text = "Testing connection…"
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                if (simple != null) {
                    SimpleSetup.testAndSave(SimpleSetupInput(simple, label, apiKey.text.toString()), app.settings)
                } else {
                    // Advanced: same semantics for a custom config – validate, real request, then save.
                    val c = currentConfig()
                    if (c.type == ProviderType.LOCAL) {
                        app.settings.apply { providerType = c.type; baseUrl = c.baseUrl; model = c.model; apiKey = c.apiKey; apiName = label }
                        SimpleSetup.Outcome(true, "✓ Offline planner selected. No API key needed.")
                    } else {
                        c.validationError()?.let { SimpleSetup.Outcome(false, "✗ $it", ProviderErrorKind.CONFIG) } ?: run {
                            val r = ConnectionTester.test(c)
                            if (r.ok) {
                                app.settings.apply { providerType = c.type; baseUrl = c.baseUrl; model = c.model; apiKey = c.apiKey; apiName = label }
                                SimpleSetup.Outcome(true, "✓ ${label.trim().ifBlank { "API" }} is connected and saved. " + r.message.removePrefix("✓").trim())
                            } else SimpleSetup.Outcome(false, "✗ " + SimpleSetup.friendlyError(r.kind ?: ProviderErrorKind.UNKNOWN, c, r.message.removePrefix("✗").trim()), r.kind)
                        }
                    }
                }
            }
            withContext(MainDispatcher) {
                btnTest.isEnabled = true
                testResult.text = outcome.message
                if (outcome.saved) {
                    app.settings.fallbackToLocal = swFallback.isChecked
                    Toast.makeText(this@SettingsActivity, "Saved", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
