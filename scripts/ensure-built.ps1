# Called by iscrule.cmd before every command: builds the kit when it was never built,
# or when the code changed since the last build (for example after a git pull).
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$target = Join-Path $root 'target'
$stamp = Join-Path $target 'build.stamp'

$newest = Get-ChildItem (Join-Path $root 'src\main\java') -Recurse -Filter *.java |
    Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1 -ExpandProperty LastWriteTimeUtc
$pom = (Get-Item (Join-Path $root 'pom.xml')).LastWriteTimeUtc
if ($pom -gt $newest) { $newest = $pom }
$built = (Test-Path (Join-Path $target 'isc-rule-kit.jar')) -or (Test-Path (Join-Path $target 'classpath.txt'))
if ($built -and (Test-Path $stamp) -and (Get-Item $stamp).LastWriteTimeUtc -ge $newest) { exit 0 }

if (-not (Get-Command javac -ErrorAction SilentlyContinue)) {
    [Console]::Error.WriteLine('Java (JDK 17 or newer) is not installed or not on PATH. Install a JDK, then run the command again.')
    exit 2
}
[Console]::Error.WriteLine('Building ISC Rule Kit (first run, or the code changed)...')
Remove-Item (Join-Path $target 'isc-rule-kit.jar') -ErrorAction SilentlyContinue
Remove-Item (Join-Path $target 'classpath.txt') -ErrorAction SilentlyContinue

# 1) quick build from jars already in ~/.m2 or ./lib
$ok = $false
# (through cmd, so compiler warnings on stderr are not mistaken for a failed build)
$offline = Join-Path $PSScriptRoot 'build-offline.ps1'
& cmd /c "powershell -NoProfile -ExecutionPolicy Bypass -File `"$offline`" >nul 2>&1"
$ok = ($LASTEXITCODE -eq 0)

# 2) otherwise Maven downloads the libraries and builds target\isc-rule-kit.jar
if (-not $ok) {
    $mvn = (Get-Command mvn -ErrorAction SilentlyContinue).Source
    if (-not $mvn -and $env:MAVEN_HOME -and (Test-Path (Join-Path $env:MAVEN_HOME 'bin\mvn.cmd'))) { $mvn = Join-Path $env:MAVEN_HOME 'bin\mvn.cmd' }
    if (-not $mvn) {
        foreach ($base in @($root, (Split-Path -Parent $root))) {
            $hit = Get-ChildItem (Join-Path $base 'tools') -Directory -Filter 'apache-maven-*' -ErrorAction SilentlyContinue |
                Where-Object { Test-Path (Join-Path $_.FullName 'bin\mvn.cmd') } | Sort-Object Name | Select-Object -Last 1
            if ($hit) { $mvn = Join-Path $hit.FullName 'bin\mvn.cmd'; break }
        }
    }
    if (-not $mvn) {
        # download Maven once into <kit>\tools (checked against its published SHA-512)
        $v = '3.9.9'
        $url = "https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/$v/apache-maven-$v-bin.zip"
        $tools = Join-Path $root 'tools'
        [Console]::Error.WriteLine("Maven not found: downloading Maven $v into $tools (one time, about 9 MB)...")
        try {
            New-Item -ItemType Directory -Force $tools | Out-Null
            $zip = Join-Path $tools "apache-maven-$v-bin.zip"
            [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
            Invoke-WebRequest $url -OutFile $zip -UseBasicParsing
            $c = (Invoke-WebRequest "$url.sha512" -UseBasicParsing).Content
            if ($c -is [byte[]]) { $c = [Text.Encoding]::ASCII.GetString($c) }
            $want = ($c.Trim() -split '\s+')[0]
            if ((Get-FileHash $zip -Algorithm SHA512).Hash -ne $want.ToUpper()) { throw 'checksum mismatch' }
            Expand-Archive $zip -DestinationPath $tools -Force
            Remove-Item $zip
            $mvn = Join-Path $tools "apache-maven-$v\bin\mvn.cmd"
        } catch {
            [Console]::Error.WriteLine("Build failed: the libraries are not in ~/.m2 and Maven could not be downloaded ($($_.Exception.Message)).")
            [Console]::Error.WriteLine('Install Maven (or unzip it into a "tools" folder in the kit), then run the command again.')
            exit 2
        }
    }
    Push-Location $root
    try { & $mvn -q -DskipTests package | Out-Host; $ok = ($LASTEXITCODE -eq 0) } finally { Pop-Location }
    if (-not $ok) { [Console]::Error.WriteLine('Build failed (see the Maven output above).'); exit 2 }
}

Set-Content -Path $stamp -Value (Get-Date -Format o) -Encoding ascii
[Console]::Error.WriteLine('Build done.')
exit 0
