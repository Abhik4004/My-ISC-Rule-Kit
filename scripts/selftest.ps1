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
}
finally {
    Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue
}

if ($failed -gt 0) { Write-Host "$failed check(s) failed" -ForegroundColor Red; exit 1 }
Write-Host 'All checks passed' -ForegroundColor Green
