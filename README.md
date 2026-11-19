# ISC Rule Kit

A pure-Java, command-line **validator and tester for SailPoint Identity Security Cloud (ISC) rules**. No UI, no server:
paste or pipe a rule in, get validation findings and test results out. Rules run on the **real BeanShell 2.0b6
interpreter** (the engine ISC rules use), against mock ISC objects built from JSON test cases.

> **This is not SailPoint's official Rule Validator (`sp-rv`) or the Rule Development Kit.** The checks come from the
> public ISC rule guidelines and code restrictions. Always run the official validator and SailPoint's review process
> before deploying. See [Limits](#limits).

## Build

Needs JDK 17+.

```powershell
mvn -q package                                   # -> target/isc-rule-kit.jar   (see note below)
# or, without Maven (uses jars from ~/.m2 or ./lib):
powershell -File scripts\build-offline.ps1       # -> target/classes
```

Run with `iscrule.cmd` (Windows), or `java -jar target/isc-rule-kit.jar <command>`.

> The `javac` build was verified. The Maven `pom.xml` (compiler + shade plugin, fat jar) could not be run in the
> offline environment this was written in, so run `mvn package` once and tell me if it needs a tweak.

## Commands

```text
iscrule validate <rule> [--type T] [--format text|json] [--fail-on error|warn]
iscrule test     <rule> <cases.json> [--type T] [--timeout-ms N] [--format text|json]
                                     [--junit FILE] [--strict-mocks] [--verbose]
iscrule check    <rule> <cases.json> [same options]        validate, then test
iscrule run      <rule> [cases.json] [same options]        validate + test; with no cases file: smoke run (see below)
iscrule export   <rule> [--type T] [--name N] [--desc D] [-o FILE]   ISC-ready rule XML
iscrule scaffold <type>                                    starter cases.json for a rule type
iscrule types                                              rule types, their inputs and return contract
```

`<rule>` is one of:

* a full rule XML file (`<Rule name=".." type="..">` with a CDATA `<Source>`),
* raw BeanShell source (`.bsh`, `.txt`, anything not starting with `<`),
* an **RDK / Eclipse-style Java class** (`public class X { ... }`),
* `-` to read from **standard input**, so you can paste or pipe code: `Get-Content rule.txt -Raw | .\iscrule.cmd validate -`

The rule type is taken from the XML `type` attribute, else `--type`, else guessed from a header comment or the
variables the code uses (reported as `[auto-detected]`).

Exit codes: `0` ok, `1` validation errors / failed tests, `2` usage or I/O error. Use `--junit` and `--format json`
for CI.

### Try it

```powershell
.\iscrule.cmd validate examples\rules\MoveToDisabledOu.buggy.java      # finds 1 error + warnings
.\iscrule.cmd check    examples\rules\MoveToDisabledOu.java  examples\cases\MoveToDisabledOu.cases.json
.\iscrule.cmd check    examples\rules\UsernameGenerator.xml  examples\cases\UsernameGenerator.cases.json
.\iscrule.cmd test     examples\rules\EmployeeIdCorrelation.bsh examples\cases\EmployeeIdCorrelation.cases.json   # 1 deliberate FAIL
.\iscrule.cmd validate examples\rules\Broken.bsh --type IdentityAttribute
.\iscrule.cmd export   examples\rules\MoveToDisabledOu.java -o "Rule - BeforeProvisioning - MoveToDisabledOu.xml"
```

## Quickest path: paste and run

```powershell
Get-Clipboard -Raw | .\iscrule.cmd run -          # copy the rule, then run this
.\iscrule.cmd run MyRule.java --watch             # re-validates and re-runs every time you save the file
```

With **no cases file**, `run` (and `check` / `test`) does a **smoke run**: it detects the rule type, feeds the rule the
default inputs from `iscrule scaffold <type>`, and reports crashes, missing methods, null errors and unmocked `idn`
calls. It cannot say whether the *answer* is right because it has no expectations. For that, add a cases file (below).

`--watch` (`-w`) works with `validate`, `test`, `check` and `run`, needs a file path (not stdin), also re-runs when the
cases file changes, and keeps going if you save a half-finished edit. Stop it with Ctrl+C.

## Testing your own rule, step by step

1. **Validate** the pasted code (no setup needed). Copy the rule, then:
   ```powershell
   Get-Clipboard -Raw | .\iscrule.cmd validate -
   ```
2. **Create a test-cases file** once per rule. It supplies the inputs the rule expects (identity, plan, `idn` results, ...):
   ```powershell
   .\iscrule.cmd scaffold BeforeProvisioning > my-rule.cases.json
   ```
   Edit `my-rule.cases.json`: change the input values to match your data, and add an `"expect"` (or `"expectChanged"` for
   BeforeProvisioning) so the test can pass or fail. Without an expectation a case just runs and shows what happened.
3. **Run validate + tests** whenever you change the logic:
   ```powershell
   Get-Clipboard -Raw | .\iscrule.cmd check - my-rule.cases.json --verbose
   # or against a saved file
   .\iscrule.cmd check MyRule.java my-rule.cases.json --verbose
   ```
4. Read the result: `PASS` / `FAIL` / `ERROR`, the returned value, what the rule changed in its inputs, log lines, and
   `NOT MOCKED` notes telling you which `idn.method()` results to add under `"idn"` in the cases file.
5. When it is clean, `iscrule export MyRule.java -o "Rule - <Type> - <Name>.xml"` produces the ISC-ready XML, then run
   SailPoint's official validator on it.

## What `validate` checks

| Area | Codes | Examples |
|---|---|---|
| XML structure | `XML0xx`, `TYP00x`, `SIG00x` | not well-formed (with a CDATA hint), missing `name`/`type`/`Source`, unsupported or deprecated rule type, `&amp;` inside CDATA, BuildMap `<Signature>` |
| Syntax | `SYN0xx` | unterminated strings/comments, bracket mismatches, likely missing `;`, **BeanShell parse errors from the real parser** (e.g. generics, which BeanShell 2.0b6 cannot parse) |
| Code restrictions | `RES00x`, `GUI001` | blocked calls (`getObjectByName`, `search`, `sleep`, ...), `Thread`, `System.out`, `public static`, `Logger.getLogger`, BeanShell shell commands, `.toXml()` |
| Guidelines | `GUI0xx` | loops, null-unsafe `getAttribute(..).x()`, hard-coded credentials, logging secrets, non-standard log levels, commented-out code, TODOs, empty catch, unreachable code, unused imports |
| Methods | `UNDEF001`, `GUI016` | call to a method that is not defined (with a "did you mean"), method defined but never called |
| Rule contract | `TYP01x`, `VAR001` | missing `return`, Correlation maps missing `identityAttributeName/Value`, `BeforeProvisioning` returning a value, using an input the rule type does not receive |
| RDK style | `CLS001`, `CLS002` | class wrapper detected; ISC inputs re-declared as fields (shadowing the real objects) |

Line numbers always refer to the file you passed in (including XML files, where source starts after `<Source>`).

## Test-case files

```jsonc
{
  "cases": [
    {
      "name": "inactive identity, Disable request",
      "inputs": {                         // every key becomes a variable in the rule
        "plan": { "identity": { "name": "jdoe", "attributes": { "cloudLifecycleState": "inactive" } },
                  "accountRequests": [ { "applicationName": "AD", "nativeIdentity": "CN=jdoe", "operation": "Disable" } ] },
        "application": { "name": "AD", "attributes": { "environment": "Sandbox" } },
        "idn": {                          // mocks for idn.<method>(...)
          "accountExistsByNativeIdentity": true,
          "getRawAccountAttribute": { "$byLastArg": { "description": "Staff" }, "$default": "" }
        }
      },
      "expect": "value or {json}",                       // compared to the rule's return value (numbers by value, key order ignored)
      "expectError": false,                              // true, or a string the exception message must contain
      "expectChanged": { "plan": { "accountRequests": [ { "attributeRequests": [ ... ] } ] } },   // subset match on mutated inputs
      "expectLogContains": [ "begins" ]
    }
  ]
}
```

`iscrule scaffold <type>` prints a starter file for each rule type. A file may also be a bare array of cases, or a
single inputs object (one case with no assertions: it just runs and reports).

**Input variables.** Well-known names become mock ISC objects: `identity`, `application`, `plan`
(with `accountRequests` / `attributeRequests`), `account`, `link`, `field`, `schema`, `integration`. Anything else
(`cols`, `record`, `oldValue`, Transform variables such as `text`) becomes plain Java (`String`, `Integer`, `Boolean`,
`ArrayList`, `LinkedHashMap`). `identity.getAttribute(name)` reads `attributes` first.

**`idn` mock.** `idn` is a BeanShell scripted object, so *any* `idn.method(args)` call works. Methods you list under
`"idn"` return that JSON value; `$byLastArg` / `$default` choose by the last argument. Anything else returns `null` and is
reported as `NOT MOCKED` (`--strict-mocks` turns that into a failure).

**Reading results.** Each case prints `PASS` / `FAIL` / `ERROR` / `RAN` (ran, but no assertions), the returned value, any
*modified inputs* (this is how you see what a BeforeProvisioning rule did to the plan), log output, and mock notes.
Runtime errors report the file line number and a hint when the cause is recognisable.

## RDK-style class files

Rules written as `public class X { ... }` with ISC inputs re-declared as fields are handled automatically. The class
wrapper and the re-declared inputs (`Application application = new Application();`, `Logger log = ...`) are stripped
for validation, testing and `export`, with line numbers unchanged. Methods become script functions and statements in the
class body run as the script body. A class-style `execute()` method is **never** called by ISC, and the validator
says so.

## Extending the mocks

Mock ISC classes live in `src/main/java/sailpoint/**` (`Identity`, `Application`, `ProvisioningPlan` with
`AccountRequest` / `AttributeRequest`, `Link`, `ResourceObject`, `Field`, `Schema`, `Attributes`, `Util`,
`GeneralException`) plus a `org.apache.log4j.Logger` shim. If a rule calls a method the mock lacks, the test fails
with `Method X() not found in class sailpoint.object.Y` and a hint: add the method and rebuild. To use SailPoint's real
jars instead, remove the matching mock package and put the real jar on the classpath.

## Limits

* Not the official validator; its rule set is reconstructed from public documentation and may differ from `sp-rv` or
  SailPoint's reviewers. The exact blocked-call list is taken from the published code-restriction page.
* The `sailpoint.*` classes are small stand-ins, not SailPoint's. Behaviour that depends on the real platform
  (IdnRuleUtil results, real identity data model, plan semantics) is only as faithful as your `idn` mocks and fixtures.
* BeanShell 2.0b6 is used. If ISC runs a different BeanShell build, rare language differences are possible.
* A rule stuck in an infinite loop is reported as a timeout, but Java cannot kill the thread; it is abandoned and the
  process exits when the run finishes (the CLI calls `System.exit`).

## Layout

```text
src/main/java/com/iscrulekit/    CLI (Main), Validator, RuleParser, ClassUnwrapper, RuleRunner, Fixture, IdnMock, TestSuite, Report, ...
src/main/java/sailpoint/**       mock ISC classes
src/main/java/org/apache/log4j/  Logger / LogManager shim
examples/rules, examples/cases   sample rules and test cases
scripts/build-offline.ps1        build without Maven
```
"# My-ISC-Rule-Kit" 
