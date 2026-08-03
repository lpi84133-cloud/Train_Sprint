# FINAL CHECKLIST — before every upload to Play

> Run this on a **real device**, on a **release build**, with **fresh install
> state**. An emulator does not reproduce the keyboard, the notch, Play
> Integrity App Check, or the AppsFlyer install referrer.
>
> Nothing here duplicates the automated greps — those live in
> `rules/play_moderation_hardening.mdc` §5 and must pass first.

## 0. Preconditions

```powershell
# Fingerprint checks must be clean before you touch a device.
rg -n '259_?200|Chrome/1[0-9][0-9]\.|__flsa\b|__kb\b|KbPan|fl_state|app_push_channel' app/src/main/java
rg -n 'com\.example\.grayshell' app/src/main/java
rg -n 'CHANGE-ME-EVERY-PROJECT' gray.properties

# Build and install. NOT installRelease — Samsung blocks /data/local/tmp on a
# locked screen (pitfalls #16).
gradlew assembleRelease
adb install -r app\build\outputs\apk\release\app-release.apk
```

Reset state between scenarios: `adb shell pm clear <applicationId>`

Logcat with every tag that matters (pitfalls #28) — release builds strip
`Trace`, so run the **debug** build for anything needing log evidence:

```powershell
adb logcat -c
adb logcat -s WelcomePortal:V TrackingDispatch:V ReachDispatch:V StreamPortal:V AppEntry:V AppsFlyer_6.16.2:V
```

---

## 1. Fingerprint safety

- [ ] `gradlew grayReport` output differs on **every line** from the previous
      project in the portfolio. Keep the reports side by side and diff them.
- [ ] `tools/rebrand.py` was run: package, sub-package folders, class names
      and drawable prefix are all project-specific.
- [ ] `gray.properties` is **not** in git (`git status` shows nothing).
- [ ] Keystore is new for this app; alias and passwords are not reused.
- [ ] `google-services.json` belongs to a Firebase project used by **no other
      app** in the portfolio.
- [ ] AppsFlyer dev key is not shared with a sibling submission.
- [ ] Config endpoint domain: different registrar / registration date /
      nameservers from every sibling.
- [ ] At least two dependency versions differ from the previous project
      (OkHttp, AppsFlyer, Firebase BOM, security-crypto, coroutines).
- [ ] Notification icon glyph is redrawn, not the template's.

---

## 2. First launch — gray path

- [ ] Fresh install via the OneLink (non-organic). Splash appears, bar
      animates, dots cycle and never freeze.
- [ ] Log shows `SDK started from WelcomePortal`, then a conversion callback
      with a real `af_status` within a few seconds.
- [ ] Config POST body carries the attribution fields (not an empty body).
- [ ] Response `ok:true` + url → WebView opens.
- [ ] The bar reaches 100% **before** the handover; no jump at 60%.
- [ ] Push-permission screen appears once, before the WebView.

## 3. First launch — white path

- [ ] Fresh install without a link (organic). Backend answers `404` /
      `ok:false` against a body that really contains `af_status=Organic`.
- [ ] The native game opens.
- [ ] Relaunch: goes straight to the game with **no** config POST.

## 4. First launch — offline

- [ ] Fresh install, airplane mode on. The **no-wifi screen is the first
      frame** — no splash ahead of it, no bar filling.
- [ ] Nothing was persisted: turn the radio on, tap Retry → the full gray
      pipeline runs and a link install reaches the **WebView**, never the game.
- [ ] Auto-retry fires on its own when connectivity returns.

## 5. Returning user

- [ ] Returning STREAM, online: config POST runs, new URL loads.
- [ ] Returning STREAM, endpoint down: falls back to the saved URL.
- [ ] Returning STREAM, endpoint down + no saved URL: no-wifi screen.
- [ ] Returning STREAM, offline: no-wifi screen carrying the saved URL;
      Retry returns to that page, not a blank WebView (pitfalls #9).
- [ ] Returning NATIVE: game opens immediately, no network needed.

## 6. Push

- [ ] **Cold** (app killed): tap → splash runs, bar fills, pushed URL opens.
- [ ] **Warm** (app backgrounded, shell alive): tap → **no splash at all**,
      the URL loads in the existing WebView.
- [ ] **NATIVE user**: push arrives, notification shows, tap opens the
      **game**. The WebView must not appear. This is the store-review
      invariant — verify it explicitly.
- [ ] Push URL outside `gray.allowedHosts` → notification shows, tap does
      **not** open a WebView.
- [ ] Push with an image renders BigPicture; a slow image URL does not stall
      or ANR the service.
- [ ] Permission screen: Accept → OS dialog. Skip → re-asked only after the
      snooze window (`gradlew grayReport` prints the value). OS "deny
      forever" → never asked again.

## 7. WebView shell

- [ ] Safe area correct in **portrait and landscape**, with a notch device.
      Site buttons are not squashed (pitfalls #10).
- [ ] Rotate repeatedly, and lock/unlock the screen — layout stays correct.
- [ ] Keyboard: tap a field in portrait and in landscape. Content pans, does
      not jump-and-drop back.
- [ ] Keyboard inside a login **iframe** works.
- [ ] Long redirect chain resolves; no black screen between hops; no native
      error page visible.
- [ ] Payment / wallet link (`intent://`, `tel:`, custom scheme) hands off to
      the system app and the page behind it stays put.
- [ ] `target="_blank"` opens in place, not in an external Chrome tab.
- [ ] File upload: chooser opens with **no** storage permission dialog.
- [ ] Back button walks WebView history and does not close the app.
- [ ] Turn the radio off with a page already loaded → no-wifi screen within
      the heartbeat interval.

## 8. Native (white) part

- [ ] `NativeContentActivity` is **replaced with the real game**. The stub
      with "Replace ... with your real game" must not ship.
- [ ] The game is genuinely playable and reviewable on its own.
- [ ] The game does not import any gray-flow package.
- [ ] There is a path from the WebView shell back to the game.

## 9. Build & store hygiene

- [ ] Release is signed with this project's keystore; mapping file uploaded.
- [ ] `gray.debugForceUrl` empty.
- [ ] `gray.allowedHosts` non-empty and lists only specific partner hosts.
- [ ] Manifest declares exactly: `INTERNET`, `ACCESS_NETWORK_STATE`,
      `POST_NOTIFICATIONS`, `VIBRATE` — nothing else.
- [ ] Data safety form declares Device IDs (AppsFlyer, FCM) shared with third
      parties.
- [ ] Listing title / description / screenshots describe the **native game**
      and match `gray.appLabel`.
- [ ] Privacy policy URL is live, on this app's own domain, and not shared
      with a sibling.
- [ ] Release logcat is silent: no config body, no attribution dump, no
      `Sending first launch`.
- [ ] Launcher icon renders correctly on API 26+ adaptive and on legacy;
      nothing important inside the 66dp safe zone.
- [ ] APK/AAB installs and runs on the **minSdk** device (API 24) as well as
      the newest one available.

---

## 10. Sign-off

| Item | Value |
|---|---|
| App / applicationId | |
| `gray.seed` first 6 chars | |
| rebrand theme | |
| Firebase project | |
| AppsFlyer key (last 4) | |
| Config endpoint host | |
| Keystore file | |
| Tested on (device / API) | |
| Date | |

Keep this table with the project. When app #8 goes out, it is the record that
proves none of its joins match apps #1–#7.
