#!/usr/bin/env python3
# ─────────────────────────────────────────────────────────────────────────────
#  tools/rebrand.py — one-shot per-project renamer.
#
#  A companion to gray.properties: what the build derives from `gray.seed`
#  covers preference files, encoded arrays, sentinels, timings and cipher
#  parameters. This script covers what the build cannot: the *shape* of the
#  code — the package name, class names, folder layout, and their references
#  in the manifest, ProGuard rules, google-services.json and build script.
#
#  Two apps in the portfolio must not share the class name `WelcomePortal`,
#  the package `com.example.grayshell`, the drawable set name, or the folder
#  `startup/portal/reach/signal/vault/wire/blueprint`. This script rotates
#  every one of them in one pass, with a plan file for review before the
#  changes are written to disk.
#
#  Usage:
#      python tools/rebrand.py --config gray.properties       # plan only
#      python tools/rebrand.py --config gray.properties --apply
#      python tools/rebrand.py --config gray.properties --apply --package com.acme.reef --theme reef
#
#  Themes are just word-banks. Add your own to THEMES to broaden the vocabulary.
# ─────────────────────────────────────────────────────────────────────────────
import argparse
import hashlib
import io
import json
import os
import random
import re
import shutil
import sys
from pathlib import Path

# Force UTF-8 output so this works from a cp1251 Windows console.
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except AttributeError:
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")

REPO = Path(__file__).resolve().parents[1]
APP  = REPO / "app"
SRC  = APP / "src" / "main"
JAVA_ROOT = SRC / "java"


# ── Word banks per theme ────────────────────────────────────────────────────
THEMES = {
    # The template itself uses "portal/hatch": tower / forge / roost tones.
    # A new project must NOT use "portal/hatch". Add more themes freely.
    "reef": {
        "packages": ["core", "screens", "net", "attribution", "push", "prefs", "config", "connectivity"],
        "app":      "ReefApp",
        "router":   "TideRouter",
        "shell":    "WaveShell",
        "alert":    "TideAlert",
        "offline":  "NoCurrentScreen",
        "keyboard": "KeyboardTide",
        "reach":    "ConfigClient",
        "tracker":  "AttrHub",
        "push":     "FcmReef",
        "bus":      "PushBusReef",
        "vault":    "Prefs",
        "wire":     "NetMon",
        "blueprint":"Env",
        "loading":  "TideLoader",
        "fullscreen":"Immersive",
        "native":   "GameHost",
        "channel":  "ChannelReef",
        "result":   "ConfigResult",
        "drawables_prefix": "reef",
    },
    "canyon": {
        "packages": ["boot", "surface", "relay", "attr", "notif", "store", "env", "netmon"],
        "app":      "CanyonApp",
        "router":   "GorgeRouter",
        "shell":    "CanyonShell",
        "alert":    "GorgeAlert",
        "offline":  "GorgeOffline",
        "keyboard": "CanyonPan",
        "reach":    "GorgeClient",
        "tracker":  "AttrCanyon",
        "push":     "FcmCanyon",
        "bus":      "PushBusCanyon",
        "vault":    "PrefStore",
        "wire":     "LinkMon",
        "blueprint":"Env",
        "loading":  "GorgeLoader",
        "fullscreen":"FullScreen",
        "native":   "GameHost",
        "channel":  "GorgeChannel",
        "result":   "GorgeResult",
        "drawables_prefix": "cn",
    },
    # Train Sprint. Added per the "add a theme rather than reuse one" rule in
    # .cursor/START_HERE.md §2b — every name is genuinely distinct from the
    # template's, including the fullscreen helper (canyon only re-cases it).
    "rail": {
        "packages": ["ignition", "deck", "beacon", "dispatch", "ledger",
                     "circuit", "gauge", "siding", "harness"],
        "app":      "RailApp",
        "router":   "JunctionGate",
        "shell":    "CarriageShell",
        "alert":    "SemaphorePrompt",
        "offline":  "DeadTrackScreen",
        "keyboard": "PlatformSlide",
        "reach":    "DispatchClient",
        "tracker":  "AttrYard",
        "push":     "FcmWhistle",
        "bus":      "WhistleBus",
        "vault":    "Sidings",
        "wire":     "TrackMon",
        # "Manifest" would collide with android.Manifest in the push-permission
        # screen, so the depot metaphor carries the config bridge instead.
        "blueprint":"Depot",
        "loading":  "BoardingView",
        "fullscreen":"CabView",
        "native":   "GameHost",
        "channel":  "RouteChannel",
        "result":   "RouteResult",
        "drawables_prefix": "rl",
    },
    "orbit": {
        "packages": ["boot", "view", "attr", "push", "prefs", "net", "cfg"],
        "app":      "OrbitApp",
        "router":   "LaunchGate",
        "shell":    "OrbitShell",
        "alert":    "OptInPrompt",
        "offline":  "SignalLostScreen",
        "keyboard": "KeyboardSlide",
        "reach":    "CfgClient",
        "tracker":  "AttrHub",
        "push":     "FcmReceiver",
        "bus":      "PushRelay",
        "vault":    "Store",
        "wire":     "Uplink",
        "blueprint":"Env",
        "loading":  "OrbitLoader",
        "fullscreen":"WindowGlue",
        "native":   "GameHost",
        "channel":  "GateChannel",
        "result":   "GateResult",
        "drawables_prefix": "orb",
    },
}

# What each *current* file/class is called in the template. Every rebrand is a
# mapping from these names into new ones under the chosen theme.
CURRENT = {
    "package_root": "com.example.grayshell",
    "packages": {
        "startup":  "startup",
        "portal":   "portal",
        "reach":    "reach",
        "signal":   "signal",
        "vault":    "vault",
        "wire":     "wire",
        "blueprint":"blueprint",
        "core":     "core",
        "root":     "",
    },
    "classes": {
        "AppEntry":            "app",
        "WelcomePortal":       "router",
        "StreamPortal":        "shell",
        "AlertPortal":         "alert",
        "OfflinePortal":       "offline",
        "KeyboardPan":         "keyboard",
        "ReachDispatch":       "reach",
        "TrackingDispatch":    "tracker",
        "PushRelay":           "push",
        "PushBus":             "bus",
        "DataVault":           "vault",
        "Secrets":             None,          # too generic; leave it
        "NetWire":             "wire",
        "AppBlueprint":        "blueprint",
        "ChannelResult":       "result",
        "LoadingView":         "loading",
        "Fullscreen":          "fullscreen",
        "NativeContentActivity": "native",
    },
    "drawables_prefix": "gray_",
}


def slug(name: str) -> str:
    """PascalCase → snake_case for file names."""
    return re.sub(r"(?<!^)([A-Z])", r"_\1", name).lower()


def load_config(path: Path) -> dict:
    if not path.exists():
        raise SystemExit(f"config file not found: {path}")
    props = {}
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.split("#", 1)[0].strip()
        if "=" not in line:
            continue
        k, v = line.split("=", 1)
        props[k.strip()] = v.strip()
    return props


def build_plan(seed: str, package: str, theme: str) -> dict:
    if theme not in THEMES:
        raise SystemExit(f"unknown theme '{theme}'. Available: {', '.join(THEMES)}")
    rng = random.Random(int(hashlib.sha256(seed.encode()).hexdigest()[:16], 16))
    words = THEMES[theme]

    # Deterministic package layout: pick the same words for the same seed.
    pkg_pool = list(words["packages"])
    rng.shuffle(pkg_pool)
    if len(pkg_pool) < len(CURRENT["packages"]):
        pkg_pool += [f"pkg{i}" for i in range(len(CURRENT["packages"]) - len(pkg_pool))]

    pkg_map = {}
    for i, (old, _) in enumerate(CURRENT["packages"].items()):
        if old == "root":
            pkg_map[old] = ""
            continue
        # Ensure "core" stays if used, else a themed name.
        pkg_map[old] = pkg_pool[i]

    # Classes: pick the word-bank name where provided.
    class_map = {}
    for old, key in CURRENT["classes"].items():
        class_map[old] = words[key] if key else old

    return {
        "seed": seed,
        "theme": theme,
        "package_root": {
            "old": CURRENT["package_root"],
            "new": package,
        },
        "packages": pkg_map,
        "classes": class_map,
        "drawables_prefix": {
            "old": CURRENT["drawables_prefix"],
            "new": words["drawables_prefix"] + "_",
        },
    }


def collect_targets() -> list:
    """Files rebrand.py rewrites — one list, easy to audit."""
    patterns = [
        "app/build.gradle.kts",
        "app/proguard-rules.pro",
        "app/src/main/AndroidManifest.xml",
        # All of values/, not just strings.xml: theme names embed class tokens
        # ("Theme.App.Fullscreen"), and renaming them in the manifest while
        # leaving themes.xml alone breaks the resource link.
        "app/src/main/res/values*/*.xml",
        "app/src/main/res/xml/*.xml",
        "app/src/main/res/drawable*/**/*.xml",
        "app/src/main/java/**/*.kt",
        "settings.gradle.kts",
        "gray.properties",
        "gray.properties.example",
    ]
    files = set()
    for p in patterns:
        for f in REPO.glob(p):
            if f.is_file():
                files.add(f)
    return sorted(files)


def rewrite_source(text: str, plan: dict) -> str:
    """Textual substitution — safe because every replaced token is unique."""
    for old, new in plan["classes"].items():
        if old == new:
            continue
        text = re.sub(rf"\b{re.escape(old)}\b", new, text)
    old_root = plan["package_root"]["old"]
    new_root = plan["package_root"]["new"]
    if old_root != new_root:
        text = text.replace(old_root, new_root)
    for old_pkg, new_pkg in plan["packages"].items():
        if old_pkg in ("root", ""):
            continue
        if old_pkg == new_pkg:
            continue
        # Only replace when preceded by the new root package + '.'
        text = re.sub(
            rf"{re.escape(new_root)}\.{re.escape(old_pkg)}\b",
            f"{new_root}.{new_pkg}",
            text,
        )
        # The manifest names components relative to the namespace
        # (android:name=".startup.WelcomePortal"), which the fully-qualified
        # pattern above never sees. Left alone, every gray component points at
        # a package that no longer exists and the app dies on launch with a
        # ClassNotFoundException.
        text = re.sub(
            rf'(android:name=")\.{re.escape(old_pkg)}\.',
            rf"\g<1>.{new_pkg}.",
            text,
        )
    old_dp = plan["drawables_prefix"]["old"]
    new_dp = plan["drawables_prefix"]["new"]
    if old_dp != new_dp:
        text = text.replace(old_dp, new_dp)
    return text


def move_files(plan: dict, apply: bool) -> list:
    """Physical folder + file renames. Returns the list of moves."""
    moves = []
    old_root = plan["package_root"]["old"].replace(".", "/")
    new_root = plan["package_root"]["new"].replace(".", "/")

    src_root = JAVA_ROOT / old_root
    dst_root = JAVA_ROOT / new_root

    if not src_root.exists():
        raise SystemExit(f"source root not found: {src_root}")

    for path in sorted(src_root.rglob("*.kt")):
        rel = path.relative_to(src_root)
        parts = list(rel.parts)
        # Rename the leaf package folder.
        if len(parts) >= 2:
            old_pkg = parts[0]
            new_pkg = plan["packages"].get(old_pkg, old_pkg)
            parts[0] = new_pkg
        # Rename the file if its class was renamed.
        stem = path.stem
        new_stem = plan["classes"].get(stem, stem)
        parts[-1] = new_stem + ".kt"
        dst = dst_root.joinpath(*parts)
        moves.append((path, dst))

    # Drawable resource files carrying the "gray_" prefix.
    old_dp = plan["drawables_prefix"]["old"]
    new_dp = plan["drawables_prefix"]["new"]
    if old_dp != new_dp:
        for path in sorted(SRC.glob("res/drawable*/**/*")):
            if path.is_file() and path.name.startswith(old_dp):
                new_name = new_dp + path.name[len(old_dp):]
                moves.append((path, path.with_name(new_name)))

    if apply:
        for src, dst in moves:
            dst.parent.mkdir(parents=True, exist_ok=True)
            shutil.move(str(src), str(dst))
        # Prune empty old-root directories.
        if src_root.exists():
            for dirpath, _, _ in os.walk(src_root, topdown=False):
                p = Path(dirpath)
                if not any(p.iterdir()):
                    p.rmdir()
    return moves


def apply_rewrites(plan: dict, apply: bool) -> list:
    edited = []
    for path in collect_targets():
        original = path.read_text(encoding="utf-8")
        updated  = rewrite_source(original, plan)
        if updated != original:
            edited.append(path)
            if apply:
                path.write_text(updated, encoding="utf-8")
    return edited


def main() -> int:
    ap = argparse.ArgumentParser(description="Rebrand the gray template.")
    ap.add_argument("--config", default="gray.properties",
                    help="path to gray.properties (default: repo root)")
    ap.add_argument("--package", help="new applicationId; overrides gray.bundleId")
    ap.add_argument("--theme", help=f"one of {list(THEMES)}", default=None)
    ap.add_argument("--apply", action="store_true",
                    help="actually write changes; without it, prints the plan only")
    args = ap.parse_args()

    cfg_path = (REPO / args.config).resolve() if not Path(args.config).is_absolute() else Path(args.config)
    props = load_config(cfg_path)
    seed = props.get("gray.seed", "")
    if not seed or seed == "CHANGE-ME-EVERY-PROJECT":
        raise SystemExit("gray.seed missing or default — set it first (gradlew graySeed).")
    package = args.package or props.get("gray.bundleId", "")
    if not package:
        raise SystemExit("no package: pass --package or set gray.bundleId in gray.properties.")

    theme = args.theme
    if theme is None:
        rng = random.Random(int(hashlib.sha256(seed.encode()).hexdigest()[:16], 16))
        theme = rng.choice(list(THEMES))

    plan = build_plan(seed, package, theme)

    print("═══ REBRAND PLAN ═══")
    print(f"seed         : {seed[:10]}…  ({len(seed)} chars)")
    print(f"theme        : {theme}")
    print(f"package root : {plan['package_root']['old']}  →  {plan['package_root']['new']}")
    print(f"drawables    : {plan['drawables_prefix']['old']}  →  {plan['drawables_prefix']['new']}")
    print("packages     :")
    for old, new in plan["packages"].items():
        if old == "root": continue
        print(f"  {old:<10} → {new}")
    print("classes      :")
    for old, new in plan["classes"].items():
        if old == new: continue
        print(f"  {old:<24} → {new}")

    moves = move_files(plan, apply=False)
    edits = apply_rewrites(plan, apply=False)
    print(f"\nfile renames : {len(moves)}")
    print(f"text edits   : {len(edits)}")

    if not args.apply:
        print("\n(plan only — pass --apply to write the changes)")
        return 0

    move_files(plan, apply=True)
    apply_rewrites(plan, apply=True)
    print("\n✓ rebrand applied. Verify with `gradlew clean assembleDebug`.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
