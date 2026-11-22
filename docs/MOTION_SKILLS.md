# Motion Skills installation and AudioShelf adaptation

Source: https://github.com/iart-ai/motion-skills (MIT).
The upstream collection consists of **19 packs** containing **56 agent skills**.

## On a Windows PC

Install Node.js LTS and Git for Windows, open PowerShell in an AudioShelf clone,
then run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\install-motion-skills.ps1
```

The script installs every iArt pack globally for the **Codex** agent using
the official CLI, one repository at a time. It reports any failures and exits
nonzero if a pack failed. Restart Codex afterward.

For skills scoped only to the current project:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\install-motion-skills.ps1 -ProjectOnly
```

To inspect available skills after the installation, run
`npx skills list -g` or check Codex's global skills directory.

## What AudioShelf uses

The app is Kotlin/Jetpack Compose, **not** React/Three.js. Design principles
from the animation, accessibility, WebGL, performance and background packs are
adapted to native Compose without loading browser engines:

- Walnut: volumetric stage light, edge highlights and a dark wood shelf lip.
- Sleeves: mild opposing 3D rotation via `graphicsLayer` and contact shadow.
- Art: a slow, lightweight diagonal sheen over original and special-edition
  artwork, with no source-image distortion.
- Motion: stops when the user disables Motion or Android animations.

No skill packages are bundled into the APK. All skill installation occurs on
the user's own development machine; the script never uploads an API token.

## QA

Use the repository's Android GitHub Actions build to verify compilation and
unit tests. Inspect on-device rendering against the approved visual concepts
before claiming a pixel-perfect match.
