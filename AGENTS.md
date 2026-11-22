# AudioShelf: Codex project instructions

This is a native Android/Jetpack Compose audiobook application. Preserve the
application ID, local library data, cached covers, saved listening progress,
provider behavior and Media3 playback.

## Motion skills to use

See [iArt Motion Skills](https://github.com/iart-ai/motion-skills).
- `motion-design-skills/animation-principles`: settle into position with
  eased motion, restrained secondary highlights and realistic object weight.
- `web-animation-skills/60fps-animation`: favor graphics-layer transforms
  and opacity over relayout; measure actual Android frame times on device.
- `web-animation-skills/accessible-animation`: honor the in-app Motion toggle
  and Android's animation settings; still render attractive static geometry.
- `motion-design-skills/motion-background`: keep Story Mode ambience low
  contrast behind captions, periodic, and paused when not visible.
- `webgl-animation-skills/threejs-animation`: art-direction reference for
  perspective, lighting, occlusion and GPU lifecycle. Do not embed Three.js
  or WebGL solely to render a two-cover native Compose shelf.

## Visual requirements

- The Walnut bookshelf must have tangible depth, alternating mild book
  perspective, shadows that pin sleeves to the wood, and foreground shelf lips.
- Use the actual book/album cover art. Preserve its aspect ratio and existing
  cover-matching logic; do not substitute unrelated illustrations.
- Reflections must also cover concept-specific edition prints, not only
  remotely downloaded art.
- Each object remains clickable and has a legible progress indicator.
- Reduce animation when the Motion toggle is off or Android animations
  are disabled. No continuous work should run in that mode.
- Avoid new permissions, servers, runtime CDNs or copyrighted stock media.

## Verify before merging

Run `gradle :app:testDebugUnitTest :app:assembleDebug` with JDK 17 and
Android SDK 35. Check a narrow and a wide Android phone, 0/1/2/9 books,
portrait and square jackets, album (record) art, missing art, dark/light
settings and animation-disabled mode. Review 3D perspective, text legibility,
touch areas, launch latency, dropped frames and GPU overdraw.
