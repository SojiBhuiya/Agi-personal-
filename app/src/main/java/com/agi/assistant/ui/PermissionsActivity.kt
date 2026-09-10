package com.agi.assistant.ui

import android.app.Activity
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.agi.assistant.AssistantApp
import com.agi.assistant.R
import com.agi.assistant.core.permissions.Capability

/** Lists every capability with its explanation and a Grant/Granted button. */
class PermissionsActivity : Activity() {
    private val app get() = application as AssistantApp
    private lateinit var container: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_permissions)
        container = findViewById(R.id.container)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        while (container.childCount > 2) container.removeViewAt(2)
        val pm = app.permissions
        pm.capabilities.forEach { cap ->
            if (cap.special == null && cap.runtimePermissions.isEmpty()) return@forEach
            val v = LayoutInflater.from(this).inflate(R.layout.item_permission, container, false)
            v.findViewById<TextView>(R.id.title).text = cap.title
            v.findViewById<TextView>(R.id.why).text = cap.why
            val btn = v.findViewById<Button>(R.id.action)
            val granted = pm.isGranted(cap)
            btn.text = if (granted) "Granted" else "Grant"
            btn.isEnabled = !granted
            btn.alpha = if (granted) 0.5f else 1f
            btn.setOnClickListener {
                when (cap.special) {
                    null -> requestPermissions(cap.runtimePermissions.toTypedArray(), 100)
                    else -> startActivity(pm.settingsIntent(cap.special))
                }
            }
            container.addView(v)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        render()
    }
}
