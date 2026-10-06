"""Small adb + uiautomator driver used by the emulator flow scripts.

Compose exposes text/content-description through accessibility, so `uiautomator dump` sees it.
Every step logs what is on screen, so a failing CI run can be diagnosed from logs alone.
"""
import os
import re
import subprocess
import time
import xml.etree.ElementTree as ET

ADB = os.environ.get("ADB", "adb")
OUT = os.environ.get("FLOW_OUT", "flow-artifacts")
PACKAGE = os.environ.get("APP_PACKAGE", "com.nuviodebug.com")
os.makedirs(OUT, exist_ok=True)


def sh(*args, check=False, timeout=120):
    result = subprocess.run([ADB, *args], capture_output=True, text=True, timeout=timeout)
    if check and result.returncode != 0:
        raise RuntimeError(f"adb {' '.join(args)} failed: {result.stderr.strip()}")
    return result.stdout


class Node:
    def __init__(self, el):
        self.text = el.get("text") or ""
        self.desc = el.get("content-desc") or ""
        self.rid = el.get("resource-id") or ""
        self.cls = el.get("class") or ""
        self.clickable = el.get("clickable") == "true"
        self.focused = el.get("focused") == "true"
        self.pkg = el.get("package") or ""
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", el.get("bounds") or "")
        self.bounds = tuple(int(x) for x in m.groups()) if m else (0, 0, 0, 0)

    @property
    def label(self):
        return self.text or self.desc

    @property
    def center(self):
        x1, y1, x2, y2 = self.bounds
        return (x1 + x2) // 2, (y1 + y2) // 2

    def __repr__(self):
        return f"<{self.cls.split('.')[-1]} '{self.label[:50]}' {self.bounds}>"


def dump(name=None):
    for _ in range(3):
        sh("shell", "uiautomator", "dump", "/sdcard/window_dump.xml", timeout=60)
        xml = sh("shell", "cat", "/sdcard/window_dump.xml")
        if xml.strip().startswith("<?xml"):
            break
        time.sleep(1)
    else:
        return []
    if name:
        with open(os.path.join(OUT, f"{name}.xml"), "w") as fh:
            fh.write(xml)
    try:
        root = ET.fromstring(xml)
    except ET.ParseError:
        return []
    return [Node(el) for el in root.iter("node")]


def dismiss_system_dialogs(nodes=None):
    """Taps 'Wait' on system ANR dialogs ("Pixel Launcher isn't responding") that slow emulators
    show over the app. Returns True if one was dismissed."""
    nodes = nodes if nodes is not None else dump()
    if not any("isn't responding" in n.label or "keeps stopping" in n.label for n in nodes):
        return False
    # Our own app: wait it out. Another app (Pixel Launcher, System UI): close it, since waiting
    # keeps the hung app around and its dialog comes straight back.
    ours = any(("Nuvio" in n.label or PACKAGE in n.label) and "isn't responding" in n.label for n in nodes)
    wait = next((n for n in nodes if n.label == "Wait"), None)
    close = next((n for n in nodes if n.label in ("Close app", "Close")), None)
    button = (wait or close) if ours else (close or wait)
    if button is None:
        return False
    print(f"  [system dialog] dismissing with '{button.label}'", flush=True)
    sh("shell", "input", "tap", *map(str, button.center))
    time.sleep(1.5)
    return True


def visible_labels(nodes=None, limit=60):
    nodes = nodes if nodes is not None else dump()
    nodes = [n for n in nodes if not n.pkg or n.pkg == PACKAGE] or nodes
    labels = []
    for n in nodes:
        if n.label and n.label not in labels:
            labels.append(n.label.replace("\n", " ")[:60])
    return labels[:limit]


def screenshot(name):
    path = os.path.join(OUT, f"{name}.png")
    with open(path, "wb") as fh:
        fh.write(subprocess.run([ADB, "exec-out", "screencap", "-p"], capture_output=True, timeout=60).stdout)
    nodes = dump(name)
    print(f"  [screen {name}] {visible_labels(nodes, 40)}", flush=True)
    return nodes


_recordings = {}


def start_recording(name, limit_s=180):
    """Starts `screenrecord` on the device (max 180 s per Android)."""
    remote = f"/sdcard/{name}.mp4"
    proc = subprocess.Popen([ADB, "shell", "screenrecord", "--time-limit", str(limit_s), "--bit-rate", "4000000", remote])
    _recordings[name] = (proc, remote)
    time.sleep(1)


def stop_recording(name):
    proc, remote = _recordings.pop(name, (None, None))
    if proc is None:
        return
    sh("shell", "pkill", "-INT", "screenrecord")
    try:
        proc.wait(timeout=15)
    except subprocess.TimeoutExpired:
        proc.kill()
    time.sleep(2)
    subprocess.run([ADB, "pull", remote, os.path.join(OUT, f"{name}.mp4")], capture_output=True, timeout=120)
    sh("shell", "rm", "-f", remote)


def find(pattern, timeout=20, exact=False, clickable_only=False):
    regex = re.compile(pattern if not exact else f"^{re.escape(pattern)}$", re.IGNORECASE)
    deadline = time.time() + timeout
    while time.time() < deadline:
        all_nodes = dump()
        if dismiss_system_dialogs(all_nodes):
            continue
        nodes = [n for n in all_nodes if not n.pkg or n.pkg == PACKAGE]
        matches = [n for n in nodes if (regex.search(n.text) or regex.search(n.desc)) and (n.clickable or not clickable_only)]
        if matches:
            return matches[0]
        time.sleep(1)
    return None


def wait_for(pattern, timeout=30):
    node = find(pattern, timeout=timeout)
    if node is None:
        raise AssertionError(f"'{pattern}' not on screen; visible: {visible_labels()}")
    return node


def tap(node_or_xy):
    x, y = node_or_xy.center if isinstance(node_or_xy, Node) else node_or_xy
    sh("shell", "input", "tap", str(x), str(y))
    time.sleep(1.2)


def tap_text(pattern, timeout=20):
    node = wait_for(pattern, timeout)
    tap(node)
    return node


def type_text(text):
    escaped = text.replace("\\", "\\\\").replace(" ", "%s").replace("'", "\\'").replace("&", "\\&").replace("?", "\\?")
    sh("shell", "input", "text", escaped)
    time.sleep(0.8)


def key(code):
    sh("shell", "input", "keyevent", str(code))
    time.sleep(0.8)


def back():
    key(4)


def keyboard_shown():
    """Whether the soft keyboard is drawn. With the AVD's hardware keyboard (CI) the IME window still
    reports as visible while drawing nothing, so this is informational only."""
    return "mInputShown=true" in sh("shell", "dumpsys", "input_method")


def hide_keyboard():
    """No-op on purpose. CI runs with a hardware keyboard, so the soft keyboard never covers the UI,
    and pressing Back to hide it would close screens (run #14 lost the profile editor that way)."""
    return False


def swipe_up():
    size = sh("shell", "wm", "size")
    m = re.search(r"(\d+)x(\d+)", size)
    w, h = (int(m.group(1)), int(m.group(2))) if m else (1080, 2400)
    sh("shell", "input", "swipe", str(w // 2), str(int(h * 0.75)), str(w // 2), str(int(h * 0.3)), "400")
    time.sleep(1)


def launch(activity="com.nuvio.app.MainActivity"):
    sh("shell", "am", "start", "-n", f"{PACKAGE}/{activity}", check=True)
    time.sleep(4)


def current_focus():
    out = sh("shell", "dumpsys", "window")
    m = re.search(r"mCurrentFocus=.*?\{[^}]*\s(\S+)\}", out)
    return m.group(1) if m else out[:200]


def app_in_foreground():
    out = sh("shell", "dumpsys", "activity", "activities")
    m = re.search(r"(?:topResumedActivity|mResumedActivity|ResumedActivity)[^\n]*?\s(\S+)/", out)
    return bool(m and m.group(1) == PACKAGE)


def ensure_app():
    """Relaunches the app if a previous step left another app (launcher, browser) on top."""
    for _ in range(3):
        if not dismiss_system_dialogs():
            break
    hide_keyboard()
    if not app_in_foreground():
        print(f"  [ensure_app] {PACKAGE} not in foreground ({current_focus()}); relaunching", flush=True)
        launch()
        time.sleep(3)


class Flow:
    """Runs named steps, records PASS/FAIL with evidence, never aborts the whole run."""

    def __init__(self, title, before=None):
        self.title = title
        self.results = []
        self.before = before

    def step(self, name, fn):
        print(f"\n=== {name}", flush=True)
        started = time.time()
        try:
            if self.before:
                self.before()
            detail = fn() or ""
            if not app_in_foreground():
                raise AssertionError(f"step ended outside the app ({current_focus()})")
            status = "PASS"
        except Exception as error:  # noqa: BLE001
            detail = f"{type(error).__name__}: {error}"
            status = "FAIL"
            try:
                screenshot(re.sub(r"\W+", "_", name.lower()) + "_failure")
            except Exception:  # noqa: BLE001
                pass
        took = time.time() - started
        print(f"--- {status} {name} ({took:.1f}s) {detail}", flush=True)
        self.results.append((name, status, str(detail)[:300], took))
        return status == "PASS"

    def report(self):
        lines = [f"## {self.title}", "", "| Step | Result | Detail |", "|---|---|---|"]
        for name, status, detail, took in self.results:
            icon = "✅" if status == "PASS" else "❌"
            lines.append(f"| {name} | {icon} {status} ({took:.0f}s) | {detail.replace('|', '/')} |")
        text = "\n".join(lines) + "\n"
        print(text, flush=True)
        with open(os.path.join(OUT, "summary.md"), "a") as fh:
            fh.write(text + "\n")
        summary = os.environ.get("GITHUB_STEP_SUMMARY")
        if summary:
            with open(summary, "a") as fh:
                fh.write(text + "\n")
        return all(status == "PASS" for _, status, _, _ in self.results)
