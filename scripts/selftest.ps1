# Self-test for ISC Rule Kit: build, run the sample suite, and drive the test wizard and generator end to end.
# Exit code 0 when everything behaves as expected.
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root
$failed = 0
function Check([string]$what, [bool]$ok) {
    if ($ok) { Write-Host "  ok    $what" -ForegroundColor Green } else { Write-Host "  FAIL  $what" -ForegroundColor Red; $script:failed++ }
}

& powershell -File (Join-Path $PSScriptRoot 'build-offline.ps1') | Out-Null
Check 'build' ($LASTEXITCODE -eq 0)

$suite = & .\iscrule.cmd suite --no-color
Check 'sample suite: every good rule passes, every buggy rule is caught' ($LASTEXITCODE -eq 0)
Check 'sample suite: summary line' (($suite | Select-Object -Last 1) -match 'passed, 3 buggy samples caught')

$tmp = Join-Path ([IO.Path]::GetTempPath()) ("iscrule-selftest-" + [guid]::NewGuid().ToString('N').Substring(0, 8))
New-Item -ItemType Directory $tmp | Out-Null
try {
    Copy-Item samples\IdentityAttribute\LifecycleState.bsh (Join-Path $tmp 'Rule.bsh')
    $rule = Join-Path $tmp 'Rule.bsh'

    $mixed = Join-Path $tmp 'Mixed.bsh'
    "boolean a = true, b = false, c = true;`nif (a && b || c) log.debug(`"x`");`nif (a && (b || c)) log.debug(`"a && b || c`");`nreturn null;" | Set-Content $mixed -Encoding utf8
    $v = (& .\iscrule.cmd validate $mixed --type Transform --no-color) -join "`n"
    Check 'validate flags && / || without brackets (BUG003) only where it applies' ($v -match 'line 2\s+BUG003' -and $v -notmatch 'line 3\s+BUG003')

    $analysis = & .\iscrule.cmd analyze $rule --type IdentityAttribute --no-color
    Check 'analyze finds hrStatus values T, L, A' (($analysis -join "`n") -match 'hrStatus\s+"T", "L", "A"')

    # wizard: hrStatus = T (1st choice), defaults elsewhere, approve, default name, no more cases, don't run all
    $answers = @('1', '', '', '', '', '', '', '1', 'terminated', '', 'n') -join "`n"
    $answers | & .\iscrule.cmd add-test $rule --type IdentityAttribute --no-color | Out-Null
    $cases = Join-Path $tmp 'Rule.cases.json'
    $json = Get-Content $cases -Raw | ConvertFrom-Json
    Check 'add-test saves the approved case' ($json.cases.Count -eq 1 -and $json.cases[0].expect -eq 'inactive')

    & .\iscrule.cmd gen $rule --type IdentityAttribute --record --no-color | Out-Null
    & .\iscrule.cmd test $rule $cases --type IdentityAttribute --no-color | Out-Null
    Check 'gen --record appends cases that pass on the unchanged rule' ($LASTEXITCODE -eq 0)

    & .\iscrule.cmd new Transform Demo --dir $tmp --no-color | Out-Null
    & .\iscrule.cmd run (Join-Path $tmp 'Transform\Demo.java') --no-color | Out-Null
    Check 'new creates a runnable rule' ($LASTEXITCODE -eq 0)

    # import: rule XML -> rule file + cases + JUnit test; JUnit passes; export rebuilds the same XML
    $xml = Join-Path $tmp 'export.xml'
    & .\iscrule.cmd export samples\Correlation\EmployeeIdThenEmail.bsh --name 'HR Correlation' -o $xml | Out-Null
    $imported = Join-Path $tmp 'imported'
    & .\iscrule.cmd import $xml --dir $imported --no-color | Out-Null
    $ruleFile = Join-Path $imported 'Correlation\HrCorrelation.java'
    Check 'import writes rule, cases and JUnit test' ((Test-Path $ruleFile) -and (Test-Path (Join-Path $imported 'Correlation\HrCorrelation.cases.json')) -and (Test-Path (Join-Path $imported 'Correlation\HrCorrelationTest.java')))
    & .\iscrule.cmd junit $imported --no-color | Out-Null
    Check 'generated JUnit tests pass on the imported rule' ($LASTEXITCODE -eq 0)
    $back = Join-Path $tmp 'back.xml'
    & .\iscrule.cmd export $ruleFile -o $back | Out-Null
    $a = ([xml](Get-Content $xml -Raw)).Rule; $b = ([xml](Get-Content $back -Raw)).Rule
    Check 'export rebuilds the imported XML' ($a.name -eq $b.name -and $a.type -eq $b.type -and $a.Source.InnerText.Trim() -eq $b.Source.InnerText.Trim())
    (Get-Content $ruleFile -Raw) -replace '"employeeNumber"', '"empNo"' | Set-Content $ruleFile -Encoding utf8
    & .\iscrule.cmd junit $imported --no-color | Out-Null
    Check 'generated JUnit tests catch a changed rule' ($LASTEXITCODE -eq 1)

    # auto / sync: writes cases, is stable, notices a code change
    $autoRule = Join-Path $tmp 'Auto.bsh'
    Copy-Item samples\IdentityAttribute\LifecycleState.bsh $autoRule
    $a1 = (& .\iscrule.cmd auto $autoRule --type IdentityAttribute --time 5 --no-color) -join "`n"
    Check 'auto writes cases and reports coverage' ($a1 -match 'added\s+\d+ auto case' -and $a1 -match 'coverage\s+\d+ of \d+ blocks')
    $a2 = (& .\iscrule.cmd auto $autoRule --type IdentityAttribute --time 5 --no-color) -join "`n"
    Check 'auto on an unchanged rule adds nothing' ($a2 -match 'added\s+nothing' -and $a2 -match '\d+ unchanged')
    (Get-Content $autoRule -Raw).Replace('return "inactive";', 'return "terminated";') | Set-Content $autoRule -Encoding utf8
    $a3 = (& .\iscrule.cmd auto $autoRule --type IdentityAttribute --time 5 --accept --no-color) -join "`n"
    Check 'auto notices a code change and updates the auto cases (--accept)' ($a3 -match 'Behaviour changed' -and $a3 -match '\d+ updated')

    $r1 = (& .\iscrule.cmd report $autoRule --type IdentityAttribute --case 1 --no-color) -join "`n"
    Check 'report writes the page and shows one case (what the rule did + log)' ($r1 -match 'What the rule did' -and $r1 -match 'Log' -and (Test-Path (Join-Path $tmp 'Auto.report.html')))

    # SailPoint RDK (only when an RDK checkout and Maven are available): runs in a temporary copy of the RDK
    $rdk = @((Join-Path $root '..\rule-development-kit'), $env:ISC_RDK) | Where-Object { $_ -and (Test-Path (Join-Path $_ 'pom.xml')) } | Select-Object -First 1
    $hasMaven = (Get-Command mvn -ErrorAction SilentlyContinue) -or (Get-ChildItem (Join-Path $root '..\tools') -Directory -Filter 'apache-maven-*' -ErrorAction SilentlyContinue)
    if ($rdk -and $hasMaven) {
        $rdkCopy = Join-Path $tmp 'rdk'
        robocopy $rdk $rdkCopy /E /XD .git target /NFL /NDL /NJH /NJS /NP | Out-Null
        $out = (& .\iscrule.cmd rdk samples\Correlation\EmployeeIdThenEmail.bsh --rdk $rdkCopy --no-color) -join "`n"
        Check 'rdk: generated RDK tests pass on SailPoint classes (mvn test)' ($LASTEXITCODE -eq 0 -and $out -match 'RESULT\s+6 passed')
    } else {
        Write-Host '  skip  rdk (no rule-development-kit next to the kit, or no Maven)' -ForegroundColor Yellow
    }
}
finally {
    Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue
}

if ($failed -gt 0) { Write-Host "$failed check(s) failed" -ForegroundColor Red; exit 1 }
Write-Host 'All checks passed' -ForegroundColor Green
