# My-ISC-Rule-Kit

Test SailPoint Identity Security Cloud (ISC) rules on your own computer before you deploy them.

* **Checks** your rule for mistakes, such as blocked calls, undefined methods, `==` on strings, and `&&` / `||` without brackets.
* **Runs** your rule on the same BeanShell engine ISC uses, with test data you choose.
* **Writes the tests for you**: it asks what you want to test, or imports a rule XML and generates JUnit tests.

No server, no ISC tenant, and no setup beyond Java. Everything runs from a terminal in this folder.

> This is not SailPoint's official Rule Validator or Rule Development Kit. Run SailPoint's validator and review
> process before deploying.

---

## 1. One-time setup

You need **Java 17 or newer** (a JDK, not just a JRE). Check it with:

```powershell
java -version
```

There is no build step. The first `.\iscrule.cmd` command builds the kit, and it rebuilds by itself whenever the code
changes (for example after `git pull`). If Maven is missing, it is downloaded once into a `tools` folder.
Every command below starts with `.\iscrule.cmd`.

Then install SailPoint's Rule Development Kit into this folder (needs Git):

```powershell
.\iscrule.cmd rdk-update
```

---

## The automatic way (recommended)

You never write test cases. The kit reads your rule, writes the tests, keeps them up to date when you change the code,
and runs them in SailPoint's RDK.

**Once per rule.** Put the rule file in `imports\`, or paste it into `paste-here\Rule.java`, then run:

```powershell
.\iscrule.cmd sync imports\MyRule.xml
```

**Every time you change the rule.** Edit the file, save, and run the same command. Or leave this running and it
re-runs on every save:

```powershell
.\iscrule.cmd sync imports\MyRule.xml --watch
```

**To refresh the RDK from SailPoint** (`git pull`; your files are kept):

```powershell
.\iscrule.cmd rdk-update
```

### What `sync` does

```text
SYNC  imports\MyRule.xml  (BeforeProvisioning)
  validation  0 errors, 6 warnings

  Behaviour changed  Disable, environment=other, cloudLifecycleState=inactive  ->  Disable with password, manager, AC_NewParent
      plan.accountRequests[0].attributeRequests[3].value: expected "OU=Disabled accounts,...", got "OU=Disabled Users,..."
    Is the new behaviour right?
   1) yes - update this test
   2) no - it's a bug, keep the old expectation (the test fails)
   3) yes to this and all other changed auto tests

  existing    51 cases: 48 unchanged, 3 updated (new behaviour accepted)
  added       nothing (every reachable block already has a test)
  coverage    56 of 56 blocks  (100%)
              48 of 48 value paths  (each branch's value checked where it is used, e.g. per-environment OUs)

  test class  src\test\java\sailpoint\MyRuleTest.java   (51 @Test methods)
RESULT  51 passed  (51 RDK tests)
Ready to deploy: ...\rule-development-kit\src\main\resources\rules\Rule - BeforeProvisioning - My Rule.xml
```

1. **Writes test cases for you.** The kit marks every `if`, `else`, loop and `catch` in the rule, then tries
   combinations of the values your rule checks: lifecycle states, affiliations, operations, group DNs,
   environments, `idn` results, null values. It keeps the smallest set of cases that reaches every block. It also
   reaches every **value path**: when a variable gets a different value per branch (such as an OU per
   environment), every value is checked where it's used. Each case records what the rule did, and its name says so:
   `Enable, environment=Stage, affiliation=applicant -> Enable with AC_NewParent`.
2. **When you change the code, it re-runs every case:**
   - **Unchanged results** stay as they are.
   - **Auto cases whose result changed** are shown to you with the exact difference. Answer **1** if the new
     behaviour is right (the test is updated), or **2** if it's a bug (the test keeps failing until you fix the rule).
     `--accept` answers yes to all of them.
   - **Your own cases** (from `add-test`) are never changed. If one fails, it's listed for you to look at.
   - **New code** (a new `if`, a new branch) gets new cases automatically.
3. **Shows what isn't tested yet**, for example `not reached: line 164 if (accountAttribute instanceof String)`.
   Use `add-test` for those.
4. **Runs everything in SailPoint's RDK:** the deployable XML, the JUnit 5 + Mockito test class, and `mvn test`.
   It ends with **Ready to deploy** when everything passes.

`auto` is the same without the RDK step (faster while you work). `--time 30` gives the search more time on big rules
(default 20 seconds), and `--max 80` allows more cases (default 60).

> **Auto cases record what the rule does, not what it should do.** They catch every change in behaviour, but they
> can't know the business rule. Read the case names once: a line like `Enable request, not in Birthright group ->
> Modify` shows the behaviour plainly. For what *must* happen, add your own cases with `add-test`. `sync` never
> changes those.

### `rdk-update`

| Your RDK is... | What happens |
|---|---|
| not installed | cloned into `rule-development-kit\` inside this folder |
| a git clone | `git pull`; your generated tests and rule XMLs are untracked files, so they're kept |
| a zip download | turned into a git clone: a fresh clone, plus every file of yours that SailPoint's copy doesn't have; the old folder is kept as `<name>-before-update-<date>` |

Each time, it also sets Mockito 5.20.0 / Byte Buddy 1.17.7 in the RDK's `pom.xml` when SailPoint's copy pins older
versions (those only work on JDK 17), so the RDK runs on your JDK. It never downgrades. Then it runs all RDK tests.
`--no-test` skips them, and `--rdk <folder>` updates an RDK somewhere else.

---

## 2. Pick how you want to start

| You have... | Go to |
|---|---|
| Rule code you can copy and paste | [3. Paste a rule and test it](#3-paste-a-rule-and-test-it) |
| A rule XML exported from ISC, or JSON from the API | [4. Import a rule XML](#4-import-a-rule-xml) |
| Nothing yet, and you want to write a new rule | [5. Start a new rule](#5-start-a-new-rule) |
| A rule that is ready, and you want SailPoint's own test setup before deploying | [RDK. Test in SailPoint's Rule Development Kit](#rdk-test-in-sailpoints-rule-development-kit) |

---

## 3. Paste a rule and test it

**Step 1: Paste.** Open `paste-here\Rule.java`, replace everything in it with your rule, and save. Plain BeanShell,
a `public class X { ... }` file, or a full `<Rule>` XML all work.

**Step 2: Check and run it.**

```powershell
.\iscrule.cmd paste
```

You get a **VALIDATION** section listing problems in the code, then a **TESTS** section. With no test cases yet,
the rule runs once with sample data. That shows whether it crashes, but not whether it does the right thing.

**Step 3: Create a test case by answering questions.**

```powershell
.\iscrule.cmd add-test
```

The kit reads your rule first, so its suggestions are the values your rule actually checks:

```text
Operation
   1) Disable
   2) Enable
   3) Modify
   number [Modify]:                         <- press Enter to accept the [default]
identity attribute cloudLifecycleState
   1) inactive
   2) active
   number or your own value [active]: 1     <- or type a number to pick a suggestion
idn.getRawAccountAttribute("brb")
   1) true
   2) false
   3) (empty)
   number or your own value [false]:        <- or type any value of your own
```

* **Enter** keeps the value in `[brackets]`.
* **A number** picks a suggestion.
* **Anything else** is used exactly as you typed it.
* **Several values** (a list of groups, say) go in one answer separated by `;`.

The kit then runs the rule and shows what happened:

```text
RESULT OF THE RUN
  What the rule changed
    plan.accountRequests[0].operation: "Modify"  ->  "Enable"
    + plan.accountRequests[0].attributeRequests: AC_NewParent Set "OU=MedCenterUsers,..."
Is this what the rule should do?
   1) yes - save it as the expected result
   2) no - I will say what should happen
   3) save the inputs only, no check
```

* **1 (yes)** when the result is right. From now on the case fails if the rule ever behaves differently.
* **2 (no)** when the result is wrong. You type what should happen, and the case fails until the rule is fixed.

Then give the case a name (Enter accepts the suggested one). The case is saved in `paste-here\Rule.cases.json`.

**Step 4: Run all your cases** whenever you change the rule:

```powershell
.\iscrule.cmd paste
```

Add `--watch` to re-run automatically every time you save `Rule.java`. Press Ctrl+C to stop.

> `Rule.cases.json` belongs to the rule you pasted. When you paste a **different** rule, delete or rename that file
> first.

---

## 4. Import a rule XML

**Step 1:** Save the exported file in the `imports` folder, for example `imports\MyRule.xml`. These all work:

* a single `<Rule>` file;
* a file with several rules in it (`<sailpoint>...</sailpoint>`);
* the JSON the ISC API returns (`name`, `type`, `sourceCode.script`).

**Step 2: Import it.**

```powershell
.\iscrule.cmd import imports\MyRule.xml
```

For each rule in the file you get three new files in `rules\<RuleType>\`:

```text
rules\BeforeProvisioning\MyRule.java          the rule code, taken out of the XML
rules\BeforeProvisioning\MyRule.cases.json    about 20 test cases, generated for you
rules\BeforeProvisioning\MyRuleTest.java      a JUnit test class, one test per case
```

The import also prints how many validation problems the rule has, and how many test cases made it crash.

**Step 3: Run the tests.**

```powershell
.\iscrule.cmd junit rules
```

```text
  MyRuleTest
    PASS  test01_baselineModifyMemberOfAdd...
    PASS  test02_operationDisable
    ...
RESULT  18 passed  (18 tests)
```

**What these tests tell you.** The generated cases record what the rule does **today**, bugs included. They are a
safety net: when you change the rule, any test that fails shows behaviour you changed, for example
`expected "Enable", got "Modify"`. To test what the rule *should* do, add your own cases:

```powershell
.\iscrule.cmd add-test rules\BeforeProvisioning\MyRule.java
.\iscrule.cmd junit-gen rules\BeforeProvisioning\MyRule.java     # puts the new case into MyRuleTest.java too
```

**Step 4: Turn the code back into XML** when you have finished editing:

```powershell
.\iscrule.cmd export rules\BeforeProvisioning\MyRule.java -o "Rule - BeforeProvisioning - MyRule.xml"
```

The XML keeps the original rule name, type and description.

---

## 5. Start a new rule

```powershell
.\iscrule.cmd types                                   # lists the rule types
.\iscrule.cmd new BeforeProvisioning MyNewRule        # creates rules\BeforeProvisioning\MyNewRule.java
```

The new file explains which inputs ISC gives the rule and what it must return. Write your logic, then follow
[section 3](#3-paste-a-rule-and-test-it) with the file name added:

```powershell
.\iscrule.cmd add-test rules\BeforeProvisioning\MyNewRule.java
.\iscrule.cmd run rules\BeforeProvisioning\MyNewRule.java rules\BeforeProvisioning\MyNewRule.cases.json
```

---

## RDK. Test in SailPoint's Rule Development Kit

The kit's own runner uses small stand-ins for SailPoint's classes. SailPoint's **Rule Development Kit (RDK)** uses
SailPoint's real classes, with JUnit 5 and Mockito. One command puts your rule into the RDK, writes the tests and
runs them there:

```powershell
.\iscrule.cmd rdk                                   # the rule in paste-here\Rule.java
.\iscrule.cmd rdk imports\MyRule.xml                # or an exported rule XML
.\iscrule.cmd rdk rules\BeforeProvisioning\MyRule.java
```

```text
RDK  imports\MyRule.xml  ->  D:\...\rule-development-kit

  validation  0 errors, 8 warnings
  rule XML    src\main\resources\rules\Rule - BeforeProvisioning - My Rule.xml   (this is the file you deploy)
  test cases  imports\MyRule.cases.json   (18)
  test class  src\test\java\sailpoint\MyRuleTest.java   (18 @Test methods)

  mvn test    -Dtest=MyRuleTest
    PASS  test01_baselineModifyMemberOfAdd...
    PASS  test02_operationDisable
    ...
RESULT  18 passed  (18 RDK tests)

Ready to deploy: ...\src\main\resources\rules\Rule - BeforeProvisioning - My Rule.xml
```

What it does, in order:

1. **Validates** the rule, showing errors and likely bugs.
2. **Writes the deployable XML** into the RDK's `src/main/resources/rules/`. The name, type, DOCTYPE and CDATA are
   all set, so this is the file you upload to ISC.
3. **Uses the rule's test cases**: `Rule.cases.json` next to the rule, or a cases file you name as the second
   argument. With no cases file it generates one, recording what the rule does today.
4. **Writes an RDK test class** to `src/test/java/sailpoint/<Name>Test.java`, in the same style as SailPoint's
   examples. `idn`, `Identity` and `Application` are Mockito mocks, `ProvisioningPlan` and `AccountRequest` are
   SailPoint's real classes, and the rule is read from the XML with `RuleXmlUtils` and run on a BeanShell
   `Interpreter`.
5. **Runs `mvn test`** for that class and shows one line per test. It ends with **Ready to deploy** when everything
   passes and validation has no errors.

Pasted code has no rule name, so `rdk` asks for one (or pass `--name "My Rule"`). Add your own cases with
`add-test` and run `rdk` again. The test class is regenerated each time, unless you edited it by hand (see the
comment at its top). `--no-run` only writes the files.

**One-time RDK setup**

1. Clone the RDK **next to this folder**:
   `git clone https://github.com/sailpoint-oss/rule-development-kit.git`. Or pass `--rdk <folder>`, or set
   `ISC_RDK`.
2. Install **Maven 3.9+**, or unzip it into a `tools` folder next to the RDK. The first run downloads the test
   libraries.
3. **If your Java is newer than 17:** SailPoint's `pom.xml` pins Mockito 5.2.0, which fails with
   `Mockito cannot mock this class`. Use the updated `pom.xml` in this setup (Mockito 5.20.0, Byte Buddy 1.17.7).
   It runs on Java 17 through 25.

> **Methods SailPoint's classes don't have.** RDK tests fail with `Method setMap( java.util.HashMap ) not found in
> class 'sailpoint.object.Attributes'` when a rule uses a method that isn't in SailPoint's published rule API. The
> kit's own runner can't catch this, which is the main reason to also test in the RDK. `validate` now warns about
> `setMap` (API001); use `putAll(map)` instead.

---

## 6. Reading the results

```text
VALIDATION  0 errors, 6 warnings, 9 notes
  WARN   line 417  BUG003
         && and || mixed without brackets. ...
TESTS  17 cases
  PASS   01 Add Birthright, active staff -> Enable + Regular Users OU
  FAIL   14 Remove Birthright + another group for a student
         Why it failed
             plan.accountRequests[0].attributeRequests: expected 2 items, got 1 item
         What the rule changed
           - ...: memberOf Remove [...]                 (- removed, + added, old -> new)
         Log
           DEBUG ...                                    (the rule's own log lines)
RESULT  14 passed, 3 failed  (17 cases)
```

| You see | It means |
|---|---|
| `ERROR` in validation | The rule would probably fail in ISC, or be rejected. Fix it. |
| `WARN` in validation | Likely a bug or a guideline problem. Read the message. |
| `PASS` | The rule did what the case expects. |
| `FAIL` | The rule ran but did something else. "Why it failed" names the exact field. |
| `ERROR` in a test | The rule crashed. You get the line number and a hint. |
| `RAN` | The rule ran, but the case has nothing to check. Use `add-test` to give it an expected result. |
| `NOT MOCKED: idn.someMethod(...)` | The rule called an `idn` method the case gives no answer for (it got `null`). Add it under `"idn"` in the cases file. |

Add `--verbose` to any command to see everything: passing cases in full, every log line and every value.

---

## 7. Cheat sheet

| I want to... | Command |
|---|---|
| Check and run the pasted rule | `.\iscrule.cmd paste` |
| Re-run every time I save | `.\iscrule.cmd paste --watch` |
| Add a test case by answering questions | `.\iscrule.cmd add-test [rule file]` |
| Generate many test cases automatically | `.\iscrule.cmd gen [rule file] --record` |
| See what a rule depends on | `.\iscrule.cmd analyze [rule file]` |
| Only check the code, without running it | `.\iscrule.cmd validate <rule file>` |
| Run one rule against its cases | `.\iscrule.cmd run <rule file> <cases file>` |
| Import an ISC export | `.\iscrule.cmd import <file.xml or .json>` |
| Run JUnit tests | `.\iscrule.cmd junit rules` |
| Run every rule in a folder | `.\iscrule.cmd suite rules` |
| Start a new rule | `.\iscrule.cmd new <Type> <Name>` |
| **Write and update all tests automatically, run them in the RDK** | `.\iscrule.cmd sync [rule file or XML]` |
| ... and again on every save | `.\iscrule.cmd sync [rule file] --watch` |
| Same without the RDK step | `.\iscrule.cmd auto [rule file]` |
| Install or `git pull` SailPoint's RDK | `.\iscrule.cmd rdk-update` |
| Test in SailPoint's RDK and get the deployable XML | `.\iscrule.cmd rdk [rule file or XML]` |
| Get the XML to upload to ISC | `.\iscrule.cmd export <rule file> -o <out.xml>` |
| See all commands | `.\iscrule.cmd help` |

`[rule file]` is optional. Without it, the command uses `paste-here\Rule.java`.

---

## 8. Common problems

**"Not built yet" or `iscrule.cmd` is not recognised.** Run the build from section 1. Make sure the terminal is
in this folder.

**"could not tell the rule type".** Add `--type BeforeProvisioning` (or whichever type it is; `.\iscrule.cmd types`
lists them). Alternatively, put the file in a folder named after its type, such as `rules\Correlation\`.

**The wizard suggested values that don't fit my case.** Suggestions are only shortcuts. Type the value you want
instead.

**My test passes, but I know the rule has a bug.** Generated and "yes"-approved cases record what the rule *does*.
Add a case with `add-test`, answer **2 (no)**, and type what *should* happen. That case fails until the rule is
fixed.

**"JUnit 4 not found".** Copy `junit-4.x.jar` and `hamcrest-core-1.3.jar` into a `lib` folder here. The kit also finds
them automatically in your Maven cache (`%USERPROFILE%\.m2`).

**`rdk` says "Rule Development Kit not found" or "Maven not found".** Follow the one-time RDK setup above.

**RDK tests fail with `Mockito cannot mock this class`.** Your Java is newer than 17 and the RDK still has
SailPoint's original Mockito version. See step 3 of the RDK setup.

**Strange characters or no colours.** Add `--no-color`, or use Windows Terminal, where colours work automatically.

---

## More

* [docs/REFERENCE.md](docs/REFERENCE.md) has every command option, what each validation code checks, the test-case file
  format, the sample rules (`.\iscrule.cmd suite`), how the `idn` mock works, and the kit's limits.
* After changing the kit itself, run `powershell -File scripts\selftest.ps1` to check that everything still works.
