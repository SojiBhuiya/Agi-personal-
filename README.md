# AGI Personal Assistant (Android)

A native Android AI assistant that lets you control your phone with natural
language – typed or spoken. Say *"Open YouTube"*, *"Call Rahim"*, *"Turn the
volume up"*, *"Take a screenshot"*, or a multi-step request like *"Open Chrome,
search for Bangladesh weather, and tell me what you find"*, and the assistant
performs the actions using official Android APIs (Intents, Accessibility
Service, MediaStore, SpeechRecognizer, TextToSpeech…).

* **Provider-independent AI layer** – works offline out of the box with a
  built-in rule-based planner, and can be pointed at any OpenAI-compatible API
  (Groq, OpenRouter, OpenAI, Ollama, LM Studio…) or Google Gemini from the
  Settings screen. No API keys are compiled into the APK.
* **Tool/action system** – 24 phone-control tools exposed to the model as
  function-calling schemas; the same tools are used by the offline planner.
* **Permission management** – every capability is optional and requested
  through the standard Android permission UI.

📦 **Install:** `dist/agi-assistant-0.1.0-debug.apk` (Android 8.0+ / API 26+).
Enable *"Install unknown apps"* for your file manager/browser, open the APK,
then open the app → shield icon → grant what you want the assistant to do.

## Project layout

```
app/src/main/java/com/agi/assistant
├── AssistantApp.kt                 composition root (settings, tools, agent)
├── core/
│   ├── ai/                         provider-independent AI contract
│   │   ├── AiProvider.kt           interface + AiRequest/AiResponse
│   │   ├── ChatMessage.kt          roles, tool calls, JSON persistence
│   │   ├── ProviderConfig.kt       provider types & presets
│   │   ├── AiProviderFactory.kt    the ONLY place that knows concrete providers
│   │   └── providers/
│   │       ├── LocalRuleProvider.kt        offline planner (no key needed)
│   │       ├── OpenAiCompatibleProvider.kt OpenAI / Groq / OpenRouter / Ollama…
│   │       └── GeminiProvider.kt           Google Gemini
│   ├── agent/
│   │   ├── AssistantAgent.kt       agent loop: model → tools → model → reply
│   │   └── ConversationStore.kt    persisted conversation context/window
│   ├── tools/
│   │   ├── Tool.kt / ToolSpec.kt / ToolResult.kt / ToolRegistry.kt
│   │   └── impl/                   AppTools, CommunicationTools, DeviceTools,
│   │                               FileTools, NotificationTools, AccessibilityTools
│   ├── update/                 GitHub-release update checker (see docs/UPDATES.md)
│   ├── permissions/PermissionManager.kt
│   └── settings/SecureSettings.kt  Android-Keystore-encrypted API key storage
├── services/
│   ├── AssistantAccessibilityService.kt  back/home/scroll/tap/type/read/screenshot
│   ├── NotificationListener.kt           "read my notifications"
│   └── AssistantFileProvider.kt          hands Downloads files to viewer apps
├── voice/  VoiceInput.kt (SpeechRecognizer)  Speaker.kt (TextToSpeech)
└── ui/     MainActivity (chat + mic), SettingsActivity, PermissionsActivity
```

See **docs/ARCHITECTURE.md** for the design, **docs/BUILD.md** for building
(Gradle/Android Studio *or* the offline `scripts/build_apk.sh` pipeline) and
**docs/TESTING.md** for what was tested and how.

## Quick start (development)

```bash
# Android Studio: open the folder and Run.  CLI:
./gradlew assembleDebug        # needs Gradle + Android SDK
# or, without Gradle/SDK (what produced dist/*.apk):
TOOLCHAIN=/path/to/toolchain scripts/build_apk.sh debug
scripts/run_tests.sh           # JVM tests for the planner, agent loop, provider adapters
```

## Adding an AI provider

1. Implement `AiProvider` (one `suspend fun complete(request): AiResponse`).
2. Add a `ProviderType` and a branch in `AiProviderFactory.create`.
3. Optionally add a `ProviderPreset` so it shows up in Settings.

Nothing else changes – the agent loop, tools and UI are provider-agnostic.

## Adding a tool

Implement `Tool` (a `ToolSpec` with typed params + `execute`), then add it to
`ToolRegistry.default`. It is immediately available to every provider and to
the offline planner (add a phrase rule in `LocalRuleProvider` if you want it to
work without an LLM).

## Privacy & security

* API keys are encrypted with an AES-256-GCM key held in the Android Keystore.
* Screen content is only read when you ask for a screen action and is only sent
  to the AI provider **you** configured. The offline planner sends nothing.
* Accessibility and notification access are opt-in via the system settings
  screens and can be revoked at any time. The app never bypasses Android
  security; actions that Android reserves for the user (e.g. sending SMS
  without confirmation, reading another app's private data) are not attempted.
