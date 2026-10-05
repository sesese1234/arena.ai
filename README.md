# Nuvio + in-app AI assistant

An Android media app for Stremio-compatible addons with a built-in AI agent that can operate the
whole app: search, open titles, pick streams, control the player (subtitles, audio, seek, speed,
next episode), manage the library, addons, downloads and settings, and search the web. It works
with any model provider in the [models.dev](https://models.dev) catalog (the list
[opencode](https://github.com/sst/opencode) uses) or any custom OpenAI-compatible endpoint.

## Upstream project and license

This repository is a fork of **[Nuvio Mobile](https://github.com/NuvioMedia/NuvioMobile)** by
NuvioMedia (branch `cmp-rewrite`, imported with its full git history). Nuvio Mobile is a Kotlin
Multiplatform / Compose Multiplatform app published on Google Play as `com.nuvio.app`. All credit
for the media app belongs to the Nuvio authors. The upstream README is kept at
[`docs/UPSTREAM_README.md`](docs/UPSTREAM_README.md).

Like upstream, this project is licensed under the **GNU General Public License v3.0**
([`LICENSE`](LICENSE)).

### Why Nuvio Mobile?

The official Stremio Android app is closed source (Stremio's GitHub only publishes libraries such
as `stremio-core-kotlin` and player forks), so the agent had to be built on an open client.
Candidates evaluated in October 2026:

| Project | Form factor | Activity | Notes |
|---|---|---|---|
| **NuvioMedia/NuvioMobile** | **Phone / tablet** | ~2.6k commits, pushed daily | Most complete phone client: addons, catalogs, search, ExoPlayer + libmpv, subtitles, library, sync, downloads, Trakt/Simkl/MDBList. **Chosen.** |
| NuvioMedia/NuvioTV | Android TV | ~5.8k commits, daily | Same family, TV-only UI. |
| LumeraD3v/Lumera | Android TV | ~150 commits, last push Apr 2026 | TV-only, less active. |
| sauliiin/open-stremio-mobile (OmniStream) | Phone | 46 commits | Smaller, Portuguese-first. |
| stremio-native/stremio-android | Phone | 24 commits, Jul 2026 | Early stage. |
| coveninja/cove | Desktop + Android | active | AGPL, desktop-first. |

Nuvio Mobile built cleanly (`./gradlew :androidApp:assembleFullDebug`) once the SDK was installed.

## Building

Requirements: JDK 21, Android SDK with `platforms;android-37.0` and `build-tools;37.0.0`.

```bash
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew :androidApp:assembleFullDebug      # debug APK (package com.nuviodebug.com)
./gradlew :androidApp:assembleFullRelease    # R8-minified release APKs per ABI (debug-signed without a keystore)
./gradlew :composeApp:testAndroidHostTest --tests 'com.nuvio.app.agent.*'   # agent unit tests
./gradlew :androidApp:lintFullDebug
```

Notes:
- Upstream `gradle.properties` requests 12 GB + 8 GB daemons. On smaller machines put lower limits
  in `~/.gradle/gradle.properties` (CI uses `-Xmx6g`, Kotlin in-process).
- Nuvio's own backend (Supabase), Trakt/Simkl and TMDB keys are optional `local.properties` entries;
  without them use **Continue Without Account** on first launch.
- Live provider tests run when `DEEPSEEK_API_KEY` is set (`LiveProviderTest`).

CI (`.github/workflows/agent-ci.yml`) builds debug + release APKs, runs unit tests and lint, then
boots an API 34 emulator for each APK. There it runs the core app flows and the agent tasks over
adb/uiautomator, with a hermetic local test addon (direct HTTP stream + EN/ES subtitles), and
uploads APKs, screenshots, UI dumps and logcat as artifacts.

## Using the assistant

Tap the ✨ button (bottom-right on every screen, top-right in the player) to open the chat. The
panel is compact by default so you can watch the app change while the agent works; expand it to
full screen if you prefer. Long-running tasks can be stopped at any time. Tool calls appear as
collapsible cards (arguments + JSON result). Voice input uses the system speech recognizer when
one is available.

Destructive or account-affecting actions (removing addons or library items, clearing the
library, deleting collections, cancelling downloads, signing out, switching profiles, forgetting
memory) always show a confirmation dialog first.

### Setting up a provider

Assistant → ⚙ → **Add provider**:

- **From the catalog**: search the bundled models.dev snapshot (226 providers / 8k+ models), pick a
  provider, paste the key, choose models. Most providers are OpenAI-compatible (DeepSeek,
  OpenRouter, Groq, Mistral, xAI, Together, Fireworks, Cerebras, DeepInfra, …). Native clients
  exist for **Anthropic**, **Google Gemini**, **AWS Bedrock** (SigV4 access keys or a Bedrock API
  key; set `AWS_REGION`), **Azure OpenAI** (`AZURE_RESOURCE_NAME`, `AZURE_API_VERSION` or `v1`),
  and **Vertex AI** for Gemini and Claude (`GOOGLE_VERTEX_PROJECT`, `GOOGLE_VERTEX_LOCATION`, plus
  a service-account JSON key or an access token). All of them stream tokens and support tool
  calling.
- **Custom endpoint**: any OpenAI-compatible server, e.g. Ollama `http://<host>:11434/v1`
  (`http://10.0.2.2:11434/v1` from the emulator), LM Studio `http://<host>:1234/v1`, LiteLLM,
  vLLM, OpenRouter `https://openrouter.ai/api/v1`. Add custom headers, `${VARIABLE}` URL templates
  and any model id.
- Per model: context length, max output tokens, temperature, tool support. Globally: default
  provider/model, max steps, temperature, parallel tool calls, reasoning display.
- **Refresh from models.dev** downloads the current catalog (falling back to opencode's mirror
  `models.opencode.ai`). Regenerate the bundled snapshot with
  `python3 scripts/update-models-catalog.py`.

Keys are encrypted with AES-256-GCM using a non-exportable **Android Keystore** key. They are
never logged, and the app disables backup and excludes agent data from Android 12+ device
transfer.

Example: DeepSeek → Add provider → search "DeepSeek" → paste key → select `deepseek-flash`.

### Web search backends

| Backend | Needs | Notes |
|---|---|---|
| DuckDuckGo (default) | nothing | Lite → HTML endpoints; when DuckDuckGo throttles, falls back to Wikipedia search. |
| SearXNG public instance | nothing | Any instance with JSON output enabled (HTML results are parsed too). |
| Brave Search | API key | `X-Subscription-Token`. |
| Tavily | API key | Returns a synthesized answer as well. |
| SearXNG (yours) | URL (+ optional bearer) | Self-hosted. |

`fetch_url` downloads up to 2 MB within 20 s and returns readable text, paged by `offset`/`max_chars`.

## Agent architecture

```
composeApp/src/commonMain/.../agent/AgentBridge.kt     UI → agent handles (navigation, stream picker, player, search box)
composeApp/src/androidMain/.../agent/
  core/      ToolRegistry, schema DSL, argument validation, confirmations, structured errors
  tools/     app tools (state, navigation, browse, playback, player, library, progress, addons,
             settings, account, downloads, collections) + built-ins (web, addon protocol,
             calculator, time, memory); AgentToolCoverage (exhaustiveness contract)
  llm/       OpenAI-compatible/Azure, Anthropic, Gemini, Vertex, Bedrock clients; SSE + AWS
             event-stream parsing; error classification
  catalog/   models.dev catalog parsing + npm → protocol mapping
  config/    settings store, Keystore SecretStore
  runtime/   AgentSession (loop), ContextManager (trimming), SystemPrompt
  ui/        floating button overlay, chat, settings screen
```

- **Tools call existing app logic.** Screens publish handles to `AgentBridge` while they are
  composed: `MainAppContent` publishes the navigator and its play flow, `StreamDestination` its
  open-stream action, and the player its runtime. The player's subtitle and audio sheets and the
  agent share the same functions. Other tools call the singleton repositories directly.
- **One registration per tool**: `registry.tool("name") { description; params { … }; covers("Repo.method"); confirm { … }; run { args -> … } }`.
  A new feature becomes a tool with one block. `AgentToolCatalogTest` fails when a public method
  on a covered repository or bridge has neither a tool nor a documented exemption
  (`AgentToolCoverage.EXEMPTIONS`).
- **Loop**: plan → act → check → report, with up to N steps. Read-only calls run in parallel.
  Transient errors (429/5xx/network) are retried with backoff. If a provider rejects tools, the
  agent continues as plain chat. Tool errors and malformed arguments go back to the model so it
  can recover. Old turns are trimmed (old tool output first, then whole turns replaced by a
  summary) to fit the context window. Conversations persist, and history is browsable.
- **System prompt**: describes the app, the current screen and player state, installed addons,
  remembered preferences, the tool catalog by area, and how to behave.

The full tool reference is in [`docs/agent-tools.md`](docs/agent-tools.md), generated from the
registry by `AgentToolDocsTest`.

## Testing

- `composeApp/src/androidHostTest/.../agent/`: unit tests covering
  - the registry and schemas, plus the coverage gate;
  - each provider client against recorded fixtures (real DeepSeek streams; Anthropic, Gemini and
    Bedrock in their documented formats);
  - the SigV4 test vector;
  - the agent loop with a scripted fake provider (multi-step, parallel calls, retries, tool
    rejection, malformed arguments, step limit, cancellation, context trimming);
  - the web search parsers (recorded DuckDuckGo page, SearXNG, Brave, Tavily, Wikipedia), HTML
    extraction, fetch limits, the calculator, catalog mapping and secret encryption;
  - live DeepSeek runs (`LiveProviderTest`, when a key is set).
- `scripts/ci/baseline_flows.py`: core app flows on an emulator.
- `scripts/ci/agent_flows.py`: agent tasks end to end through the real UI, either against
  DeepSeek or the scripted mock OpenAI-compatible server in `scripts/mock-llm/`.
- See [`docs/REPORT.md`](docs/REPORT.md) for results, evidence and known limitations.
