#!/usr/bin/env python3
"""Regenerate the bundled models.dev provider/model catalog used by the in-app agent.

opencode loads the same catalog (https://models.dev/api.json, mirrored at
https://models.opencode.ai/api.json) and ships a build-time snapshot of it. We do the same:
this script downloads the catalog, keeps only the fields the app needs and writes a compact
asset to composeApp/src/androidMain/assets/agent/models_dev.json.

Usage: python3 scripts/update-models-catalog.py [--source URL] [--input FILE]
"""
import argparse
import json
import os
import sys
import urllib.request
from datetime import datetime, timezone

SOURCES = ["https://models.dev/api.json", "https://models.opencode.ai/api.json"]
OUT = os.path.join(os.path.dirname(__file__), "..", "composeApp", "src", "androidMain", "assets", "agent", "models_dev.json")

PROVIDER_KEYS = ["id", "name", "api", "npm", "env", "doc"]
MODEL_KEYS = ["id", "name", "family", "tool_call", "reasoning", "temperature", "attachment", "release_date", "status"]


def trim_model(model):
    out = {k: model[k] for k in MODEL_KEYS if k in model}
    limit = model.get("limit") or {}
    out["limit"] = {k: limit[k] for k in ("context", "input", "output") if k in limit}
    modalities = model.get("modalities") or {}
    if modalities.get("input"):
        out["input"] = modalities["input"]
    provider = model.get("provider")
    if provider:
        out["provider"] = {k: provider[k] for k in ("npm", "api") if k in provider}
    return out


def trim(catalog):
    providers = {}
    for pid in sorted(catalog):
        provider = catalog[pid]
        entry = {k: provider[k] for k in PROVIDER_KEYS if k in provider}
        models = provider.get("models") or {}
        entry["models"] = {mid: trim_model(models[mid]) for mid in sorted(models)}
        providers[pid] = entry
    return providers


def fetch(url):
    req = urllib.request.Request(url, headers={"User-Agent": "nuvio-agent-catalog-script"})
    with urllib.request.urlopen(req, timeout=60) as resp:
        return json.load(resp)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", help="catalog URL (default: models.dev, then models.opencode.ai)")
    parser.add_argument("--input", help="read a previously downloaded api.json instead of fetching")
    parser.add_argument("--output", default=OUT)
    args = parser.parse_args()

    if args.input:
        with open(args.input) as fh:
            catalog, source = json.load(fh), args.input
    else:
        last_error = None
        for url in ([args.source] if args.source else SOURCES):
            try:
                catalog, source = fetch(url), url
                break
            except Exception as error:  # noqa: BLE001
                last_error = error
                print(f"warning: {url}: {error}", file=sys.stderr)
        else:
            sys.exit(f"could not download catalog: {last_error}")

    providers = trim(catalog)
    payload = {
        "source": source,
        "generatedAt": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "providers": providers,
    }
    os.makedirs(os.path.dirname(args.output), exist_ok=True)
    with open(args.output, "w") as fh:
        json.dump(payload, fh, separators=(",", ":"), sort_keys=False)
    models = sum(len(p["models"]) for p in providers.values())
    print(f"wrote {args.output}: {len(providers)} providers, {models} models, {os.path.getsize(args.output)} bytes")


if __name__ == "__main__":
    main()
