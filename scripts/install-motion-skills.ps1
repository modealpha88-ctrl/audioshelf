<#
.SYNOPSIS
Install all 19 iArt Motion Skills packs for OpenAI Codex on Windows.
.DESCRIPTION
Requires Node.js (npx) and Git in PATH. Uses the official Skills CLI.
Installs into your Codex global skills directory unless -ProjectOnly is passed.
No administrator privileges or API keys are required.
.EXAMPLE
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\install-motion-skills.ps1
#>
[CmdletBinding()]
param([switch]$ProjectOnly)

$ErrorActionPreference = 'Stop'
$npx = Get-Command npx.cmd -ErrorAction SilentlyContinue
if (-not $npx) { throw 'Node.js with npx.cmd was not found. Install Node.js LTS and reopen PowerShell.' }
if (-not (Get-Command git.exe -ErrorAction SilentlyContinue)) {
    throw 'Git for Windows is required. Install Git, then reopen PowerShell.'
}

# Keep this in sync with https://github.com/iart-ai/motion-skills.
$packs = @(
    'tiktok-video-skills',
    'text-message-video-skills',
    'youtube-video-skills',
    'ecommerce-video-skills',
    'ad-video-skills',
    'data-animation-skills',
    'explainer-video-skills',
    'map-animation-skills',
    'web-animation-skills',
    'motion-design-skills',
    'kinetic-typography-skills',
    'freelance-motion-skills',
    'webgl-animation-skills',
    'manim-skills',
    'generative-illustration-skills',
    'javascript-animation-skills',
    'lower-thirds-skills',
    'b-roll-skills',
    'talking-head-skills'
)
if ($packs.Count -ne 19) { throw "Expected 19 packs, found $($packs.Count)." }
$failed = [System.Collections.Generic.List[string]]::new()
for ($i = 0; $i -lt $packs.Count; $i++) {
    $pack = $packs[$i]
    Write-Host ("[{0}/19] Installing iart-ai/{1} for Codex" -f ($i + 1), $pack)
    $arguments = @('--yes', 'skills', 'add', "iart-ai/$pack", '-a', 'codex', '-y')
    if (-not $ProjectOnly) { $arguments += '-g' }
    try {
        & $npx.Source @arguments
        if ($LASTEXITCODE -ne 0) { throw "Skills CLI returned code $LASTEXITCODE" }
    } catch {
        Write-Warning ("Installation failed for {0}: {1}" -f $pack, $_)
        $failed.Add($pack)
    }
}
if ($failed.Count -gt 0) {
    Write-Warning ("Installed {0} of 19 packs. Retry failed packs: {1}" -f (19 - $failed.Count), ($failed -join ', '))
    exit 1
}
Write-Host 'All 19 motion-skills packs successfully installed for Codex.'
Write-Host 'Restart Codex or open a new session to load them.'
