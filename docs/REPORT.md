# Final report: in-app AI agent for a Stremio-compatible Android app

_Status as of 2026-10-05. Branches: `claude/streamio-android-agent-ur2z97` (requested) and
`ccr-09b73190-s7c7p6` (session branch); both hold the same commits._

## 1. Base app

- The **official Stremio Android app** (`com.stremio.one`) is closed source; it was also removed
  from Google Play in January 2026. Stremio's GitHub only has libraries.
- Chosen base: **[NuvioMedia/NuvioMobile](https://github.com/NuvioMedia/NuvioMobile)**
  (`cmp-rewrite`), a phone/tablet Kotlin Multiplatform + Compose app on Google Play as
  `com.nuvio.app`, GPL-3.0. It is the most complete and most active open phone client (about 2.6k
  commits with daily pushes; addons, catalogs, search, ExoPlayer + libmpv, subtitles, library,
  sync, downloads, Trakt/Simkl/MDBList). The comparison with NuvioTV, Lumera, OmniStream,
  stremio-native and Cove is in the README.
- Imported with full history; license kept; upstream README preserved in `docs/UPSTREAM_README.md`.

## 2. Phase 1 baseline (before any agent change)

| Check | Result |
|---|---|
| `./gradlew :androidApp:assembleFullDebug` | ✅ builds (161 MB universal debug APK), 234 upstream compiler warnings |
| Unit tests (`:composeApp:testAndroidHostTest`) | 1,515 tests, **6 failing upstream** (see §6) |
| Lint (`:androidApp:lintFullDebug`) | ✅ 0 errors, 17 warnings |
| On-device | No KVM in the dev container, so the emulator runs in GitHub Actions (`reactivecircus/android-emulator-runner`, API 34 x86_64, Pixel 6 profile) |

## 3. What was built

See README → *Agent architecture* and [`docs/agent-tools.md`](agent-tools.md) (generated, 74 tools).

- **Tool registry**: one registration per tool with JSON-schema params, validation/coercion,
  structured JSON results and errors, confirmation gating for destructive/account actions,
  timeouts and result truncation.
- **Exhaustiveness**: Nuvio has no ViewModels; screens use singleton repositories. A reflection
  test over 19 repositories and 3 UI bridges fails if any public action has neither a tool nor a
  documented exemption (credentials, sync internals, lifecycle, iOS-only, visual editors).
- **App control**: navigation and every settings page; search, discover and catalogs; title and
  episode details; the stream list → player; player play/pause/seek/speed/audio/subtitles/subtitle
  delay/next episode/close; library; progress and watched state; addons
  (install/remove/enable/reorder/refresh); 60+ settings; profiles and sign-out; downloads;
  collections; read-only state tools.
- **Built-ins**: `web_search` (DuckDuckGo/SearXNG keyless; Brave, Tavily, private SearXNG; falls
  back to Wikipedia), `fetch_url`, direct addon protocol (manifest/catalog/meta/stream/subtitles),
  calculator, time, memory.
- **Providers**: models.dev catalog snapshot (226 providers, 8,396 models) plus in-app refresh
  (models.dev → models.opencode.ai, as opencode does). Clients: OpenAI-compatible (incl. DeepSeek
  `reasoning_content`), Azure OpenAI, Anthropic, Gemini, Vertex (Gemini + Claude), Bedrock (SigV4 +
  event stream). Custom endpoints, headers, URL variables, model ids and per-model limits.
  Keystore-encrypted keys, excluded from backup and device transfer.
- **Agent loop**: multi-step plan/act/check; parallel read-only calls; retries with backoff;
  fallback to chat-only when a provider rejects tools; tool errors fed back to the model; context
  trimming with summary; cancellation; persisted history; streaming UI with collapsible tool
  cards; floating button on every screen; voice input.

## 4. Verification

### Unit tests (JVM, `com.nuvio.app.agent.*`), all passing
| Suite | Tests | Covers |
|---|---|---|
| ToolRegistryTest | 10 | schema output, validation/coercion, malformed JSON recovery, confirmations, errors, timeout, truncation |
| AgentToolCatalogTest | 6 | full catalog sanity, confirmation on destructive tools, required capabilities, **coverage gate** |
| ProviderClientsTest | 14 | recorded DeepSeek streams (tool call + text), Azure routing, Anthropic, Gemini, Bedrock event stream + SigV4 (AWS vector), error classification |
| AgentSessionTest | 10 | multi-step loop, parallel reads, error recovery, retries, tool rejection fallback, step limit, declined confirmation, cancellation, context trimming |
| WebAndUtilityTest | 14 | DuckDuckGo (recorded), SearXNG, Brave, Tavily, Wikipedia parsers, HTML→text, fetch limits, calculator, catalog mapping, Keystore store contract, memory |
| AgentToolDocsTest | 1 | `docs/agent-tools.md` matches the registry |
| LiveProviderTest | 3 | **real DeepSeek `deepseek-flash`** (skipped without a key) |

Full suite with the agent: **1,580 tests, 5 failures, all of them upstream failures that already
existed** (§6); the 6th baseline failure (Robolectric download) is fixed by the mirror setting.

### Live provider runs (DeepSeek `deepseek-flash`, real network)
- *"Who directed The Matrix?"* → `web_search` (DuckDuckGo throttled → model tried other
  backends → `fetch_url` Wikipedia) → "Lana and Lilly Wachowski" ✅
- *"Find The Matrix's IMDb id via the Cinemeta addon"* → `addon_catalog` → `tt0133093` ✅
- *"136 minutes in hours"* → `calculate` → 2.27 ✅

### Release build
`assembleFullRelease` with R8 minify and resource shrinking ✅ (≈45 MB per ABI). The mapping keeps
the agent's kotlinx-serialization serializers. The release APK installs and launches on the
emulator.

### On-device (GitHub Actions emulator, API 34 x86_64, Pixel 6 profile)
Flows are driven over adb/uiautomator by `scripts/ci/*.py`; screenshots, uiautomator dumps,
screen recordings of every agent task, logcat and the mock-LLM log are uploaded as the
`emulator-evidence` artifact of each run. A local Stremio test addon (ffmpeg-generated video plus
EN/ES subtitles) serves streams at `10.0.2.2:7000`.

**Run #18** (full build at `28f0f8a9`, R8 release APK), baseline flows: **13/13 pass**

| Flow | Release |
|---|---|
| Onboarding (guest → create profile → Home) | ✅ |
| Install addon: Cinemeta / OpenSubtitles / local test addon | ✅ ✅ ✅ |
| Home catalogs | ✅ |
| Search "The Matrix" | ✅ |
| Title details | ✅ |
| Stream list (test addon) | ✅ |
| Playback (ExoPlayer + h264/AAC decoders up) | ✅ |
| Continue Watching row after playback | ✅ |
| Library add (details → More actions → Add to library) and Library tab | ✅ |
| Settings | ✅ |
| Remove addon (with confirm dialog) | ✅ |

Agent, configured **through the in-app settings UI** (custom OpenAI-compatible endpoint → scripted
mock model on the host), in run #18:

| Task | Result |
|---|---|
| Configure provider (base URL, Keystore-encrypted key, model) | ✅ |
| "Play the first stream and turn on English subtitles" | ✅ `get_app_state → play_title → play_stream → player_select_subtitles → get_player_state`; player verified in logcat |
| "What's on my continue-watching list?" | ✅ `get_continue_watching` → "Continue watching: The Matrix." |
| "Install the OpenSubtitles addon" | ⚠️ `install_addon` succeeded; the check failed only because the new row was below the fold (fixed in the script) |
| "Find The Matrix and open its details" / "Add this to my library" | ⚠️ tools ran and navigated, but the *mock* picked the wrong search result (Inception); fixed in the mock |
| "Search the web … then open their other movie" | ⚠️ the prompt was cut to 30 chars by `adb input text`; typing now goes in chunks |
| Destructive action asks for confirmation | ⚠️ the dialog appeared ("Remove addon? The assistant wants to uninstall 'OpenSubtitles v3'") but the script couldn't see its test tags; it now taps the dialog's buttons by label |

**Bug found only on the device:** every agent request failed with `PatternSyntaxException` because
the `${NAME}` placeholder regex ended in a bare `}` — accepted by the JVM that runs unit tests,
rejected by Android's ICU engine. Fixed, with `RegexPortabilityTest` scanning agent sources for it.

The debug build (unminified) hits a 5 s input-dispatch ANR during its first cold start on the
software-rendered CI emulator, which derailed its run; the script now does a warm-up launch.

**Runs #19–#20** (same APKs, harness fixes): #19's runner stopped reporting (no log, no artifacts)
and #20 hung after the emulator dropped off adb — both traced to a warm-up `am start -W` with no
timeout, now removed, with every adb call bounded and one reconnect attempt. Before the emulator
dropped, #20 re-confirmed **13/13 baseline flows** on the release build and the agent task
**"Install the OpenSubtitles addon" passed end to end** (`install_addon` → addon listed).
#20 also exposed a real agent bug: `search_titles` could return the *previous* search's results
(the Search screen starts the query after a debounce, and the tool only waited 400 ms), so "Find The
Matrix" opened Inception. The tool now starts the search itself (`SearchRepository.search` flips the
state to loading synchronously); `play_title` does the same for streams.

The DeepSeek run on the emulator needs the `DEEPSEEK_API_KEY` Actions secret; the same agent code
was exercised against real `deepseek-flash` by the JVM live tests (§4).

## 5. Known limitations
- No KVM in the development container: all on-device evidence comes from CI emulators (artifacts
  are kept 14 days).
- `DEEPSEEK_API_KEY` must be added as an Actions secret by the repo owner (automated secret
  writes were blocked); until then the emulator agent tasks use the scripted mock provider and
  the live JVM tests are skipped in CI.
- Nuvio's own backend (Supabase), Trakt, TMDB and similar keys aren't configured in this fork's
  builds, so tests use guest mode.
- Public web search without keys is best-effort: DuckDuckGo throttles bursts (handled by the
  fallbacks) and public SearXNG instances often disable JSON.
- Vertex AI and Bedrock clients are tested against recorded or synthetic responses and the AWS
  SigV4 vector, not against live accounts (no credentials available).
- Subtitle rendering can't be read through uiautomator; on-device subtitle checks rely on the
  tool result, the player state and screenshots/video.
- The iOS target (upstream KMP) isn't built here (no macOS); the shared bridge code is pure
  Kotlin, and the agent itself lives in `androidMain`.

## 6. Flows broken upstream before this work
- Unit tests: `CustomPosterUrlResolverTest.resolve_pattern_without_any_placeholders_returns_as_is`,
  `RatingsVisibilityTest.allEpisodeLayoutsRespectVisibilityForFetchedAndAddonRatings`,
  `PlayerAutoPlayTest` (2 cases), `WatchProgressIdentityTest` (legacy payload). `RootTabHostTest`
  failed only because Robolectric couldn't download `android-all` (rate-limited); fixed via
  `ROBOLECTRIC_REPO`.
- Lint: 17 upstream warnings, including an empty `checkServerTrusted` (trust-all TLS manager) in
  upstream code, worth reviewing upstream.
- Build: upstream's daemon sizes (12 GB + 8 GB) exceed hosted runners; reduced in CI.
- Upstream issue/PR automation workflows would act on this repository; removed.
- Guest onboarding: after *Continue Without Account* the app shows an empty *Who's watching?*
  screen (a guest has no profiles, and the screen offers only *Manage Profiles*). You have to
  tap Manage Profiles → Add Profile → Create Profile before reaching Home. It isn't broken, but
  it's easy to mistake for a hang. The CI flow does these steps.
- On slow CI emulators the system sometimes shows "Pixel Launcher isn't responding" over the
  app. This is an emulator issue, not an app issue; the flow scripts tap *Wait*.
- _On-device baseline: pending (see §4)._
