# Architecture

## Goals
* Natural-language phone control (voice + text) on Android 8.0+.
* AI provider independence: swap/add LLM APIs without touching the rest.
* Small, dependency-light, buildable offline; clear seams for growth (Compose,
  Hilt, Room, on-device models) without a rewrite.

## Layers

```
 UI (MainActivity / Settings / Permissions)
      │  AgentEvent stream
      ▼
 AssistantAgent  ── ConversationStore (context window, persisted JSON)
      │  AiRequest(systemPrompt, messages, toolSpecs)
      ▼
 AiProvider (interface)  ◄── AiProviderFactory ◄── SecureSettings (ProviderConfig)
   ├─ LocalRuleProvider      (offline deterministic planner)
   ├─ OpenAiCompatibleProvider
   └─ GeminiProvider
      │  AiResponse(text | toolCalls)
      ▼
 ToolRegistry → Tool.execute(args, ToolContext) → ToolResult
   ├─ Intents (open app/url/search/settings/dial/sms/alarm)
   ├─ System services (AudioManager, CameraManager, Settings.System, BatteryManager)
   ├─ MediaStore + AssistantFileProvider (find/open files)
   ├─ NotificationListenerService (read notifications)
   └─ AccessibilityService (back/home/recents, scroll, tap, type, read screen, screenshot)
```

### Agent loop
`AssistantAgent.handle(text)`:
1. Append the user turn to `ConversationStore`.
2. Build `AiRequest` = system prompt (date, rules, tool categories) + recent
   window + tool specs; call the active provider.
3. If the response has tool calls, run each through `ToolRegistry`, append a
   `TOOL` message per call, and loop (max 8 steps) so the model can chain
   actions ("open Chrome → search → read_screen → summarise").
4. If a tool returns `needsPermission`, stop and surface a permission prompt.
5. Emit `AgentEvent`s (Thinking, ToolStarted/Finished, Reply, Error, Done) to
   the UI, which renders them as chat bubbles and speaks replies via TTS.
6. If a remote provider throws and *fallback to offline* is enabled, the loop
   transparently retries with `LocalRuleProvider`.

### Provider contract
`AiProvider.complete(AiRequest): AiResponse` – a single suspend call. Messages
use a neutral `ChatMessage(role, content, toolCalls, toolCallId, toolName)`;
each provider maps them to its wire format (OpenAI `tool_calls`/`tool` role,
Gemini `functionCall`/`functionResponse`). Tool schemas are JSON-Schema objects
generated from typed `ToolSpec`s.

### Tools
A `Tool` = `ToolSpec` (name, description, typed params) + `execute`. Results
carry `output` (for the model), `spoken` (short TTS phrase), `needsPermission`
and `leftApp`. Tools never crash the loop; exceptions become failed results.

### Offline planner
`LocalRuleProvider` splits multi-step utterances on *and / then / commas* and
maps each step to a tool call with ordered regex rules (navigation → volume →
brightness → flashlight → alarm → search → call → WhatsApp → SMS → contacts →
files → settings → type → tap → URL → play → open app). It is pure Kotlin/JVM
and covered by unit tests.

### Security
* `SecureSettings` encrypts secrets with an Android-Keystore AES-GCM key.
* No key is present in source or APK; users paste keys at runtime.
* All privileged capabilities go through system UIs; `PermissionManager`
  centralises status checks and intents.

## Why plain Android Views + manual DI?
The sandbox that produced the APK has no access to Google's Maven repository,
so AndroidX/Compose could not be resolved. The code is written so that moving
to Compose/Hilt later only touches `ui/` and `AssistantApp` – the core packages
have no UI or framework-DI dependencies.

## Next phase (suggested)
* Wake word / continuous listening via a foreground service.
* On-device LLM provider (e.g. llama.cpp / MediaPipe) behind `AiProvider`.
* Visual grounding: send `screenshot` to a vision model for "tap this button".
* Confirmation dialogs for sensitive tools (direct SMS send, calls).
* Room-backed conversation history with per-thread context.
* Jetpack Compose UI, Hilt DI, instrumentation tests on an emulator/device.
