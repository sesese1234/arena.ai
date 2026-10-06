#!/usr/bin/env python3
"""End-to-end agent tasks on a real emulator, driven only through the UI.

1. Configures a provider through the assistant settings screen: DeepSeek (deepseek-flash) when
   DEEPSEEK_API_KEY is set, otherwise the scripted mock OpenAI-compatible server on the host.
2. Sends each task through the chat input, waits for the agent to finish, records the chat
   (tool calls + reply), then closes the panel and checks the app state it should have changed.

Evidence goes to $FLOW_OUT (agent_*.png / .xml) and the markdown summary.
"""
import argparse
import os
import re
import time

import ui
import baseline_flows as base

AGENT_PROVIDER = os.environ.get("AGENT_PROVIDER") or ("deepseek" if os.environ.get("DEEPSEEK_API_KEY") else "mock")


def rid(name):
    return f"^{name}$"


def find_id(tag, timeout=10):
    deadline = time.time() + timeout
    while time.time() < deadline:
        for node in ui.dump():
            if node.rid == tag or node.rid.endswith(":id/" + tag):
                return node
        time.sleep(0.7)
    return None


def tap_id(tag, timeout=10, scroll=True):
    node = find_id(tag, timeout)
    for _ in range(5 if scroll else 0):
        if node is not None:
            break
        ui.swipe_up()  # long forms (provider editor) put later fields below the fold
        node = find_id(tag, 3)
    if node is None:
        raise AssertionError(f"no element with test tag '{tag}'; visible {ui.visible_labels()[:25]}")
    ui.tap(node)
    return node


def clear_focused(chars=80):
    ui.key(123)  # MOVE_END
    for _ in range(chars // 20):
        ui.sh("shell", "input", "keyevent", *(["67"] * 20))


def open_chat():
    if find_id("agent_input", timeout=2):
        return
    ui.hide_keyboard()  # the soft keyboard would cover the floating button
    tap_id("agent_fab", timeout=15, scroll=False)
    find_id("agent_input", timeout=10) or tap_id("agent_fab", scroll=False)


def close_chat():
    node = find_id("agent_close", timeout=3)
    if node:
        ui.tap(node)
        time.sleep(1)


def configure_provider():
    open_chat()
    tap_id("agent_settings")
    time.sleep(1)
    ui.screenshot("agent_00_settings")
    if AGENT_PROVIDER == "deepseek":
        tap_id("agent_add_provider")
        tap_id("agent_catalog_search")
        ui.type_text("DeepSeek")
        time.sleep(1)
        tap_id("agent_catalog_deepseek")
        time.sleep(1)
        tap_id("agent_secret_apiKey")
        ui.sh("shell", "input", "text", os.environ["DEEPSEEK_API_KEY"])
        ui.hide_keyboard()
        ui.screenshot("agent_01_provider_editor")
        tap_id("agent_provider_save")
        time.sleep(1)
        tap_id("agent_model_deepseek_deepseek-flash", timeout=10)
    else:
        ui.tap_text(r"^Custom endpoint$", timeout=10)
        time.sleep(1)
        tap_id("agent_provider_base_url")
        clear_focused(60)
        ui.type_text("http://10.0.2.2:8090/v1")
        tap_id("agent_secret_apiKey")
        ui.type_text("mock-key")
        tap_id("agent_new_model")
        ui.type_text("mock-agent")
        ui.tap(ui.wait_for(r"^Add model$", timeout=5))
        ui.wait_for(r"mock-agent", timeout=5)
        ui.hide_keyboard()
        ui.screenshot("agent_01_provider_editor")
        tap_id("agent_provider_save")
        time.sleep(1)
    nodes = ui.screenshot("agent_02_provider_saved")
    labels = " ".join(ui.visible_labels(nodes))
    if "credentials set" not in labels:
        raise AssertionError(f"provider not configured: {labels[:400]}")
    ui.tap(ui.wait_for(r"^Back$", timeout=5))
    time.sleep(1)
    return f"{AGENT_PROVIDER} configured"


def chat_transcript():
    nodes = ui.dump()
    tools = [n.desc.replace("Tool ", "") for n in nodes if n.desc.startswith("Tool ")]
    replies = [n.desc.replace("Assistant: ", "") for n in nodes if n.desc.startswith("Assistant: ")]
    errors = []
    for box in (n for n in nodes if n.rid.endswith("agent_error")):
        x1, y1, x2, y2 = box.bounds
        inner = [n.label for n in nodes if n is not box and n.label and x1 <= n.bounds[0] and n.bounds[2] <= x2 and y1 <= n.bounds[1] and n.bounds[3] <= y2]
        errors.append(" ".join(inner) or box.label or "(error with no text)")
    return tools, replies, errors


def confirmation_buttons():
    """(confirm, cancel) nodes of the assistant's confirmation dialog, or None. The dialog is its
    own window, so its test tags may not be exported; fall back to the Cancel button and the
    button beside it."""
    nodes = ui.dump()
    ok = next((n for n in nodes if n.rid.endswith("agent_confirm_ok")), None)
    cancel = next((n for n in nodes if n.rid.endswith("agent_confirm_cancel")), None)
    if ok and cancel:
        return ok, cancel
    if not any("assistant wants to" in n.label.lower() for n in nodes):
        return None
    cancel = next((n for n in nodes if n.label == "Cancel"), None)
    if cancel is None:
        return None
    row = [n for n in nodes if n.label and n is not cancel and abs(n.center[1] - cancel.center[1]) < 40 and n.center[0] > cancel.center[0]]
    return (row[0], cancel) if row else None


# The CI emulator's qemu process has crashed 17-22 minutes in during agent tasks (runs #21-#24, with
# 4 GB and 2.5 GB of guest RAM alike). Screen recording goes through the emulator's host GPU path,
# so only the first few tasks are recorded; the rest keep screenshots and uiautomator dumps.
MAX_RECORDINGS = int(os.environ.get("AGENT_MAX_RECORDINGS", "3"))
_recorded = 0


def ask(prompt, name, timeout=240, approve=None):
    global _recorded
    record = _recorded < MAX_RECORDINGS
    if record:
        _recorded += 1
        ui.start_recording(f"agent_{name}", limit_s=min(timeout, 180))
    try:
        return _ask(prompt, name, timeout, approve)
    finally:
        if record:
            ui.stop_recording(f"agent_{name}")


def _ask(prompt, name, timeout, approve):
    open_chat()
    tap_id("agent_input", scroll=False)
    ui.type_text(prompt)
    tap_id("agent_send", scroll=False)
    deadline = time.time() + timeout
    confirmed = False
    seen_tools = []
    while time.time() < deadline:
        time.sleep(2)
        if approve is not None:
            dialog = confirmation_buttons()
            if dialog:
                ui.screenshot(f"agent_{name}_confirmation")
                ui.tap(dialog[0] if approve else dialog[1])
                confirmed = True
                continue
        tools, _, _ = chat_transcript()
        seen_tools = sorted(set(seen_tools) | set(tools))
        if find_id("agent_send", timeout=0.5) and not find_id("agent_stop", timeout=0.5):
            break
    else:
        raise AssertionError(f"agent did not finish within {timeout}s")
    ui.screenshot(f"agent_{name}_chat")
    tools, replies, errors = chat_transcript()
    reply = replies[-1] if replies else ""
    print(f"  tools={sorted(set(seen_tools) | set(tools))} reply={reply[:200]!r} errors={errors}", flush=True)
    if errors:
        raise AssertionError(f"agent error: {errors[-1]}")
    return sorted(set(seen_tools) | set(tools)), reply, confirmed


def screen_labels(name):
    close_chat()
    time.sleep(2)
    return " ".join(ui.visible_labels(ui.screenshot(f"agent_{name}_app"), 80))


def task_install_addon():
    tools, reply, _ = ask("Install the OpenSubtitles addon", "install_addon")
    assert "install_addon" in tools, tools
    base.open_addons()
    for _ in range(4):
        if ui.find(r"OpenSubtitles", timeout=5):
            break
        ui.swipe_up()
    else:
        raise AssertionError(f"OpenSubtitles not listed after the agent installed it; visible {ui.visible_labels()}")
    ui.screenshot("agent_install_addon_verified")
    ui.back()
    base.go_home()
    return reply


def task_find_matrix():
    tools, reply, _ = ask("Find The Matrix and open its details", "find_matrix")
    assert "open_title" in tools, tools
    labels = screen_labels("find_matrix")
    assert "Matrix" in labels, labels[:300]
    return reply


def task_add_to_library():
    tools, reply, _ = ask("Add this to my library", "add_library")
    assert "add_to_library" in tools, tools
    base.go_home()
    ui.tap_text(r"^Library$", timeout=10)
    time.sleep(3)
    ui.wait_for(r"Matrix", timeout=15)
    ui.screenshot("agent_add_library_verified")
    base.go_home()
    return reply


def task_play_with_subtitles():
    # Re-open The Matrix so "the first stream" refers to it.
    ask("Open The Matrix (1999) details", "reopen_matrix", timeout=180)
    tools, reply, _ = ask("Play the first stream and turn on English subtitles", "play_subs", timeout=300)
    assert "play_stream" in tools and "player_select_subtitles" in tools, tools
    close_chat()
    time.sleep(3)
    ui.screenshot("agent_play_subs_player")
    assert ui.player_started(), "no player activity in logcat"
    return reply


def task_web_then_open():
    tools, reply, _ = ask("Search the web for who directed The Matrix, then open their other movie", "web_open", timeout=300)
    assert "web_search" in tools and "open_title" in tools, tools
    labels = screen_labels("web_open")
    return f"{reply[:150]} | screen: {labels[:120]}"


def task_continue_watching():
    tools, reply, _ = ask("What's on my continue-watching list?", "continue_watching")
    assert "get_continue_watching" in tools, tools
    assert "Matrix" in reply, reply
    return reply


def task_destructive_confirmation():
    tools, reply, confirmed = ask("Remove the OpenSubtitles addon", "remove_addon", approve=True)
    assert confirmed, "expected a confirmation dialog before removing the addon"
    assert "remove_addon" in tools, tools
    return reply


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--addon-host", default="10.0.2.2:7000")
    parser.add_argument("--title", default="Agent tasks")
    args = parser.parse_args()
    flow = ui.Flow(f"{args.title} — provider: {AGENT_PROVIDER}", before=base.ensure_signed_in)
    if not flow.step("Configure provider in assistant settings", configure_provider):
        flow.report()
        return
    tasks = {
        "find": ("Agent: find The Matrix and open its details", task_find_matrix),
        "library": ("Agent: add this to my library", task_add_to_library),
        "play": ("Agent: play first stream + English subtitles", task_play_with_subtitles),
        "install": ("Agent: install the OpenSubtitles addon", task_install_addon),
        "continue": ("Agent: what's on my continue-watching list", task_continue_watching),
        "remove": ("Agent: destructive action asks for confirmation", task_destructive_confirmation),
        "web": ("Agent: web search director, open their other movie", task_web_then_open),
    }
    # The CI emulator dies 14-22 minutes after boot, so AGENT_TASKS can put the tasks that still need
    # evidence first. Removing OpenSubtitles needs the install task before it.
    order = os.environ.get("AGENT_TASKS", "find,library,play,install,continue,remove,web").split(",")
    for key in order:
        flow.step(*tasks[key.strip()])
    try:
        with open(f"{ui.OUT}/agent-logcat.txt", "w") as fh:
            fh.write(ui.sh("logcat", "-d", timeout=120))
    except Exception as error:  # noqa: BLE001 - the report matters more than the log
        print(f"  [logcat] not collected: {error}", flush=True)
    return flow.report()


if __name__ == "__main__":
    main()
