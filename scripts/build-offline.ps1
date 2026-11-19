# Builds ISC Rule Kit without Maven, using jars from the local Maven cache (~/.m2) or a ./lib folder.
# With Maven installed you can instead run:  mvn -q package   (produces target/isc-rule-kit.jar)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$m2 = Join-Path $env:USERPROFILE '.m2\repository'

function Find-Jar([string]$relative, [string]$glob) {
    $lib = Join-Path $root 'lib'
    if (Test-Path $lib) { $hit = Get-ChildItem $lib -Filter $glob -ErrorAction SilentlyContinue | Select-Object -First 1; if ($hit) { return $hit.FullName } }
    $dir = Join-Path $m2 $relative
    if (Test-Path $dir) {
        $hit = Get-ChildItem $dir -Recurse -Filter $glob | Where-Object { $_.Name -notmatch 'sources|javadoc' } | Sort-Object FullName -Descending | Select-Object -First 1
        if ($hit) { return $hit.FullName }
    }
    throw "Missing dependency $glob. Install Maven and run 'mvn package', or copy the jar into $lib"
}

$jars = @(
    (Find-Jar 'org\apache-extras\beanshell\bsh' 'bsh-*.jar'),
    (Find-Jar 'com\fasterxml\jackson\core\jackson-databind' 'jackson-databind-*.jar'),
    (Find-Jar 'com\fasterxml\jackson\core\jackson-core' 'jackson-core-*.jar'),
    (Find-Jar 'com\fasterxml\jackson\core\jackson-annotations' 'jackson-annotations-*.jar'),
    (Find-Jar 'commons-lang\commons-lang' 'commons-lang-*.jar')
)
$cp = $jars -join ';'

$out = Join-Path $root 'target\classes'
if (Test-Path $out) { Remove-Item $out -Recurse -Force }
New-Item -ItemType Directory -Force $out | Out-Null
$sources = Get-ChildItem (Join-Path $root 'src\main\java') -Recurse -Filter *.java | ForEach-Object { $_.FullName }

& javac --release 17 -encoding UTF-8 -Xlint:all -cp $cp -d $out $sources
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

Set-Content -Path (Join-Path $root 'target\classpath.txt') -Value $cp -Encoding ascii
Write-Host "Built $out  ($($sources.Count) source files). Run: .\iscrule.cmd help"
