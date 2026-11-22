<#
.SYNOPSIS
Install all 19 pinned iArt packs into AudioShelf's Codex project skills.
.DESCRIPTION
Requires Python 3 and Git. Run from PowerShell and reopen the AudioShelf
project in Codex after installation.
#>
[CmdletBinding()]
param([string]$Destination, [switch]$ProjectOnly)

$ErrorActionPreference = 'Stop'
$arguments = @((Join-Path $PSScriptRoot 'install-motion-skills.py'))
if ($Destination) { $arguments += @('--destination', $Destination) }

if (Get-Command py -ErrorAction SilentlyContinue) {
    & py -3 @arguments
} else {
    & python3 @arguments
}
exit $LASTEXITCODE
