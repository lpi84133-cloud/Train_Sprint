# START HERE — foollegends (native Kotlin gray-part template)

> **Read this file the first time you touch this repo. Then read
> `.cursor/rules/kotlin_fingerprint.mdc` and `gray.properties.example`.**
> The rest of `.cursor/rules/` is the reference material this file points
> back to when you need it.

## 1. What this repo is

Native Kotlin (non-Flutter) Android application. A "gray part" — WebView
shell + AppsFlyer attribution gate + config endpoint + FCM push — sits in
front of a native game. Non-organic installs route to the WebView; organic
installs stay on the game.

There will be many apps shipped from this template. What matters is that no
two of them share a **fingerprint** — identical package names, class names,
storage files, JS sentinels, User-Agent strings, encoded byte arrays, or
"canonical" 3-day / 15-second constants. A clustering pass across the store
does not care that a class was renamed; it looks at what renaming leaves
alone. So renaming by hand is not enough — the build itself has to do it.

## 2. The two ways per-project uniqueness is enforced

### 2a. `gray.properties` + Gradle-driven derivation

Everything the code reads at runtime comes from `BuildConfig`. The build
script (`app/build.gradle.kts`) turns one line of `gray.properties`
(`gray.seed`) into the whole set of values via a deterministic RNG:

- SharedPreferences file names + every key inside them
- JS injection sentinels + the JavaScript bridge name
- FCM notification channel id + human-facing title
- XOR codec seed byte array + multiplier + offset + algorithm variant
- Push snooze duration, attribution timeouts, config timeout, redirect budget,
  heartbeat interval, safe-area re-inject delay, connectivity grace, ...
- Chrome major / build / patch numbers presented in the User-Agent

Two projects with different seeds share **no** value in any of the above.
The values are compiled into `BuildConfig.*`; the source tree contains no
`259200`, no `Chrome/131.0.6778.135`, no `fl_state`, no `KbPan`.

### 2b. `tools/rebrand.py` — one-shot renamer

Whatever the seed cannot touch — the shape of the code — is handled by
`tools/rebrand.py`. It rotates:

- Application package (`com.example.grayshell` → `com.acme.reef`)
- Kotlin sub-package folder names (`startup/portal/reach/…` → theme-picked)
- Every class name in the gray flow
  (`WelcomePortal`/`StreamPortal`/`DataVault`/... → theme-picked)
- Drawable resource prefix (`gray_*` → theme-picked)

Themes are word banks in the script. Pick one per project; the script
prints a plan first, applies with `--apply`.

## 3. Setting up a new project — three commands

```powershell
# 1. Fresh seed. Never reuse one across the portfolio.
gradlew graySeed

# 2. Copy the template and paste the seed + your credentials in.
Copy-Item gray.properties.example gray.properties
notepad gray.properties          # fill bundleId, config URL, AF key, hosts

# 3. Rotate the code shape. rebrand.py picks a theme by seed unless --theme.
python tools\rebrand.py --apply
```

After that: `gradlew assembleDebug` builds; `adb install -r
app\build\outputs\apk\debug\app-debug.apk` deploys.

## 4. Where to look for what

| Question | File |
|---|---|
| Where is anything? (fast lookup) | `.cursor/rules/AGENT.md` |
| Building a new project, stage by stage | `.cursor/DEV_PLAYBOOK.md` |
| Ship gate — device QA before upload | `.cursor/FINAL_CHECKLIST.md` |
| How is the fingerprint derived? | `app/build.gradle.kts` (the top half — read it) |
| What values are per-project? | `gray.properties.example` (each key documented inline) |
| Per-project uniqueness rules | `.cursor/rules/kotlin_fingerprint.mdc` |
| What clusters a portfolio in Play | `.cursor/rules/play_moderation_hardening.mdc` |
| Architecture + config contract | `.cursor/rules/kotlin_gray_guide.mdc` |
| The launch state machine | `.cursor/rules/kotlin_launch_flow.mdc` + `startup/WelcomePortal.kt` |
| WebView shell contract | `.cursor/rules/kotlin_webview.mdc` + `portal/StreamPortal.kt` |
| Keyboard | `.cursor/rules/kotlin_keyboard.mdc` + `portal/KeyboardPan.kt` |
| User-Agent + appid/appname decision | `.cursor/rules/kotlin_user_agent.mdc` |
| Real bugs + fixes (34 entries) | `.cursor/rules/kotlin_gray_pitfalls.mdc` |
| Screen artwork contract | `.cursor/rules/custom_screens.md` |

## 5. Invariants — things that must never break

1. **Once NATIVE, stay NATIVE.** A push URL for a NATIVE user shows the
   notification but does not open a WebView. Enforced in `WelcomePortal`
   and `PushRelay`.
2. **Undecided + offline → No-WiFi first frame.** No splash, no
   attribution, nothing persisted. The screen restarts the router when the
   link returns.
3. **A `NATIVE` decision is only persisted when the endpoint really
   answered AND the request carried attribution.** A network failure or an
   empty attribution leaves the mode UNDECIDED for the next launch.
4. **AppsFlyer `init` in the Application, `start` from an Activity, after
   a connectivity check.** All three matter; see pitfalls #19.
5. **Push URLs are one-shot.** Warm URLs never persist; cold URLs live in
   the vault until the router consumes them exactly once.
6. **URLs from outside the WebView pass `UrlGuard`.** Config answers and
   push payloads only. Internal WebView navigation is unrestricted (an
   affiliate chain legitimately crosses hosts nobody can enumerate).
7. **The bar fills before the handover.** One loading session per launch,
   `LoadingView.complete { … }` gates every hand-over.
8. **Keyboard is solved by panning, never resizing.** `KeyboardPan` is the
   only hand on the wheel; IME insets are stripped from the WebView.
9. **In release, `Trace` compiles out.** Every log call goes through
   `Trace` and is guarded by `BuildConfig.DEBUG`.
10. **`gray.properties` is gitignored.** The seed and credentials never
    reach the repository. `keystore/keystore.properties` too.

## 6. Common questions

- **How do I test with a specific URL?** Set `gray.debugForceUrl` in
  `gray.properties`. Compiled out of release automatically.
- **How do I inspect the derived fingerprint?** `gradlew grayReport`.
  Do not commit or ship the output — it lists preference key names.
- **How do I rotate the fingerprint after a botched build?**
  `gradlew graySeed` → paste into `gray.properties` → `gradlew clean`.
  The rebrand does not need to run again for a seed rotation.
- **Can two apps share the seed?** No. This is the entire point of the
  file. `.cursor/rules/kotlin_fingerprint.mdc` §"do not share".
- **Can I hand-edit the encoded arrays or the codec constants?** No.
  `gray.properties` is the input; everything else is a derived output.

## 7. Directory layout

```
foollegends/
├── gray.properties            (gitignored — you create this)
├── gray.properties.example    (documented template)
├── tools/rebrand.py           (per-project package/class rename)
├── app/
│   ├── build.gradle.kts       (fingerprint engine — read the top half)
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/example/grayshell/
│       │   ├── startup/       AppEntry · WelcomePortal
│       │   ├── portal/        StreamPortal · AlertPortal · OfflinePortal · KeyboardPan
│       │   ├── reach/         ReachDispatch · TrackingDispatch
│       │   ├── signal/        PushRelay · PushBus
│       │   ├── vault/         DataVault · Secrets
│       │   ├── wire/          NetWire
│       │   ├── blueprint/     AppBlueprint · ChannelResult
│       │   ├── core/          Trace · UrlGuard · UserAgent
│       │   ├── LoadingView · Fullscreen · NativeContentActivity
│       └── res/               drawable* · mipmap* · values · xml
└── .cursor/
    ├── START_HERE.md              (this file — entry point)
    ├── DEV_PLAYBOOK.md            (stage-by-stage build process)
    ├── FINAL_CHECKLIST.md         (device QA before every upload)
    ├── rules/
    │   ├── AGENT.md                       (compressed file map)
    │   ├── kotlin_gray_guide.mdc          (architecture, config contract)
    │   ├── kotlin_launch_flow.mdc         (attribution contract)
    │   ├── kotlin_webview.mdc             (shell spec)
    │   ├── kotlin_keyboard.mdc            (pan, not resize)
    │   ├── kotlin_user_agent.mdc          (UA contract)
    │   ├── kotlin_gray_pitfalls.mdc       (34 real bugs + fixes)
    │   ├── kotlin_fingerprint.mdc         (how uniqueness is generated)
    │   ├── play_moderation_hardening.mdc  (what clusters a portfolio)
    │   └── custom_screens.md              (artwork contract)
    └── skills/gray-part-kotlin/SKILL.md
```
