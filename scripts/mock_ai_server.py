#!/usr/bin/env python3
"""Tiny mock of the OpenAI chat-completions and Gemini generateContent APIs for tests.
Turn 1 (no tool results in history) -> replies with an open_app tool call.
Turn 2 (tool result present)        -> replies with final text.
Every request (headers/body/path) is written to LOG for assertions."""
import json, sys
from http.server import BaseHTTPRequestHandler, HTTPServer

LOG = sys.argv[2] if len(sys.argv) > 2 else "/tmp/mock_ai_last.json"

class H(BaseHTTPRequestHandler):
    def log_message(self, *a): pass
    def do_POST(self):
        n = int(self.headers.get("Content-Length", 0))
        body = json.loads(self.rfile.read(n) or b"{}")
        json.dump({"path": self.path, "headers": dict(self.headers), "body": body}, open(LOG, "w"))
        if self.path.startswith("/bad"):
            return self.send(401, {"error": {"message": "Invalid API key", "type": "auth"}})
        if ":generateContent" in self.path:
            had_tool = any(p.get("functionResponse") for c in body.get("contents", []) for p in c.get("parts", []))
            part = {"text": "Done, YouTube is open."} if had_tool else {"functionCall": {"name": "open_app", "args": {"app": "YouTube"}}}
            return self.send(200, {"candidates": [{"content": {"role": "model", "parts": [part]}}]})
        had_tool = any(m.get("role") == "tool" for m in body.get("messages", []))
        msg = {"role": "assistant", "content": "Done, YouTube is open."} if had_tool else {
            "role": "assistant", "content": None,
            "tool_calls": [{"id": "call_abc", "type": "function", "function": {"name": "open_app", "arguments": json.dumps({"app": "YouTube"})}}]}
        self.send(200, {"model": body.get("model"), "choices": [{"message": msg, "finish_reason": "tool_calls" if not had_tool else "stop"}]})
    def send(self, code, obj):
        data = json.dumps(obj).encode()
        self.send_response(code); self.send_header("Content-Type", "application/json"); self.send_header("Content-Length", str(len(data))); self.end_headers(); self.wfile.write(data)

port = int(sys.argv[1]) if len(sys.argv) > 1 else 8089
HTTPServer(("127.0.0.1", port), H).serve_forever()
