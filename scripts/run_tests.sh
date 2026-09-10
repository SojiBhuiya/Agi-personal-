#!/usr/bin/env bash
# Runs the JVM unit tests for the provider-independent core with the offline toolchain.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TOOLCHAIN="${TOOLCHAIN:-$ROOT/toolchain}"
export JAVA_HOME="${JAVA_HOME:-$TOOLCHAIN/jdk}" PATH="${JAVA_HOME:-$TOOLCHAIN/jdk}/bin:$PATH"
KLIB="$TOOLCHAIN/kotlinc/lib"
OUT="$ROOT/app/build-offline/test-classes"; rm -rf "$OUT"; mkdir -p "$OUT"
"$TOOLCHAIN/kotlinc/bin/kotlinc" -jvm-target 1.8 -no-reflect \
  -cp "$TOOLCHAIN/android.jar:$KLIB/kotlinx-coroutines-core-jvm.jar" -d "$OUT" \
  "$ROOT/app/src/main/java/com/agi/assistant/core/ai" "$ROOT/app/src/main/java/com/agi/assistant/core/tools/ToolSpec.kt" \
  "$ROOT/app/src/main/java/com/agi/assistant/core/tools/Tool.kt" "$ROOT/app/src/main/java/com/agi/assistant/core/tools/ToolResult.kt" \
  "$ROOT/app/src/test/java" 2>&1 | grep -v '^warning:' || true
# org.json is a real implementation on the JVM (android.jar only has stubs).
CP="$OUT:$KLIB/kotlin-stdlib.jar:$KLIB/kotlinx-coroutines-core-jvm.jar:$TOOLCHAIN/json.jar"
echo "== LocalRuleProviderTest"; java -cp "$CP" com.agi.assistant.LocalRuleProviderTest
echo "== AgentLoopTest"; java -cp "$CP" com.agi.assistant.AgentLoopTest
echo "== ProviderWireTest (mock OpenAI/Gemini server)"
python3 "$ROOT/scripts/mock_ai_server.py" 8089 /tmp/mock_ai_last.json & MOCK=$!; trap 'kill $MOCK 2>/dev/null' EXIT; sleep 0.7
java -cp "$CP" com.agi.assistant.ProviderWireTest http://127.0.0.1:8089 /tmp/mock_ai_last.json
