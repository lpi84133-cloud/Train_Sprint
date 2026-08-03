---
description: Compressed project map for AI agents — every file, its role, and the one thing you must not get wrong about it. Read when you need to locate code fast without opening the full guide.
globs: app/src/main/java/**/*.kt
alwaysApply: false
---

# AGENT map — Kotlin gray part

Fast lookup. For *why* any of it is the way it is, follow the "see" column.

## Control flow in ten lines

```
AppEntry.onCreate            Firebase + AppCheck, UrlGuard warning,
                             TrackingDispatch.prime()   ← init only, no traffic
   ↓
WelcomePortal.onCreate       warm-push handoff? → finish
                             RunChannel.NATIVE?  → game, no network
                             UNDECIDED + offline? → OfflinePortal, first frame
                             else → LoadingView + route()
   ↓
WelcomePortal.route()        cold push URL (UrlGuard) → StreamPortal
                             UNDECIDED → handleFirstLaunch()
                             STREAM    → handleOnlineReturn()
                             NATIVE    → goNative()
   ↓
handleFirstLaunch()          ensureInternet → ignite+retrace → awaitAttribution
                             → ReachDispatch.fetchChannel → STREAM | NATIVE
   ↓
goGray()/goNative()          handOver { }: the bar fills to 100% first, always
```

## File map

### `startup/`
| File | Role | Do not get wrong | See |
|---|---|---|---|
| `AppEntry.kt` | Application. Firebase, AppCheck, `TrackingDispatch.prime()` | `prime` (not `start`) belongs here. Moving it costs 30 s of attribution | launch_flow §1 |
| `WelcomePortal.kt` | Launcher + router + state machine | NATIVE never flips to WebView, not even on push. `ensureInternet` must not leak its collect | launch_flow §4–5 |

### `portal/`
| File | Role | Do not get wrong | See |
|---|---|---|---|
| `StreamPortal.kt` | Full-screen WebView shell | `deepestHop` (retry resumes here) and `lastMainFrameUrl` (last settled) are **different fields**. The cover is only for the session's first page. No `onReceivedError` branch may end without navigating or dropping the cover | webview, pitfalls #10 #30 #31 #33 |
| `KeyboardPan.kt` | Slides the WebView; never resizes | IME insets are stripped from the WebView; clamp to the *settled* height, remembered per orientation | keyboard |
| `AlertPortal.kt` | Push-permission screen | Snooze on skip; OS "deny forever" is permanent | guide |
| `OfflinePortal.kt` | No-wifi + Retry + auto-retry | With no return URL it restarts the router, so AppsFlyer is asked for the first time | pitfalls #20 |

### `reach/`
| File | Role | Do not get wrong | See |
|---|---|---|---|
| `TrackingDispatch.kt` | AppsFlyer: prime / ignite / retrace / GCD fallback | Conversion fields go out verbatim. `setDebugLog(BuildConfig.DEBUG)` | launch_flow, pitfalls #19 |
| `ReachDispatch.kt` | Config POST + parse | 404 = a real "no". A thrown request = `unreachable()`, persists nothing. Response URL passes `UrlGuard` | guide |

### `signal/`
| File | Role | Do not get wrong | See |
|---|---|---|---|
| `PushRelay.kt` | FCM service | Image fetch off the main thread. NATIVE users never get a WebView. URLs pass `UrlGuard` | play_moderation §3b |
| `PushBus.kt` | Warm/cold handoff, process-wide | Warm URLs are never persisted | webview |

### `vault/`
| File | Role | Do not get wrong | See |
|---|---|---|---|
| `DataVault.kt` | Prefs + encrypted prefs | Filenames and every key come from `BuildConfig`. Encrypted-store failure falls back in-memory, never to plaintext | fingerprint |
| `Secrets.kt` | XOR decoder | Parameters and variant come from `BuildConfig`. Never called with a literal array | fingerprint |

### `core/`
| File | Role | Do not get wrong | See |
|---|---|---|---|
| `Trace.kt` | The only logger | Guarded by `BuildConfig.DEBUG`; a bare `Log.i` elsewhere ships | play_moderation §4 |
| `UrlGuard.kt` | Host-suffix allowlist | Gates config answers and push payloads only, **not** in-WebView navigation | play_moderation §3b |
| `UserAgent.kt` | The single UA builder | Every caller reads from here. Version tuple is seeded per project | user_agent |

### `blueprint/`, root, `wire/`
| File | Role |
|---|---|
| `AppBlueprint.kt` | Thin read-only bridge to `BuildConfig` + `Secrets`. No logic |
| `ChannelResult.kt` | `answered` separates "server said no" from "nobody answered" |
| `NetWire.kt` | `registerDefaultNetworkCallback` + TCP probe |
| `LoadingView.kt` | Splash. Indeterminate bar, `complete { }` gates every handover |
| `Fullscreen.kt` | The one immersive helper. Call **after** `setContentView` (pitfalls #2) |
| `NativeContentActivity.kt` | **Stub.** Must be replaced with the real game before shipping |

## Build layer

| File | Role |
|---|---|
| `gray.properties` | Gitignored. Seed + identity + credentials + allowlist. The only file an operator edits |
| `gray.properties.example` | Documented template |
| `app/build.gradle.kts` | Fingerprint engine (top half) + `graySeed` / `grayReport` tasks |
| `tools/rebrand.py` | Renames package, folders, classes, drawable prefix |

## Things that look like bugs but are not

- `ReachDispatch` returns `native()` for a URL that fails `UrlGuard`, but the
  caller does **not** persist NATIVE for it — the endpoint answered, our own
  gate rejected it, so the question stays open next launch.
- `OfflinePortal` with no return URL relaunches `WelcomePortal` instead of
  patching state. Patching would settle the install as organic (pitfalls #20).
- `setSupportMultipleWindows(false)` is deliberate. Enabling it and hosting the
  popup in the same WebView crashes (pitfalls #21).
- `onPageFinished` returns early when `loadFailed` — a failed load reaches it
  with the error page committed and would reset the retry budget.
- The `Mozilla/5.0 (Linux; Android ` literal in `UserAgent.kt` is intentional;
  only the version tuple is a fingerprint (user_agent §2).

## Rule index

`kotlin_gray_guide` · `kotlin_launch_flow` · `kotlin_webview` ·
`kotlin_keyboard` · `kotlin_user_agent` · `kotlin_gray_pitfalls` ·
`kotlin_fingerprint` · `play_moderation_hardening` · `custom_screens`
