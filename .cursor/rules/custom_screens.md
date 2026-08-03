---
description: Asset contract for the gray-part screens (loading, no-wifi, push permission) plus launcher and notification icons. Read when replacing the template placeholder artwork.
globs: app/src/main/res/**,app/src/main/java/**/LoadingView.kt,app/src/main/java/**/portal/*.kt
alwaysApply: false
---

# Custom screens — asset contract

The template ships **placeholders**: the three gray screens are code-drawn
gradients, and the launcher icon is a generic mark. All of them must be
replaced before submission. Shipping the placeholders is both an obvious
review problem and a fingerprint join — two apps with the same gradient
`<shape>` XML are the same app to a static scan.

`tools/rebrand.py` renames the `gray_*` prefix per project. Do the artwork
replacement **after** the rebrand so you name the files correctly the first
time.

---

## 1. The three screens

Each screen needs **two** images — portrait and landscape. Landscape is not
optional: `android:screenOrientation="sensor"` is set on all three activities
and the TZ requires both orientations to look deliberate.

| Screen | Drawn by | Portrait | Landscape |
|---|---|---|---|
| Loading / splash | `LoadingView.drawBackground()` | `<prefix>_loading_portrait` | `<prefix>_loading_landscape` |
| No internet | `OfflinePortal` | `<prefix>_nowifi_portrait` | `<prefix>_nowifi_landscape` |
| Push permission | `AlertPortal` | `<prefix>_notif_portrait` | `<prefix>_notif_landscape` |

`<prefix>` is whatever `rebrand.py` chose (`reef_`, `cn_`, `orb_`, …). The
template default is `gray_`.

### Format and size

- **WebP, lossy, quality ~80.** Roughly 10× smaller than PNG at the same
  perceived quality across a full-screen image, and AAPT never chokes on it
  (pitfalls #15 — PNGs with ICC profiles fail to compile).
- **Portrait: 1080 × 1920.** **Landscape: 1920 × 1080.** One density bucket
  (`res/drawable/`) is enough; Android downscales cleanly and per-density
  copies of full-screen art bloat the APK for no gain.
- File extension does not matter to the code — `R.drawable.x` resolves either
  way — so converting PNG → WebP needs no code change.

### Composition rules

- **Portrait art is drawn `CENTER_CROP`.** Keep anything that must be seen
  (title, logo) inside the middle 80% vertically; the top and bottom get cut
  on tall devices.
- **Landscape art is drawn `CENTER_CROP` too**, so keep the subject
  horizontally centered — the sides get cut on very wide devices.
- **Leave the bottom ~22% clear** on all six images. That band is where the
  loading bar, the RETRY button, and the ACCEPT/SKIP row are drawn on top.
- **Do not bake text into the art** that duplicates what the code draws
  (`Loading…`, `RETRY`, `ACCEPT`, `SKIP`). The code draws these so they can be
  localised and so the tap targets line up.

---

## 2. Wiring the loading screen to real artwork

`LoadingView` currently draws a gradient plus the words `SPLASH PLACEHOLDER`.
Replace `drawBackground` with an orientation-aware bitmap draw:

```kotlin
private fun drawBackground(canvas: Canvas, w: Float, h: Float) {
    val landscape = w > h
    val res = if (landscape) R.drawable.<prefix>_loading_landscape
              else          R.drawable.<prefix>_loading_portrait
    val bmp = cached(res) ?: return
    // CENTER_CROP: scale to cover, then center.
    val scale = maxOf(w / bmp.width, h / bmp.height)
    val dw = bmp.width * scale
    val dh = bmp.height * scale
    canvas.drawBitmap(
        bmp, null,
        RectF((w - dw) / 2f, (h - dh) / 2f, (w + dw) / 2f, (h + dh) / 2f),
        null
    )
}
```

Decode once and hold the bitmap — decoding inside `onDraw` on every animation
frame will stutter the bar. The bar, the shimmer and the caption are drawn on
top by `drawLoadingBar`, unchanged.

The other two screens already load their backgrounds via `ImageView` with
`ScaleType.CENTER_CROP` and pick the resource from
`resources.configuration.orientation` — swapping the drawable files is all
that is needed there.

---

## 3. Buttons

`<prefix>_btn_accept` and `<prefix>_btn_skip` are shape drawables, not images.
Keep them as XML but restyle per project — the accent colour, corner radius
and stroke are part of the fingerprint.

Contrast matters more than it looks: a SKIP button rendered as plain white
text over bright artwork is invisible on half the devices (pitfalls #17). Give
the secondary button a dark translucent fill, a 2dp accent stroke, and a
shadow layer on the label.

---

## 4. Launcher icon

- Source: one 1024 × 1024 PNG with no transparency at the edges.
- Generate `mipmap-{m,h,xh,xxh,xxxh}dpi/ic_launcher.png` and
  `ic_launcher_round.png`, plus the adaptive pair in `mipmap-anydpi-v26/`.
- **Adaptive safe zone:** the launcher masks to a 66dp circle inside the 108dp
  canvas. Anything outside that circle is cropped on most launchers — keep the
  mark well inside it.
- The icon must look like the **native game**, not the partner brand. It is
  the first thing a reviewer compares against the listing.

---

## 5. Notification icon

`ic_notif_flame` is the template's glyph — **redraw it**. A shared monochrome
shape across submissions is a visible cluster join.

Requirements Android enforces:

- **Monochrome, alpha-only.** Android fills it with the system tint; any
  colour in the asset is discarded and a full-colour icon renders as a white
  blob.
- Vector drawable (`res/drawable/ic_notif_<name>.xml`), 24 × 24dp viewport.
- Referenced in two places, both of which `rebrand.py` leaves alone — update
  them by hand:
  - `AndroidManifest.xml` → `com.google.firebase.messaging.default_notification_icon`
  - `PushRelay.showNotification()` → `.setSmallIcon(...)`

---

## 6. Replacement checklist

- [ ] Six WebP screen images at the right sizes, named with this project's prefix
- [ ] `LoadingView.drawBackground` draws the bitmap, placeholder text gone
- [ ] Bottom ~22% of every image is clear of important content
- [ ] Both orientations verified on a real device, including a notch model
- [ ] Button drawables restyled; SKIP is legible over the artwork
- [ ] Launcher icon regenerated for all densities + adaptive, mark inside the safe zone
- [ ] Notification glyph redrawn, alpha-only, referenced from manifest and `PushRelay`
- [ ] No `<prefix>_*` file is byte-identical to a sibling project's
