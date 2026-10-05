#!/usr/bin/env python3
"""Phase-1 style manual flows, automated over adb: onboarding, addon install/remove, catalogs,
search, title details, streams, playback, library, continue watching, settings.

Evidence (screenshots, uiautomator XML, logcat) lands in $FLOW_OUT. Each step is independent
so one broken flow doesn't hide the others. Usage:
    FLOW_OUT=out python3 scripts/ci/baseline_flows.py --addon-host 10.0.2.2:7000
"""
import argparse
import sys
import time

import ui

CINEMETA = "https://v3-cinemeta.strem.io/manifest.json"
OPENSUBS = "https://opensubtitles-v3.strem.io/manifest.json"


def open_settings_tab():
    tab = ui.find(r"^(Profile|Settings)$", timeout=10)
    if tab is None:
        raise AssertionError(f"no Profile/Settings tab; visible {ui.visible_labels()}")
    ui.tap(tab)


def open_addons():
    """Settings → Content & Discovery → Addons (Nuvio nests the addon manager one level down)."""
    go_home()
    open_settings_tab()
    if ui.find(r"^Addons$", timeout=3) is None:
        for _ in range(3):
            entry = ui.find(r"^Content & Discovery$", timeout=4)
            if entry:
                ui.tap(entry)
                break
            ui.swipe_up()
        else:
            raise AssertionError(f"no 'Content & Discovery' settings entry; visible {ui.visible_labels()}")
    ui.tap_text(r"^Addons$", timeout=15)
    time.sleep(2)


def go_home():
    ui.hide_keyboard()
    for _ in range(4):
        tab = ui.find(r"^Home$", timeout=3)
        if tab:
            ui.tap(tab)
            return
        ui.back()


def onboarding():
    ui.screenshot("01_launch")
    guest = None
    for _ in range(6):
        guest = ui.find(r"Continue Without Account", timeout=8)
        if guest:
            break
        ui.hide_keyboard()
        ui.swipe_up()
    if guest is None:
        raise AssertionError(f"no 'Continue Without Account' on the sign-in screen; visible {ui.visible_labels()}")
    ui.tap(guest)
    time.sleep(4)
    # Guest accounts start with no profiles: "Who's watching?" shows only "Manage Profiles", and a
    # profile has to be created (Manage Profiles → Add Profile → name → Create Profile) first.
    created = False
    for attempt in range(4):
        nodes = ui.screenshot(f"02_after_auth_{attempt}")
        labels = " ".join(ui.visible_labels(nodes))
        if "Home" in labels and ("Search" in labels or "Library" in labels):
            return "main tabs visible" + (" (created guest profile)" if created else "")
        tester = ui.find(r"^Tester$", timeout=2)
        if tester and ui.find(r"Who's watching", timeout=1):
            done = ui.find(r"^Done$", timeout=1)
            if done:
                ui.tap(done)  # leave edit mode, otherwise tapping the profile opens its editor
            ui.tap(ui.find(r"^Tester$", timeout=3) or tester)
            time.sleep(4)
            continue
        if not created and ui.find(r"Who's watching", timeout=2):
            add = ui.find(r"^Add Profile$", timeout=2)
            if add is None:
                ui.tap_text(r"^Manage Profiles$", timeout=5)
                add = ui.wait_for(r"^Add Profile$", timeout=10)
            ui.tap(add)
            time.sleep(2)
            ui.tap_text(r"Profile name", timeout=10)
            ui.type_text("Tester")
            ui.hide_keyboard()
            ui.screenshot("02_profile_editor")
            create = None
            for _ in range(4):
                create = ui.find(r"^Create Profile$", timeout=3)
                if create:
                    break
                ui.swipe_up()
            if create is None:
                raise AssertionError(f"no 'Create Profile' button; visible {ui.visible_labels()}")
            ui.tap(create)
            created = True
            time.sleep(5)
    ui.wait_for(r"^Home$", timeout=40)
    return "main tabs visible" + (" (created guest profile)" if created else "")


def install_addon(url, expect_name):
    def run():
        open_addons()
        ui.screenshot(f"addons_before_{expect_name.split()[0].lower()}")
        field = ui.find(r"(manifest|addon url|https://|Paste)", timeout=10)
        if field is None:
            add = ui.find(r"(Add Addon|Install Addon|\+)", timeout=5)
            if add:
                ui.tap(add)
            field = ui.wait_for(r"(manifest|URL|https://)", timeout=10)
        ui.tap(field)
        ui.type_text(url)
        ui.key(66)  # enter
        ui.hide_keyboard()
        install = ui.find(r"^(Install|Install Addon|Add)$", timeout=5)
        if install:
            ui.tap(install)
        done = ui.find(r"^(Done|Close)$", timeout=40)
        ui.screenshot(f"addons_modal_{expect_name.split()[0].lower()}")
        modal_text = " ".join(ui.visible_labels())
        if done:
            ui.tap(done)
        if "Install Failed" in modal_text:
            raise AssertionError(f"install failed: {modal_text[:300]}")
        ui.wait_for(expect_name, timeout=20)
        ui.screenshot(f"addons_after_{expect_name.split()[0].lower()}")
        ui.back()
        return f"{expect_name} listed"
    return run


def browse_catalogs():
    go_home()
    time.sleep(6)
    nodes = ui.screenshot("10_home_catalogs")
    labels = ui.visible_labels(nodes)
    if not any(("Popular" in l or "Featured" in l or "Top" in l) for l in labels):
        ui.swipe_up()
        labels = ui.visible_labels(ui.screenshot("10b_home_scrolled"))
    if not any(("Popular" in l or "Featured" in l or "Movie" in l) for l in labels):
        raise AssertionError(f"no catalog rows; visible {labels}")
    return "catalog rows visible"


def search_title():
    ui.tap_text(r"^Search$", timeout=10)
    field = ui.find(r"Search movies", timeout=10)
    if field:
        ui.tap(field)
    ui.type_text("The Matrix")
    ui.key(66)
    ui.wait_for(r"The Matrix", timeout=40)
    ui.screenshot("20_search_results")
    return "results for The Matrix"


def open_details():
    nodes = ui.dump()
    candidates = [n for n in nodes if "The Matrix" in n.label and n.bounds[1] > 300]
    if not candidates:
        raise AssertionError(f"no Matrix result to open; visible {ui.visible_labels(nodes)}")
    ui.tap(candidates[0])
    time.sleep(5)
    nodes = ui.screenshot("21_details")
    labels = " ".join(ui.visible_labels(nodes))
    if "Wachowski" not in labels and "1999" not in labels and "Play" not in labels:
        raise AssertionError(f"details not shown; visible {labels[:300]}")
    return "details visible"


def list_streams():
    play = ui.find(r"^(Play|Watch|Watch now|Resume)$", timeout=10) or ui.find(r"Play", timeout=5)
    if play is None:
        raise AssertionError(f"no play button; visible {ui.visible_labels()}")
    ui.tap(play)
    time.sleep(6)
    nodes = ui.screenshot("30_streams")
    labels = " ".join(ui.visible_labels(nodes))
    if "AgentTest" not in labels and "Agent test stream" not in labels:
        # Autoplay may have jumped straight into the player.
        if "com.nuvio" in ui.current_focus():
            return "autoplay opened player directly"
        raise AssertionError(f"test stream not listed; visible {labels[:300]}")
    return "streams listed"


def start_playback():
    stream = ui.find(r"(AgentTest|Agent test stream)", timeout=5)
    if stream:
        ui.tap(stream)
    time.sleep(12)
    ui.screenshot("31_player")
    log = ui.sh("logcat", "-d", "-t", "400")
    if "ExoPlayerImpl" not in log and "mpv" not in log.lower() and "MediaCodec" not in log:
        raise AssertionError("no player activity in logcat")
    return "player started"


def continue_watching():
    ui.back()
    time.sleep(2)
    ui.back()
    time.sleep(2)
    go_home()
    time.sleep(4)
    nodes = ui.screenshot("40_home_continue_watching")
    labels = " ".join(ui.visible_labels(nodes))
    if "Continue" not in labels:
        raise AssertionError("no Continue Watching row")
    return "continue watching row visible"


def library_flow():
    ui.tap_text(r"^Search$", timeout=10)
    field = ui.find(r"Search movies", timeout=5)
    if field is None:
        clear = ui.find(r"Clear search", timeout=3)
        if clear:
            ui.tap(clear)
        field = ui.find(r"Search movies", timeout=5)
    if field:
        ui.tap(field)
    ui.type_text("Inception")
    ui.key(66)
    ui.wait_for(r"Inception", timeout=40)
    nodes = ui.dump()
    target = next((n for n in nodes if "Inception" in n.label and n.bounds[1] > 300 and not n.focused), None)
    if target is None:
        raise AssertionError("no Inception result")
    ui.tap(target)
    time.sleep(4)
    save = ui.find(r"^(Add to library|Remove from library)$", timeout=8)
    if save is None:
        raise AssertionError(f"no library button; visible {ui.visible_labels()}")
    ui.tap(save)
    time.sleep(2)
    ui.screenshot("50_details_saved")
    go_home()
    ui.tap_text(r"^Library$", timeout=10)
    time.sleep(3)
    ui.wait_for(r"Inception", timeout=15)
    ui.screenshot("51_library")
    return "Inception in library"


def settings_flow():
    go_home()
    open_settings_tab()
    time.sleep(2)
    nodes = ui.screenshot("60_settings")
    ui.tap_text(r"(Playback|Player)", timeout=10)
    time.sleep(2)
    ui.screenshot("61_settings_playback")
    ui.back()
    return f"settings: {ui.visible_labels(nodes, 12)}"


def remove_addon():
    open_addons()
    for _ in range(4):
        target = ui.find(r"OpenSubtitles", timeout=5)
        deletes = [n for n in ui.dump() if n.desc == "Delete addon"]
        if target and deletes:
            # The row's delete button is the first one at or below the addon title.
            row = sorted((d for d in deletes if d.bounds[1] >= target.bounds[1] - 40), key=lambda d: d.bounds[1])
            if row:
                ui.tap(row[0])
                break
        ui.swipe_up()
    else:
        raise AssertionError(f"OpenSubtitles row or its delete button not found; visible {ui.visible_labels()}")
    ui.screenshot("70_delete_confirm")
    ui.tap_text(r"^Yes$", timeout=8)
    time.sleep(2)
    ui.screenshot("71_addons_after_remove")
    if ui.find(r"OpenSubtitles", timeout=3):
        raise AssertionError("OpenSubtitles still listed")
    return "removed (after Yes in the confirm dialog)"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--addon-host", default="10.0.2.2:7000")
    parser.add_argument("--title", default="Baseline app flows")
    args = parser.parse_args()
    test_addon = f"http://{args.addon_host}/manifest.json"

    ui.sh("logcat", "-c")
    ui.launch()
    flow = Flow(args.title, before=ui.ensure_app)
    flow.step("Onboarding (continue without account)", onboarding)
    flow.step("Install addon: Cinemeta", install_addon(CINEMETA, "Cinemeta"))
    flow.step("Install addon: OpenSubtitles", install_addon(OPENSUBS, "OpenSubtitles"))
    flow.step("Install addon: local test streams", install_addon(test_addon, "Agent Test Streams"))
    flow.step("Browse catalogs", browse_catalogs)
    flow.step("Search", search_title)
    flow.step("Open title details", open_details)
    flow.step("List streams", list_streams)
    flow.step("Start playback", start_playback)
    flow.step("Continue watching", continue_watching)
    flow.step("Library add/view", library_flow)
    flow.step("Settings", settings_flow)
    flow.step("Remove addon", remove_addon)
    with open(f"{ui.OUT}/logcat.txt", "w") as fh:
        fh.write(ui.sh("logcat", "-d", timeout=120))
    ok = flow.report()
    crashes = ui.sh("logcat", "-d", "-b", "crash")
    if crashes.strip():
        print("CRASH BUFFER:\n" + crashes[-4000:])
    sys.exit(0 if ok else 0)  # flows are evidence; failures are reported, not fatal


Flow = ui.Flow

if __name__ == "__main__":
    main()
