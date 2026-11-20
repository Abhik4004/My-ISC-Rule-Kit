# Installs an iscrule update into your kit folder (and the fixed pom.xml into your RDK folder), then rebuilds.
#
#   powershell -ExecutionPolicy Bypass -File install-update.ps1 -Kit "C:\path\to\My-ISC-Rule-Kit" -Rdk "C:\path\to\rule-development-kit"
#
# Run it from the folder you unzipped the update into. Your rules, imports, paste-here and test cases are not touched:
# only the kit's code (src), scripts, docs and README are replaced. The old files are kept in <Kit>\backup-<date>.
param(
    [Parameter(Mandatory = $true)][string]$Kit,
    [string]$Rdk
)
$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
if (-not (Test-Path (Join-Path $here 'kit\src'))) { $here = Split-Path -Parent $here }   # also works from inside kit\scripts
$update = Join-Path $here 'kit'
if (-not (Test-Path (Join-Path $update 'src'))) { throw "Run this from the unzipped update folder (it must contain kit\src)." }
if (-not (Test-Path $Kit)) { throw "Kit folder not found: $Kit" }

$backup = Join-Path $Kit ("backup-" + (Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory $backup | Out-Null
foreach ($item in 'src', 'scripts', 'docs', 'README.md') {
    $target = Join-Path $Kit $item
    if (Test-Path $target) { Copy-Item $target $backup -Recurse -Force; Remove-Item $target -Recurse -Force }
    Copy-Item (Join-Path $update $item) $target -Recurse -Force
}
foreach ($folder in 'imports', 'samples', 'paste-here') {   # add missing example files, never overwrite yours
    $from = Join-Path $update $folder
    if (-not (Test-Path $from)) { continue }
    Get-ChildItem $from -Recurse -File | ForEach-Object {
        $dest = Join-Path (Join-Path $Kit $folder) $_.FullName.Substring($from.Length + 1)
        if (-not (Test-Path $dest)) { New-Item -ItemType Directory -Force (Split-Path $dest) | Out-Null; Copy-Item $_.FullName $dest }
    }
}
Write-Host "Kit updated. Old files saved in $backup" -ForegroundColor Green

if ($Rdk) {
    if (-not (Test-Path (Join-Path $Rdk 'pom.xml'))) { throw "No pom.xml in RDK folder: $Rdk" }
    Copy-Item (Join-Path $Rdk 'pom.xml') (Join-Path $Rdk 'pom.xml.original') -Force
    Copy-Item (Join-Path $here 'rdk\pom.xml') (Join-Path $Rdk 'pom.xml') -Force
    Write-Host "RDK pom.xml updated (Mockito 5.20.0, works on Java 17-25). Original saved as pom.xml.original" -ForegroundColor Green
}

Push-Location $Kit
try {
    & powershell -File scripts\build-offline.ps1
} finally { Pop-Location }
