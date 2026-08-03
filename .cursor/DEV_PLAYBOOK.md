# DEV PLAYBOOK — building a new gray-part app from this template

> Stage-by-stage. Each stage has **inputs**, **what to do**, and a
> **self-verify** step you must pass before moving on. Skipping a self-verify
> is how a project reaches Stage 5 with a broken attribution pipeline and no
> way to tell when it broke.
>
> Read `START_HERE.md` first for the 30-second version. This file is what you
> work through with the repo open.

---

## Stage 0 — Inputs

Collect these before writing anything. Items marked ★ are blockers: **stop and
ask** rather than inventing a placeholder, because a placeholder that survives
to Stage 5 ships.

| Input | ★ | Notes |
|---|---|---|
| `applicationId` | ★ | Reverse-domain root must differ from every sibling, not just the leaf |
| Store-facing app name | ★ | Goes in `gray.appLabel`; must match the Play listing verbatim |
| Config endpoint URL | ★ | `https://…`; the backend must already answer |
| AppsFlyer dev key | ★ | Project-specific; do not reuse a sibling's |
| `google-services.json` | ★ | From a Firebase project used by no other app |
| Firebase project number | ★ | Sent in the config body as `firebase_project_id` |
| Partner host allowlist | ★ | Specific hosts for `gray.allowedHosts`; never a bare TLD |
| OneLink host | | `<app>.onelink.me`; omit if the campaign has none |
| The native game | ★ | A real, playable, reviewable app. Not a stub |
| Screen artwork | | 6 images — see `rules/custom_screens.md` |
| Notification glyph | | Monochrome, redrawn per project |
| Keystore | ★ | New `.jks`, new alias, new passwords |

**Self-verify:** every ★ row has a real value. If the native game does not
exist yet, the project is not ready to start — the gray flow is the smaller
half of the work and the game is what passes review.

---

## Stage 1 — Fingerprint

This is first, not last. Everything downstream reads from it.

```powershell
# 1. Fresh seed.
gradlew graySeed

# 2. Create the config from the documented template.
Copy-Item gray.properties.example gray.properties

# 3. Paste the seed and fill every Stage 0 value into gray.properties.
```

Then rotate the code shape:

```powershell
python tools\rebrand.py                 # plan only — read it
python tools\rebrand.py --apply
```

`rebrand.py` picks a theme from the seed. Pass `--theme` to force one. Themes
are word banks in the script; add more rather than reusing one across two
projects.

**Self-verify:**

```powershell
gradlew grayReport                      # diff every line against the previous project
rg -n 'com\.example\.grayshell' app     # must be empty
gradlew assembleDebug                   # must build after the rename
```

---

## Stage 2 — Identity and Firebase

- Drop `google-services.json` into `app/`.
- Confirm `gray.bundleId` matches the `package_name` inside that file exactly.
- Set `gray.oneLinkHost` + `gray.oneLinkVerify=true` if there is a OneLink;
  otherwise leave the defaults so the intent-filter matches nothing.
- Create `keystore/keystore.properties` from the `.example`, pointing at the
  new `.jks`.

**Self-verify:** `gradlew assembleRelease` produces a **signed** APK. If it
comes out unsigned, `keystore.properties` is missing or mispathed.

---

## Stage 3 — The native (white) part

Two ways round, and **the second one is the common case**. Read 3b before
assuming 3a.

### 3a. The game is written into this repo

Replace `NativeContentActivity` with the real game.

- Keep it a normal Activity (or the engine's host Activity).
- The game must **not** import any gray-flow package. The only coupling is
  `WelcomePortal.goNative()` launching it.
- Register it in the manifest with the same `configChanges` and theme.
- Add a route from the WebView shell back to the game — a "Play offline"
  entry. This removes the "unreachable subtree" signal
  (`rules/play_moderation_hardening.mdc` §3a).

**Self-verify:** launch the game directly (`adb shell am start -n
<pkg>/.<GameActivity>`), play it for a minute, rotate it. It has to stand on
its own as a product.

### 3b. The game already exists — port the gray part into *its* repo

Usually the game is built first, in its own repository, and the gray part is
brought to it. Do it in that direction: copy the gray flow into the game's
repo, not the game into this one. The game is the larger, more fragile half and
it already builds.

**Do this before running `rebrand.py`, in this order.** The renamer does a
textual pass over every `.kt` in `app/src/main/java`, including the game's, so
anything the game shares a name with gets rewritten out from under it.

1. **Rename the game's colliding classes.** `Fullscreen` and `LoadingView` are
   the usual pair — most games have both, and both are in the renamer's class
   map. Give the game's versions game-specific names first. Check the rest of
   the map (`CURRENT["classes"]` in `tools/rebrand.py`) against the game's file
   list; a collision that survives leaves `class X` renamed inside a file still
   called `X.kt`.

2. **Copy the gray part in.** Root `build.gradle.kts` (needs the
   `google-services` plugin), `gradle.properties`, `.gitignore`,
   `gray.properties.example`, `tools/rebrand.py`, `keystore/`, `.cursor/`, and
   on the app side `build.gradle.kts` (the whole fingerprint engine),
   `proguard-rules.pro`, `java/com/example/grayshell/**`, `res/xml/**`, the
   `gray_*` drawables and the notification glyph.

3. **Keep `namespace` equal to the game's package.** This is the one that bites
   silently: `R` and `BuildConfig` are generated into `namespace`, and the
   game's sources reference `R` with no import. Point the rebrand at the game's
   package (`--package com.acme.game`, or just let it read `gray.bundleId`) so
   the gray classes land in sub-packages beside the game and everything
   resolves. Giving the gray code a namespace of its own means editing an
   `import ….R` into every game file.

4. **Merge the manifest, do not overwrite it.** Take the gray `<application>`
   wholesale — `AppEntry`, the four permissions, the Firebase meta-data, the
   backup rules, the network config — put the router's `LAUNCHER` filter on
   `WelcomePortal`, and **strip the launcher filter off the game's entry
   activity**. Keep the game's activities with their own themes, named
   fully-qualified so the sub-package renaming cannot touch them.

5. **Route `goNative()` at the game.** Delete `NativeContentActivity` rather
   than keeping it as a shim, point `WelcomePortal.goNative()` at the game's
   entry Activity, and drop the stub from `proguard-rules.pro` — adding
   `-keep` lines for the game's Activities, which R8 otherwise cannot prove are
   live.

6. **Merge `res/values/`.** Theme names from both halves coexist fine; both get
   renamed by the rebrand together (pitfalls #34).

7. **Use the game's artwork for the gray screens.** `LoadingView`'s background,
   the no-wifi screen and the permission screen ship as placeholder gradients.
   A game repo already has a splash image — draw it in all three. The router's
   splash and the game's splash then being the same frame is the point: the app
   must not change its face mid-launch.

**Self-verify:**

```powershell
rg -n 'com\.example\.grayshell' app          # empty
rg -n 'android:name="\.' app/src/main/AndroidManifest.xml   # every package exists on disk
gradlew assembleDebug                        # game sources resolve R and BuildConfig
gradlew assembleRelease                      # R8 keeps the game's Activities
```

Then launch the game's entry Activity directly, as in 3a. Bumping
`gray.versionCode` above whatever the game already shipped is easy to forget —
`adb install` refuses the downgrade and that is usually how you find out.

---

## Stage 4 — Artwork and screens

Follow `rules/custom_screens.md` for exact sizes and naming. Six images plus a
launcher icon and a notification glyph.

**Self-verify:** run the app in both orientations and confirm the splash, the
no-wifi screen and the permission screen all render without letterboxing or
cropped text.

---

## Stage 5 — Wire and prove the pipeline

This is where projects break. Use the **debug** build — release strips
`Trace`.

```powershell
gradlew assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb shell pm clear <applicationId>
adb logcat -c
adb logcat -s WelcomePortal:V TrackingDispatch:V ReachDispatch:V StreamPortal:V AppsFlyer_6.16.2:V
```

The healthy sequence, in order (`rules/kotlin_launch_flow.mdc` §7):

1. `SDK started from WelcomePortal`
2. `onConversionDataSuccess` with a real `af_status`, within a few seconds
3. `request body composed (N fields)` where N is well above the 6 device fields
4. `HTTP 200` → STREAM, or `HTTP 404` → NATIVE

Reading the failures:

| Symptom | Cause | Fix |
|---|---|---|
| `conversion={}` a few ms after start | SDK started offline | Connectivity gate before `ignite` — pitfalls #19a |
| ~30 s gap, then `init_to_fg` in the AppsFlyer log | `init` moved out of the Application | pitfalls #19b |
| Same 30 s gap, `init` is in the Application | `start(applicationContext)` from an Activity | Pass the Activity — pitfalls #19c |
| `404` against a body that has `af_status=Organic` | Correct. Organic really is white | Nothing |
| `404` against an **empty** body | Launch-pipeline bug, not a backend one | Re-read §1–3 of the launch flow rule |

**Self-verify:** all four TZ first-launch scenarios behave — gray, white,
offline-then-online, and cold push. `FINAL_CHECKLIST.md` §2–§6.

---

## Stage 6 — Harden and ship

1. Work `rules/play_moderation_hardening.mdc` §5 — every grep must pass.
2. Work `FINAL_CHECKLIST.md` end to end on a real device, release build.
3. Fill in the sign-off table at the bottom of the checklist and keep it with
   the project. It is the record that proves app #8 shares no join with
   apps #1–#7.
4. Upload the mapping file alongside the AAB.
5. Complete the Data safety form honestly — Device IDs, shared with third
   parties.

---

## Operational rules that outlive any one project

- **One seed, one app.** Never reuse `gray.seed`, and never "adjust" a
  sibling's seed by a character.
- **One keystore, one Firebase project, one AppsFlyer key, one domain
  registrar per app.** These are the joins the fingerprint engine cannot
  reach; see `rules/play_moderation_hardening.mdc` §2a.
- **Never hand-edit derived values.** If you are typing a preference key, a
  JS sentinel, a timeout literal or an encoded array into Kotlin, stop — the
  build derives all of them and you are undoing the mechanism.
- **`gray.properties` and `keystore/` never reach git.**
- **Rely on logs, not assumptions, for the gray/white decision.** The whole
  reason the pipeline has four distinct failure modes with identical symptoms
  is that people guessed.
