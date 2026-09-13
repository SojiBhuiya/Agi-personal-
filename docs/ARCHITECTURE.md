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

## Volume

Single implementation, no duplicates:

`user text → LocalRuleProvider.parseStep → VolumeCommand.parse (core/tools/VolumeLogic.kt) → tool call "volume" →
VolumeTool (DeviceTools.kt) → VolumeController → AndroidVolumeBackend → AudioManager.setStreamVolume → read back → ToolResult`

* **Absolute**: any bare number (`volume 50`, `Volume 60 করো`, `ভলিউম ৬০`) is a target percent, clamped 0–100.
  `index = round(pct / 100 × getStreamMaxVolume)`; the response reports `round(actualIndex × 100 / max)` read back from the device.
* **Relative**: `up`/`down`/`বাড়াও`/`কমাও` = ±1 percentage point; `up 1%`, `ভলিউম ১০ বাড়াও`, `down by 5` = ±N points
  (`action=adjust, delta=N`). On devices whose native step is coarser than the requested delta the index moves by one
  step in the requested direction so the command always has an effect; bounds are 0 and max. No hard-coded increments.
* Bengali numerals ০–৯ are normalised by `BanglaDigits.toAscii` before parsing.
* `mute`, `unmute`, `max`, `get` and the `ring/alarm/call/notification` streams are preserved.
* JVM tests: `VolumeControlTest` (parser EN+BN, `VolumeMath`, controller against a fake backend).

## Online AI brain (provider layer)

`SecureSettings` (API key AES-GCM encrypted with an Android Keystore key) → `ProviderConfig` → `AiProviderFactory` →
`OpenAiCompatibleProvider` / `GeminiProvider` / `LocalRuleProvider` → `AssistantAgent` loop.

* `OpenAiCompatibleProvider` posts to `OpenAiEndpoint.chatCompletions(baseUrl)` (= configured Base URL + `/chat/completions`,
  never auto-adds `/v1`) with the configured model, `stream: false`, the system prompt, the full conversation window and the
  tool schemas. Tool calls returned by the model are executed by the same agent loop the offline planner uses.
* Streaming is intentionally not used: the agent consumes whole turns (text and/or tool calls); there is no token-level UI path.
* `HttpJson` / `HttpTransport` is the single network seam. Every failure becomes an `AiProviderException` with a
  `ProviderErrorKind` (CONFIG, AUTH 401/403, NOT_FOUND 404, BAD_REQUEST 400, RATE_LIMIT 429, SERVER 5xx, TIMEOUT, NETWORK,
  MALFORMED, EMPTY). `AssistantAgent` falls back to `LocalRuleProvider` on any of them when "fallback to offline" is on.
* `Redactor` strips API keys (exact value, `Bearer …`, `?key=…`, key-looking tokens) from every message that reaches logs or
  the UI. Gemini's key is sent in the `x-goog-api-key` header, not the URL.
* Settings → Test connection runs `ConnectionTester`, a real request through the same factory/provider classes.
* JVM tests: `OnlineProviderTest` (fake transport, deterministic) and `ProviderWireTest` (real HTTP against `scripts/mock_ai_server.py`).

## Response path & latency

`MainActivity → AssistantAgent.handle (Dispatchers.IO) → AgentLoop.run → provider.complete → [tools] → …`

* `AgentLoop` (pure Kotlin, `RequestFlowTest`) guarantees: one provider request per step; tools run only when the
  model returns tool calls; text-only questions = exactly one request; one tool = provider → tool → provider;
  fallback to `LocalRuleProvider` only when the remote provider throws; never after a successful response.
* The system prompt is a lazily-built static prefix + the date/time appended last, so the long unchanging part of
  every request is byte-identical (provider prefix caching).
* `ConversationStore.window()` shortens tool outputs from *earlier* turns (`HistoryWindow`, 600 chars); the current
  turn is always sent complete, which keeps Gemini 3 strict same-turn validation (thought signatures) intact.
  Persistence is asynchronous (single writer thread, atomic rename) — no file I/O on the response path.
* `UrlConnectionTransport` keeps the HTTPS connection in the keep-alive pool (no `disconnect()` on success), so the
  second request of a tool turn skips TCP + TLS setup.
* Gemini 3 models are called with their default sampling (no explicit temperature), per Google's guidance.
* `TurnTrace` (numbers only, debug log) records provider/tool counts and milliseconds per turn.
* Follow-up (not in this task): token streaming would need a streaming transport, incremental `AgentEvent`s and a
  partial-message chat row; the current architecture consumes whole turns.

## Intent-aware tool routing

Problem this solves: "আজ আবহাওয়া কেমন?" used to open Chrome (`web_search`) and then `read_screen`,
because there was no weather tool, the offline planner mapped *weather* to the browser, the
`read_screen` description invited the model to "check results after opening a page", and the
raw tool output was rendered verbatim in chat. The browser is a **UI** tool, not an information source.

Pieces (all small, declarative – no central if/else):

- `core/tools/ToolSpec.kt` – every tool declares `intent` (`INFORMATION`, `ACTION`, `UI`) and
  `rawOutput` (dumps such as screen trees / notification lists that must never be shown verbatim).
- `core/tools/WeatherLogic.kt` + `tools/impl/WeatherTool.kt` – `get_weather` (INFORMATION):
  Open-Meteo (HTTPS, keyless). No `city` → device last-known location (`ACCESS_COARSE_LOCATION`,
  requested through the normal RUNTIME permission flow); unavailable → "ask for a city", never Chrome.
- `core/agent/IntentRouter.kt` – ordered `IntentRule` list (EN + BN keywords) → `Route(intent, tool, args)`.
  Used three ways: (1) `policyPrompt` (static, generated from declared intents, part of the cached prompt
  prefix), (2) `hintFor` (one line per request, appended after the prefix, before the date), (3) the
  offline planner (`LocalRuleProvider`) picks the direct tool from the same rules.
- `core/agent/AgentLoop.kt` – if the request is informational and the model still asks for a UI tool,
  the call is **not executed**; the model gets a tool error telling it to use the direct tool or ask the
  user (`TurnTrace.blockedCalls`). After a direct INFORMATION tool succeeded the guard is lifted.
  `finalAnswer()` replaces a verbatim echo of a rawOutput dump with the tool's spoken summary;
  `AgentEvent.ToolFinished.display` is what the UI shows (`spoken` for rawOutput tools).
- Latency: still one provider request per step; a routed information request is exactly
  provider → tool → provider (2 calls) instead of 3–4 with the browser detour. "Hi" remains 1 call / 0 tools.

Tests: `IntentRoutingTest` (79 checks) – covers the 12 required scenarios plus WeatherLogic fixtures.
Not verified in the sandbox: live Open-Meteo requests and real device location (no network / device).
