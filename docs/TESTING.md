# Testing

## What ran in CI/sandbox (this build)

| Suite | Command | Result |
|-------|---------|--------|
| Kotlin compile (0 warnings, 0 errors) | `scripts/build_apk.sh debug` | ✅ 125 classes |
| Offline planner – 42 utterances incl. multi-step | `scripts/run_tests.sh` → `LocalRuleProviderTest` | ✅ 42/42 |
| Agent loop – chaining, permission gating, JSON round-trip | `AgentLoopTest` | ✅ 7/7 |
| Provider adapters vs mock OpenAI & Gemini servers (request shape, tool-call parsing, tool-result echo, HTTP error surfacing) | `ProviderWireTest` + `scripts/mock_ai_server.py` | ✅ 14/14 |
| Update system – SemVer, GitHub release parsing, asset selection, error/network states, manager state machine | `UpdateCheckerTest` | ✅ 82/82 |
| R8 whole-program reference check against `android.jar` | manual | ✅ no missing framework refs |
| APK structure: manifest (services, provider, queries, permissions), resources, alignment, v1/v2/v3 signatures | `aapt2 dump badging`, `zipalign -c` | ✅ |

The sandbox has no KVM, so an Android emulator could not be run; on-device
behaviour of Intents/Accessibility was verified by code review against the
platform APIs and API-level guards (`Build.VERSION.SDK_INT`) for every API
newer than minSdk 26.

## Manual device checklist (next step for you)
1. Install APK, open app – welcome card and chips render, dark theme.
2. Type **Open YouTube** → YouTube launches; tool bubble "✓ open app".
3. Tap mic → say **turn the volume up** → volume slider appears.
4. **Read my notifications** → banner asks for Notification access → grant →
   ask again → list shown and spoken.
5. **Go back / Scroll down / Take a screenshot** → prompt for Accessibility →
   enable → actions work; screenshot appears in Pictures/Screenshots.
6. **Call Rahim** → Contacts + Phone permission prompt → call placed.
7. **Open Chrome, search for Bangladesh weather, and tell me what you find** →
   Chrome opens with results, assistant reads screen text back.
8. Settings → Groq preset → paste key → Test connection → "✓ … responded".
   Then ask a free-form request, e.g. *"open the calculator and type 12*12"*.
9. Kill the app and reopen → conversation restored.
