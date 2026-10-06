#!/usr/bin/env python3
"""Scripted OpenAI-compatible chat server for deterministic on-device agent tests.

It speaks /v1/chat/completions with streaming (SSE) tool calls exactly like a real provider,
but decides the next step from a script matched against the latest user message, deriving
arguments from earlier tool results. Used when no real API key is available in CI; with a
key, the same flows run against a real model (DeepSeek).

    python3 scripts/mock-llm/server.py --port 8090
"""
import argparse
import json
import re
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


def last_result(messages, name):
    for msg in reversed(messages):
        if msg.get("role") == "tool" and msg.get("name") == name:
            try:
                return json.loads(msg.get("content") or "{}")
            except json.JSONDecodeError:
                return {}
    # Tool messages usually carry only tool_call_id: map it back to the call's function name.
    calls = {}
    for msg in messages:
        for call in msg.get("tool_calls") or []:
            calls[call["id"]] = call["function"]["name"]
    for msg in reversed(messages):
        if msg.get("role") == "tool" and calls.get(msg.get("tool_call_id")) == name:
            try:
                return json.loads(msg.get("content") or "{}")
            except json.JSONDecodeError:
                return {}
    return {}


def first_result_id(result, name_hint=None, type_hint=None):
    items = result.get("results") or result.get("items") or []
    def name(item):
        return (item.get("name") or item.get("title") or "").lower()
    hint = (name_hint or "").lower()
    # Exact title first, then a title containing the hint, then the first item of the right type.
    for match in (lambda i: name(i) == hint, lambda i: hint in name(i), lambda i: True):
        for item in items:
            if type_hint and item.get("type") not in (None, type_hint):
                continue
            if not hint or match(item):
                return item.get("id"), item.get("type") or type_hint
    if items:
        return items[0].get("id"), items[0].get("type")
    return None, None


def current_title(messages):
    state = last_result(messages, "get_app_state")
    screen = state.get("current_screen") or {}
    return screen.get("id"), screen.get("type") or "movie"


SCRIPTS = [
    (r"install.*opensubtitles", [
        lambda m: ("install_addon", {"url": "https://opensubtitles-v3.strem.io/manifest.json"}),
        lambda m: "Installed the OpenSubtitles v3 addon.",
    ]),
    (r"install.*cinemeta", [
        lambda m: ("install_addon", {"url": "https://v3-cinemeta.strem.io/manifest.json"}),
        lambda m: "Cinemeta is installed.",
    ]),
    (r"find the matrix and open", [
        lambda m: ("search_titles", {"query": "The Matrix", "type": "movie"}),
        lambda m: ("open_title", dict(zip(("id", "type"), first_result_id(last_result(m, "search_titles"), "The Matrix", "movie")))),
        lambda m: "Opened The Matrix (1999).",
    ]),
    (r"^\s*open the matrix", [
        lambda m: ("search_titles", {"query": "The Matrix", "type": "movie"}),
        lambda m: ("open_title", dict(zip(("id", "type"), first_result_id(last_result(m, "search_titles"), "The Matrix", "movie")))),
        lambda m: "Opened The Matrix (1999).",
    ]),
    (r"add (this|it) to my library", [
        lambda m: ("get_app_state", {}),
        lambda m: ("add_to_library", dict(zip(("id", "type"), current_title(m)))),
        lambda m: "Added it to your library.",
    ]),
    (r"play the first stream.*english subtitles", [
        lambda m: ("get_app_state", {}),
        lambda m: ("play_title", dict(zip(("id", "type"), current_title(m)))),
        lambda m: ("play_stream", {"index": 0}),
        lambda m: ("player_select_subtitles", {"language": "English"}),
        lambda m: ("get_player_state", {}),
        lambda m: "Playing the first stream with English subtitles on.",
    ]),
    (r"who directed .*then open their other movie", [
        lambda m: ("web_search", {"query": "who directed The Matrix 1999"}),
        lambda m: ("search_titles", {"query": "Speed Racer", "type": "movie"}),
        lambda m: ("open_title", dict(zip(("id", "type"), first_result_id(last_result(m, "search_titles"), "Speed Racer", "movie")))),
        lambda m: "The Matrix was directed by the Wachowskis; I opened their film Speed Racer (2008).",
    ]),
    (r"continue.watching", [
        lambda m: ("get_continue_watching", {}),
        lambda m: "Continue watching: " + ", ".join(i.get("title", "?") for i in last_result(m, "get_continue_watching").get("items", [])) + ".",
    ]),
    (r"remove the opensubtitles", [
        lambda m: ("remove_addon", {"addon": "OpenSubtitles"}),
        lambda m: "Removed the OpenSubtitles addon." if last_result(m, "remove_addon").get("ok") else "You declined, so I kept it.",
    ]),
]


def plan(messages):
    last_user = max(i for i, msg in enumerate(messages) if msg.get("role") == "user")
    text = messages[last_user].get("content") or ""
    if isinstance(text, list):
        text = " ".join(part.get("text", "") for part in text if isinstance(part, dict))
    step = sum(1 for msg in messages[last_user + 1:] if msg.get("role") == "assistant" and msg.get("tool_calls"))
    for pattern, steps in SCRIPTS:
        if re.search(pattern, text, re.IGNORECASE | re.DOTALL):
            action = steps[min(step, len(steps) - 1)](messages)
            return action
    return "(mock) I don't have a script for that request."


class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        print("[mock-llm] " + fmt % args, flush=True)

    def do_GET(self):  # noqa: N802
        if self.path.rstrip("/").endswith("/models"):
            body = json.dumps({"data": [{"id": "mock-agent", "object": "model"}]}).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(body)
            return
        self.send_error(404)

    def do_POST(self):  # noqa: N802
        if not self.path.rstrip("/").endswith("/chat/completions"):
            return self.send_error(404)
        if self.headers.get("Authorization") != "Bearer mock-key":
            body = b'{"error":{"message":"invalid api key"}}'
            self.send_response(401)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        request = json.loads(self.rfile.read(int(self.headers.get("Content-Length", "0"))))
        action = plan(request.get("messages", []))
        print(f"[mock-llm] tools={len(request.get('tools') or [])} -> {action}", flush=True)
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-cache")
        self.end_headers()
        base = {"id": "chatcmpl-" + uuid.uuid4().hex[:8], "object": "chat.completion.chunk", "model": request.get("model")}

        def send(delta, finish=None):
            chunk = dict(base, choices=[{"index": 0, "delta": delta, "finish_reason": finish}])
            self.wfile.write(f"data: {json.dumps(chunk)}\n\n".encode())
            self.wfile.flush()

        if isinstance(action, str):
            for word in re.findall(r"\S+\s*", action):
                send({"content": word})
                time.sleep(0.02)
            send({}, "stop")
        else:
            name, args = action
            call_id = "call_" + uuid.uuid4().hex[:10]
            arguments = json.dumps({k: v for k, v in args.items() if v is not None})
            send({"tool_calls": [{"index": 0, "id": call_id, "type": "function", "function": {"name": name, "arguments": ""}}]})
            for i in range(0, len(arguments), 12):
                send({"tool_calls": [{"index": 0, "function": {"arguments": arguments[i:i + 12]}}]})
            send({}, "tool_calls")
        self.wfile.write(b"data: [DONE]\n\n")
        self.wfile.flush()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=8090)
    args = parser.parse_args()
    print(f"[mock-llm] listening on :{args.port}", flush=True)
    ThreadingHTTPServer(("0.0.0.0", args.port), Handler).serve_forever()


if __name__ == "__main__":
    main()
