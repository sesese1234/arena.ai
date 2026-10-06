#!/usr/bin/env python3
"""Hermetic Stremio test addon for emulator tests.

Serves a manifest, a direct-HTTP stream for any IMDb id, and English/Spanish subtitle tracks,
plus the media itself (with HTTP Range support so the player can seek). Used by CI so the
"list streams / play / pick subtitles" flows don't depend on torrents or third-party hosts.

    python3 scripts/test-addon/server.py --port 7000 --public-host 10.0.2.2

The emulator reaches the host at 10.0.2.2, so install http://10.0.2.2:7000/manifest.json.
"""
import argparse
import json
import os
import re
import shutil
import subprocess
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))
MEDIA = os.path.join(HERE, "media")

SRT_EN = """1
00:00:01,000 --> 00:00:05,000
[EN] Agent test subtitle one

2
00:00:05,500 --> 00:00:10,000
[EN] The quick brown fox jumps over the lazy dog

3
00:00:10,500 --> 00:00:30,000
[EN] English subtitles are on
"""

SRT_ES = SRT_EN.replace("[EN]", "[ES]").replace("English subtitles are on", "Subtitulos en espanol activados")


def ensure_media():
    os.makedirs(MEDIA, exist_ok=True)
    video = os.path.join(MEDIA, "test.mp4")
    if not os.path.exists(video):
        if not shutil.which("ffmpeg"):
            raise SystemExit("ffmpeg is required to generate the test video")
        subprocess.run([
            "ffmpeg", "-y", "-loglevel", "error",
            # 20 minutes so a short test playback counts as in progress (Continue Watching), not finished.
            "-f", "lavfi", "-i", "testsrc2=size=640x360:rate=24:duration=1200",
            "-f", "lavfi", "-i", "sine=frequency=440:duration=1200",
            "-c:v", "libx264", "-preset", "ultrafast", "-crf", "32", "-pix_fmt", "yuv420p", "-g", "48",
            "-c:a", "aac", "-b:a", "96k", "-movflags", "+faststart", "-metadata:s:a:0", "language=eng",
            video,
        ], check=True)
    for name, body in (("en.srt", SRT_EN), ("es.srt", SRT_ES)):
        path = os.path.join(MEDIA, name)
        if not os.path.exists(path):
            with open(path, "w") as fh:
                fh.write(body)


def make_handler(base_url):
    manifest = {
        "id": "org.nuvio.agent.testaddon",
        "version": "1.0.0",
        "name": "Agent Test Streams",
        "description": "Local test streams and subtitles for emulator tests",
        "resources": ["stream", "subtitles"],
        "types": ["movie", "series"],
        "idPrefixes": ["tt"],
        "catalogs": [],
    }

    class Handler(SimpleHTTPRequestHandler):
        def __init__(self, *args, **kwargs):
            super().__init__(*args, directory=MEDIA, **kwargs)

        def log_message(self, fmt, *args):
            print("[test-addon] " + fmt % args, flush=True)

        def send_json(self, payload):
            body = json.dumps(payload).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def do_GET(self):  # noqa: N802
            path = self.path.split("?")[0]
            if path == "/manifest.json":
                return self.send_json(manifest)
            match = re.match(r"^/stream/(movie|series)/([^/]+)\.json$", path)
            if match:
                return self.send_json({"streams": [
                    {"name": "AgentTest\n720p", "title": "Agent test stream (local, 720p)", "url": f"{base_url}/media/test.mp4"},
                ]})
            match = re.match(r"^/subtitles/(movie|series)/([^/]+?)(/.*)?\.json$", path)
            if match:
                return self.send_json({"subtitles": [
                    {"id": "agent-test-en", "lang": "eng", "url": f"{base_url}/media/en.srt"},
                    {"id": "agent-test-es", "lang": "spa", "url": f"{base_url}/media/es.srt"},
                ]})
            if path.startswith("/media/"):
                return self.serve_media(path[len("/media/"):])
            self.send_error(404)

        def serve_media(self, name):
            file_path = os.path.join(MEDIA, os.path.basename(name))
            if not os.path.isfile(file_path):
                return self.send_error(404)
            size = os.path.getsize(file_path)
            ctype = "video/mp4" if file_path.endswith(".mp4") else "application/x-subrip"
            start, end = 0, size - 1
            range_header = self.headers.get("Range")
            if range_header:
                m = re.match(r"bytes=(\d*)-(\d*)", range_header)
                if m:
                    if m.group(1):
                        start = int(m.group(1))
                        if m.group(2):
                            end = min(int(m.group(2)), size - 1)
                    elif m.group(2):
                        start = max(0, size - int(m.group(2)))
                self.send_response(206)
                self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
            else:
                self.send_response(200)
            self.send_header("Content-Type", ctype)
            self.send_header("Accept-Ranges", "bytes")
            self.send_header("Content-Length", str(end - start + 1))
            self.end_headers()
            with open(file_path, "rb") as fh:
                fh.seek(start)
                remaining = end - start + 1
                while remaining > 0:
                    chunk = fh.read(min(65536, remaining))
                    if not chunk:
                        break
                    try:
                        self.wfile.write(chunk)
                    except (BrokenPipeError, ConnectionResetError):
                        return
                    remaining -= len(chunk)

    return Handler


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=7000)
    parser.add_argument("--public-host", default="10.0.2.2", help="host the emulator uses to reach this server")
    args = parser.parse_args()
    ensure_media()
    base_url = f"http://{args.public_host}:{args.port}"
    server = ThreadingHTTPServer(("0.0.0.0", args.port), make_handler(base_url))
    print(f"[test-addon] serving {base_url}/manifest.json", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
