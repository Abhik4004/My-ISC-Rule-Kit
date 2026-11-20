# ISC Rule Kit: reference

The full details behind the [README](../README.md): every command and option, what each check means, the test-case
file format, the sample library and how the mocks work.

> Not SailPoint's official Rule Validator (`sp-rv`) or Rule Development Kit. The checks come from the public ISC rule
> guidelines and code restrictions. Always run the official validator and SailPoint's review before deploying.

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
iscrule suite    [folder] [--verbose] [--junit FILE]       validate + test every rule in a folder (default: samples)
iscrule analyze  [rule]                                    what the rule reads and the values it checks
iscrule add-test [rule] [cases]                            interactive: build a test case, run it, approve, save
iscrule gen      [rule] [cases] [--record] [--max N]       generate test variations
iscrule new      <type> <Name> [--dir rules]               new rule from a template
iscrule import   <rule.xml | rules.json> [--dir rules] [--force]   ISC export -> rule file + cases + JUnit test
iscrule junit-gen [rule] [cases]                           (re)write <Name>Test.java from the cases file
iscrule junit    [folder|Test.java]                        compile and run JUnit test classes
iscrule paste    [options]                                 run paste-here/Rule.java (+ Rule.cases.json)
iscrule rdk      [rule | rule.xml] [cases] [--name N] [--rdk folder] [--no-run] [--force]
                                                           SailPoint RDK: deployable XML + JUnit 5/Mockito test, mvn test
```

Working on the kit itself? `powershell -File scripts\selftest.ps1` builds it, runs the sample suite, and drives
the wizard, generator and templates end to end.

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

## What `validate` checks

| Area | Codes | Examples |
|---|---|---|
| XML structure | `XML0xx`, `TYP00x`, `SIG00x` | not well-formed (with a CDATA hint), missing `name`/`type`/`Source`, unsupported or deprecated rule type, `&amp;` inside CDATA, BuildMap `<Signature>` |
| Syntax | `SYN0xx` | unterminated strings/comments, bracket mismatches, likely missing `;`, **BeanShell parse errors from the real parser** (e.g. generics, which BeanShell 2.0b6 cannot parse) |
| Code restrictions | `RES00x`, `GUI001` | blocked calls (`getObjectByName`, `search`, `sleep`, ...), `Thread`, `System.out`, `public static`, `Logger.getLogger`, BeanShell shell commands, `.toXml()` |
| Guidelines | `GUI0xx` | loops, null-unsafe `getAttribute(..).x()`, hard-coded credentials, logging secrets, non-standard log levels, commented-out code, TODOs, empty catch, unreachable code, unused imports |
| Common bugs | `BUG001`, `BUG002`, `BUG003` | strings compared with `==` / `!=` (checks identity, not text); `String.valueOf(x.getAttribute(..))`, which throws in BeanShell when the value is null; `&&` and `||` mixed without brackets (`a && b \|\| c` means `(a && b) \|\| c`) |
| Rule API | `API001` | `Attributes.setMap(...)`: not in SailPoint's published rule API (the RDK's class stubs); use `putAll` |
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

**Reading results.** The output has three parts:

```text
VALIDATION  0 errors, 2 warnings, 9 notes      each problem once, with every line it occurs on (notes need --verbose)
TESTS  14 cases                                 one line per case: PASS / FAIL / ERROR / RAN (ran, no checks)
  FAIL   14 Remove Birthright + another group ...
         Why it failed                          the exact field that differs, expected vs got
         What the rule changed                  before -> after; + added, - removed (passwords masked)
         Log                                    the rule's log lines
RESULT  13 passed, 1 failed  (14 cases)
```

Passing cases are a single line; `--verbose` expands them too (changes, logs, every `idn` mock call, notes) and lists
every value instead of summarising, e.g. `+ arguments (13 values set)`. Runtime errors show the line number and a hint.
Colours turn on automatically in Windows Terminal and VS Code; force them with `--color` or turn them off with
`--no-color` (or the `NO_COLOR` environment variable).

## The test wizard (`add-test`), `gen`, `analyze`, `new`

```powershell
# 1. paste your rule into paste-here\Rule.java and save
.\iscrule.cmd add-test      # 2. asks what you want to test, runs the rule, shows the result, asks "is this right?"
.\iscrule.cmd paste         # 3. runs every saved test case (add --watch to re-run on every save)
```

`add-test` reads your rule first, so its questions use **the values your rule actually checks**. For a
BeforeProvisioning rule that does `"inactive".equalsIgnoreCase(cloudLifecycleState)` and
`memberOfValue.equalsIgnoreCase(BIRTHRIGHT_GROUP)`, you get:

```text
Operation
   1) Disable   2) Enable   3) Modify ...              number [Modify]:
  Attribute name   1) memberOf                         [memberOf]:
  Operation        1) Add  2) Remove  3) Set           [Add]:
  Value            1) CN=DualAuthUsersMedCtr,...,DC=ql1,...   2) CN=DualAuthUsersMedCtr,...,DC=ucsfmedicalcenter,...
identity attribute cloudLifecycleState   1) inactive  2) active            [active]:
idn.getRawAccountAttribute("brb")        1) true  2) false  3) (empty)     [false]:
...
RESULT OF THE RUN
  plan.accountRequests[0].operation: "Modify"  ->  "Enable"
  + plan.accountRequests[0].attributeRequests: AC_NewParent Set "OU=MedCenterUsers,..."
Is this what the rule should do?
   1) yes - save it as the expected result
   2) no - I will say what should happen       (saves your expectation, so the case fails until the rule is fixed)
   3) save the inputs only, no check
```

Press Enter to take a default, type a number to pick a suggestion, or type any value. For several values in one
answer (a list of groups), separate them with `;`. The case lands in `Rule.cases.json` next to the rule, and
anything you have written there by hand is kept as it is.

| Command | What it does |
|---|---|
| `iscrule analyze [rule]` | Lists what the rule reads and the values it compares them with, without running it. |
| `iscrule add-test [rule] [cases]` | The interactive wizard above. |
| `iscrule gen [rule] [cases]` | Writes a baseline case plus one case per value the rule checks (`brb=true`, `operation Disable`, ...). Add `--record` to save each run's result as its expected result. Review those: a recorded bug is still a bug. `--max N` limits the number of cases (default 40). |
| `iscrule new <type> <Name> [--dir rules]` | Starts a rule from a template in `rules/<type>/<Name>.java`, like the RDK's starter project but with nothing to set up. Then `add-test` it, `suite rules` to run everything, `export` to get the XML. |

With no `[rule]`, these use `paste-here/Rule.java`. Rules can be in any folder, and `<type>` comes from `iscrule types`.

`gen` is a good first pass on someone else's rule. On the UCSFMC Before Provisioning rule it found a crash in one
of 18 generated cases: adding the *Production* Birthright DN while the source is set to Sandbox makes the rule treat
it as an ordinary group, and the run reaches the undefined `getSourceAttributeBySourceName`.

## Starting from an ISC export (XML or JSON): `iscrule import`

```powershell
.\iscrule.cmd import my-rules.xml      # or a .json export
.\iscrule.cmd junit rules               # run the generated JUnit tests
```

`import` accepts a rule XML file with one `<Rule>` or several (e.g. a `<sailpoint>` export), or the JSON the ISC API
and VS Code extension return (`name`, `type`, `sourceCode.script`; one rule or an array). For each rule it writes,
RDK style, one folder per rule type:

```text
rules/BeforeProvisioning/UcsfmcAdBeforeProvisioning.java        the code, with a header holding name/type/description
rules/BeforeProvisioning/UcsfmcAdBeforeProvisioning.cases.json  generated cases with today's results recorded
rules/BeforeProvisioning/UcsfmcAdBeforeProvisioningTest.java    a JUnit 4 test class: one @Test per case
```

The generated test reads like a hand-written RDK test:

```java
public class UcsfmcAdBeforeProvisioningTest {
    private static final RuleTester RULE = RuleTester.load("rules/BeforeProvisioning/UcsfmcAdBeforeProvisioning.java");

    /** cloudLifecycleState=inactive */
    @Test
    public void test08_cloudLifecycleStateInactive() {
        RULE.run("""
            { "plan": { ... }, "application": { ... }, "idn": { ... } }
            """)
            .assertNoError()
            .assertChanged("""
            { "plan": { "accountRequests": [ { "operation": "Modify", "attributeRequests": [ ... ] } ] } }
            """);
    }
}
```

`RuleTester` methods: `run(inputsJson)`, then `assertNoError()`, `assertError(text)`, `assertReturns(json)`,
`assertChanged(json)`, `assertLogContains(text)`; also `returned()`, `plan()`, `logs()` for your own assertions.
Write your own test classes the same way. Any file named `*Test.java` is picked up.

* `iscrule junit [folder|File.java]` compiles and runs the test classes. It finds JUnit 4 in `./lib` or your
  local Maven repository, so you need no build file or IDE setup. In an IDE, put the kit (`target/classes` and its
  jars) and JUnit 4 on the test classpath, and use the kit folder as the working directory.
* `iscrule junit-gen [rule]` rewrites `<Name>Test.java` from the cases file, e.g. after `add-test`.
* `iscrule export rules/.../X.java -o X.xml` rebuilds the XML with the original name, type and description, and
  the same source.
* `import` skips rules whose file already exists. `--force` overwrites them, and `--dir` picks another folder.

The recorded cases pin down what each rule does **today, bugs included**, so a later change that alters behaviour
fails a test. Import reports the cases where the rule throws. Review those, and use `add-test` to state the
behaviour you actually want.

## Sample library and `iscrule suite`

[`samples/`](../samples) holds realistic rules for every rule type, each with its own cases file. Run them all with one
command:

```powershell
.\iscrule.cmd suite
```

```text
SUITE  samples  (14 rules)
  PASS    AttributeGenerator\EmailAddress.bsh           6/6 cases     0 errors, 1 warning
  CAUGHT  BeforeProvisioning\AdJoinerCreate.buggy.bsh   1/6 cases     0 errors, 2 warnings
          caught: "finance joiner ..." threw NullPointerException: String.valueOf(null) ...
  ...
RESULT  11 passed, 3 buggy samples caught  (14 rules, 83 cases)
```

| Folder | Rule | What it shows |
|---|---|---|
| AttributeGenerator | `UniqueSamAccountName.java` | RDK class style, uniqueness loop with `idn.isUniqueLDAPValue`, 20-char limit, throws when all taken |
| AttributeGenerator | `EmailAddress.bsh` | preferred name, source setting, `idn.countIdentitiesBySearchableIdentityAttribute` |
| BeforeProvisioning | `AdLeaverRehire.java` | Disable: OU move, clear manager, hide from GAL, remove groups. Enable: OU by employee type |
| BeforeProvisioning | `AdJoinerCreate.bsh` | Create: OU by department, description, default groups |
| BuildMap | `HrCsvBuildMap.bsh` | trimming, short rows, date reformat, multi-value column |
| Correlation | `EmployeeIdThenEmail.bsh` | leading zeros, email fallback, skipping service accounts |
| IdentityAttribute | `LifecycleState.bsh` | lifecycle from HR status and dates, keeps `oldValue`, a case that expects an error |
| IdentityAttribute | `DepartmentCode.bsh` | exact, keyword and fallback mapping |
| ManagerCorrelation | `ManagerByEmployeeId.bsh` | parsing `"SMITH, Tyler (000123)"` |
| Transform | `ProperCaseName.bsh`, `PhoneE164.bsh` | name casing rules; phone normalisation with an optional input |

Files named `*.buggy.*` are broken on purpose. The suite reports them as **CAUGHT** when a case fails, errors or
times out (or validation finds an error), and as **MISSED** if they pass. They cover a `String.valueOf(null)`
crash, strings compared with `==`, and an endless loop.

How the suite finds things:

* every `.java`, `.bsh` or `.xml` file under the folder is a rule; `Foo.java` uses `Foo.cases.json` next to it
  (no cases file means a smoke run with default inputs);
* the rule type comes from the folder name (`samples/Transform/...`), else it is auto-detected;
* each case gets 3 seconds unless you pass `--timeout-ms`; `--verbose` prints every case; `--junit` writes one
  report for CI. Failing rules are printed in full, passing ones as one line.

To build your own library, copy the layout: `my-rules/<RuleType>/<Name>.java` + `<Name>.cases.json`, then run
`.\iscrule.cmd suite my-rules`.

## The paste-here folder

1. Open [`paste-here/Rule.java`](../paste-here/Rule.java), replace its contents with your rule, and save. Any form works:
   an RDK-style `public class X { ... }`, raw BeanShell, or full `<Rule>` XML. The folder is outside `src/`, so the
   pasted code is never compiled with the tool.
2. Run it, in either of these ways:
   * **In your IDE:** open `src/main/java/com/iscrulekit/RunPasted.java` and press the Run arrow next to `main`.
   * **In a terminal:** `.\iscrule.cmd paste` (add `--watch` to re-run on every save, `--verbose` for full output).
3. It validates the rule and runs it. If [`paste-here/Rule.cases.json`](../paste-here) exists, those cases are used for
   pass/fail checks; otherwise it is a smoke run with default inputs. Create the cases file with
   `.\iscrule.cmd add-test` (or `gen`). Delete or replace it when you paste a different rule.

## Quickest path from the clipboard

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
paste-here/                      Rule.java (paste your rule) + Rule.cases.json (its test cases)
rules/                           rules you import or create: rules/<Type>/<Name>.java, .cases.json, Test.java
imports/                         rule XML / JSON exports you want to import
samples/                         realistic sample rules for every type, with cases (iscrule suite)
examples/                        small examples used in this reference
docs/REFERENCE.md                this file
scripts/build-offline.ps1        build without Maven
scripts/selftest.ps1             build + end-to-end checks of the kit itself
src/main/java/com/iscrulekit/    the kit: Main (CLI), Validator, RuleRunner, RuleAnalyzer, CaseWizard, Importer, ...
src/main/java/sailpoint/**       mock ISC classes
```
