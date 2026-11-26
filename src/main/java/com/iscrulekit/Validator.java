package com.iscrulekit;

import com.iscrulekit.Finding.Severity;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Static checks for ISC rules, modelled on SailPoint's published rule guidelines and code restrictions.
 * This is an independent implementation: it is not SailPoint's official Rule Validator (sp-rv).
 */
public final class Validator {

    // ---- Code restrictions (blocked in cloud and connector rules) -------------------------------------------------
    private static final List<String> API_BLOCK = List.of(
            "getJdbcConnection", "getConnection", "isClosed", "setUserName", "getUserName", "impersonate", "setScopeResults",
            "getScopeResults", "getConfiguration", "encrypt", "decrypt", "authenticate", "sendEmailNotification", "runRule",
            "runScript", "startTransaction", "commitTransaction", "rollbackTransaction", "getObjectById", "getObjectByName",
            "getObject", "lockObjectById", "lockObjectByName", "lockObject", "unlockObject", "getUniqueObject", "getObjects",
            "search", "update", "countObjects", "removeObjects", "attach", "decache", "clearHighLevelCache", "getReferencedObject",
            "enableStatistics", "printStatistics", "reconnect", "setPersistenceOptions", "getPersistenceOptions", "setProperty",
            "importObject", "notify", "notifyAll", "removeObject", "sleep", "printStackTrace", "exec", "exit", "getRuntime");
    private static final List<String> BSH_CALLS = List.of(
            "rm", "bg", "cp", "desktop", "mv", "run", "editor", "bind", "cat", "cd", "dir", "dirname", "frame", "load", "pwd", "source");
    private static final List<String> BSH_WORDS = List.of(
            "getBsh", "Prompt", "pathToFile", "addClassPath", "browseClass", "classBrowser", "importCommands", "makeWorkspace",
            "reloadClasses", "setClassPath", "sourceRelative");

    private static final Pattern API_RE = Pattern.compile("(?<![\\w])(" + String.join("|", API_BLOCK) + ")\\s*\\(");
    private static final Pattern BSH_CALL_RE = Pattern.compile("(?<![\\w.])(" + String.join("|", BSH_CALLS) + ")\\s*\\(");
    private static final Pattern BSH_WORD_RE = Pattern.compile("(?<![\\w.])(" + String.join("|", BSH_WORDS) + ")\\b");

    private record Literal(Pattern re, String label) {}
    private static final List<Literal> LITERALS = List.of(
            new Literal(Pattern.compile("\\bcontext\\s*\\.\\s*prepare\\b"), "context.prepare()"),
            new Literal(Pattern.compile("\\bSailpointFactory\\s*\\.\\s*toXml"), "SailpointFactory.toXml()"),
            new Literal(Pattern.compile("\\bSystem\\s*\\.\\s*(out|err)\\b"), "System.out / System.err"),
            new Literal(Pattern.compile("\\bRunnable\\b"), "Runnable"),
            new Literal(Pattern.compile("\\bThread\\b"), "Thread"),
            new Literal(Pattern.compile("\\bpublic\\s+static\\b"), "public static"),
            new Literal(Pattern.compile("\\bXMLObjectFactory\\b"), "XMLObjectFactory"),
            new Literal(Pattern.compile("\\bLog4j\\b"), "Log4j"),
            new Literal(Pattern.compile("\\bLogger\\s*\\.\\s*getLogger\\b"), "Logger.getLogger"),
            new Literal(Pattern.compile("\\bSystem\\s*\\.\\s*exit\\b"), "System.exit"),
            new Literal(Pattern.compile("\\bRuntime\\s*\\."), "Runtime."),
            new Literal(Pattern.compile("\\.\\s*server\\s*\\("), ".server("),
            new Literal(Pattern.compile("\\bServerSocket\\b"), "ServerSocket"),
            new Literal(Pattern.compile("\\bProcessBuilder\\b"), "ProcessBuilder"),
            new Literal(Pattern.compile("\\bshutdownOnExit\\b"), "shutdownOnExit"));

    private static final Set<String> KEYWORDS = Set.of("return", "throw", "else", "new", "case", "import", "break", "continue", "do",
            "typeof", "delete", "var", "let", "const", "function", "in", "of", "instanceof", "void", "package", "goto", "assert");
    private static final Set<String> CONTROL = Set.of("if", "for", "while", "switch", "catch", "synchronized", "return", "new", "throw",
            "else", "super", "this", "print", "eval", "assert", "do", "try", "finally", "case", "default", "instanceof");

    private static final Pattern LOG_CALL = Pattern.compile("\\blog\\s*\\.\\s*(\\w+)\\s*\\(");
    private static final Pattern SENSITIVE = Pattern.compile("(pass(word|wd)?|secret|token|apikey)", Pattern.CASE_INSENSITIVE);
    private static final Pattern LOOP = Pattern.compile("\\b(for|while)\\s*\\(");
    private static final Pattern GETATTR_CHAIN = Pattern.compile("\\.getAttribute\\s*\\([^()]*\\)\\s*\\.\\s*\\w+\\s*\\(");
    /** {@code x == "lit"}, {@code "lit" != x} (but not {@code x == null}). */
    private static final Pattern STRING_EQ = Pattern.compile("[=!]=\\s*\"|\"\\s*[=!]=(?!=)");
    /** String.valueOf(...) whose argument reads an attribute or map entry, which may be null. */
    private static final Pattern VALUEOF_NULL = Pattern.compile("String\\s*\\.\\s*valueOf\\s*\\(\\s*[\\w.]*\\b(getAttribute\\w*|getStringAttribute\\w*|getRawAccountAttribute|get)\\s*\\(");
    private static final Pattern SET_MAP = Pattern.compile("\\.\\s*setMap\\s*\\(");
    private static final Pattern HARDCODED = Pattern.compile(
            "\\b(pass(word|wd)?|pwd|secret|api[_-]?key|token|client_?secret)\\w*\\s*=\\s*\"[^\"]+\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern GENERICS = Pattern.compile("\\b[A-Z]\\w*\\s*<\\s*[A-Z?]\\w*(\\s*,\\s*[A-Z?]\\w*)*\\s*>");

    private final ParsedRule p;
    private final List<Finding> out = new ArrayList<>();

    private Validator(ParsedRule p) { this.p = p; }

    public static List<Finding> validate(ParsedRule p) { return new Validator(p).run(); }

    private void add(Severity s, String code, Integer line, String msg) { out.add(new Finding(s, code, line, msg)); }

    /**
     * BUG003: {@code a && b || c} without brackets. Java evaluates {@code &&} first, which is rarely what the author meant
     * (e.g. {@code active && x.isEmpty() || "false".equals(x)} lets inactive users through). Checked per bracket level,
     * per argument and per statement, on the source with strings and comments blanked out.
     */
    private record AndOrGroup(int start, boolean[] seen) {}

    private void mixedAndOr(String blank) {
        java.util.ArrayDeque<AndOrGroup> stack = new java.util.ArrayDeque<>();
        stack.push(new AndOrGroup(0, new boolean[2]));
        java.util.Set<Integer> reported = new java.util.HashSet<>();
        for (int i = 0; i < blank.length(); i++) {
            char c = blank.charAt(i);
            AndOrGroup g = stack.peek();
            boolean pair = i + 1 < blank.length() && blank.charAt(i + 1) == c;
            if (c == '&' && pair) { g.seen()[0] = true; i++; }
            else if (c == '|' && pair) { g.seen()[1] = true; i++; }
            else if (c == '(') stack.push(new AndOrGroup(i, new boolean[2]));
            else if (c == ')' && stack.size() > 1) reportMixed(blank, stack.pop(), reported);
            else if (c == ',' || c == ';' || c == '{' || c == '}' || c == '?' || (c == ':' && stack.size() > 1)) {
                reportMixed(blank, g, reported); // each argument / statement / ternary part is checked on its own
                stack.pop();
                stack.push(new AndOrGroup(i + 1, new boolean[2]));
            }
        }
        while (!stack.isEmpty()) reportMixed(blank, stack.pop(), reported);
    }

    private void reportMixed(String blank, AndOrGroup g, java.util.Set<Integer> reported) {
        if (!g.seen()[0] || !g.seen()[1]) return;
        int at = g.start();
        while (at < blank.length() - 1 && Character.isWhitespace(blank.charAt(at))) at++; // first character of the statement
        int line = Scan.lineOf(blank, at);
        if (!reported.add(line)) return;
        add(Severity.WARN, "BUG003", L(line), "&& and || mixed without brackets. && is evaluated first, so 'a && b || c' means "
                + "'(a && b) || c', and c alone is enough to make it true. Add brackets to say what you mean, "
                + "e.g. a && (b || c).");
    }

    private List<Finding> run() {
        out.addAll(p.findings);
        RuleType t = p.type;
        xmlChecks();

        ClassUnwrapper.Result u = ClassUnwrapper.unwrap(p.source == null ? "" : p.source, t == null ? List.of() : t.inputs);
        String src = u.source();
        if (src.isBlank()) {
            add(Severity.ERROR, "SRC001", p.offset + 1, "The rule source is empty.");
            return finish();
        }
        if (u.wrapped()) {
            add(Severity.INFO, "CLS001", L(u.classLine()), "Rule is wrapped in a Java class ('" + u.className() + "', RDK style). "
                    + "ISC rules are plain scripts: the wrapper is stripped for testing and export, methods become script functions, "
                    + "and statements in the class body run as the script.");
        }
        for (ClassUnwrapper.Removed r : u.removed()) {
            add(Severity.WARN, "CLS002", L(r.line()), "'" + r.name() + "' is an input injected by ISC. Declaring it yourself shadows the real "
                    + "object. Removed for testing and export.");
        }

        List<Scan.Tok> toks = Scan.tokenize(src);
        String blank = Scan.blank(toks);
        String nc = Scan.noComments(toks);
        String[] lines = blank.split("\n", -1);
        String[] ncl = nc.split("\n", -1);

        syntax(toks, blank, lines, src);
        restrictions(lines);
        guidelines(toks, blank, lines, ncl);
        mixedAndOr(blank);
        methods(blank, u);
        typeSpecific(blank, lines, src);
        return finish();
    }

    private int L(int line) { return line + p.offset; }

    // ---------------------------------------------------------------------------------------------------------------
    private void xmlChecks() {
        RuleType t = p.type;
        if (p.xml && p.xmlOk) {
            if (!p.hasNameAttr) add(Severity.ERROR, "XML010", p.ruleLine, "<Rule> is missing the required name attribute.");
            if (!p.hasTypeAttr) add(Severity.ERROR, "XML011", p.ruleLine, "<Rule> is missing the required type attribute.");
            if (!p.hasSource) add(Severity.ERROR, "XML012", p.ruleLine, "<Rule> has no <Source> element.");
            if (!p.hasDoctype) add(Severity.INFO, "XML013", 1, "Missing <!DOCTYPE Rule PUBLIC \"sailpoint.dtd\" \"sailpoint.dtd\"> declaration (standard in ISC rule files).");
            if (p.description.isEmpty()) add(Severity.INFO, "XML014", p.ruleLine, "No <Description>. Reviewers expect rules to be documented.");
            if (p.hasSource && !p.hasCdata && (p.source.contains("<") || p.source.contains("&")))
                add(Severity.WARN, "XML015", p.offset + 1, "Source contains < or & but is not wrapped in <![CDATA[ ]]>. ISC review standards want plain & < > inside CDATA, not &amp; &lt; &gt;.");
            if (p.hasCdata && Pattern.compile("&(amp|lt|gt);").matcher(p.source).find())
                add(Severity.WARN, "XML016", p.offset + 1, "Source contains &amp; / &lt; / &gt; entities inside CDATA. Convert them to & < > (ISC review requirement).");
            if (p.language != null && !p.language.equals("beanshell"))
                add(Severity.WARN, "XML017", p.ruleLine, "language='" + p.language + "': ISC rules are BeanShell. Use language=\"beanshell\" or omit it.");
        }
        if (p.typeFromXml && t == null && p.typeName != null) {
            if (RuleType.DEPRECATED.containsKey(p.typeName))
                add(Severity.ERROR, "TYP002", p.ruleLine, "Rule type '" + p.typeName + "' is deprecated and not supported in ISC: " + RuleType.DEPRECATED.get(p.typeName) + ".");
            else {
                StringBuilder names = new StringBuilder();
                for (RuleType r : RuleType.values()) names.append(names.length() == 0 ? "" : ", ").append(r.xmlName);
                add(Severity.ERROR, "TYP001", p.ruleLine, "Rule type '" + p.typeName + "' is not a supported ISC cloud rule type. Supported: " + names + ".");
            }
        }
        if (t == RuleType.BUILD_MAP && p.xml && p.xmlOk && p.signatureArgs == null)
            add(Severity.WARN, "SIG001", p.ruleLine, "BuildMap rules should declare a <Signature><Inputs> with cols, record, application, schema (and log).");
        if (t != null && p.signatureArgs != null && !t.signature().isEmpty()) {
            List<String> miss = new ArrayList<>();
            for (String[] a : t.signature()) if (!a[0].equals("log") && !p.signatureArgs.contains(a[0])) miss.add(a[0]);
            if (!miss.isEmpty()) add(Severity.WARN, "SIG002", p.ruleLine, "Signature is missing argument(s): " + String.join(", ", miss) + ".");
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    private void syntax(List<Scan.Tok> toks, String blank, String[] lines, String src) {
        for (Scan.Tok k : toks) {
            if (k.kind() == Scan.Kind.STRING && k.unterminated()) add(Severity.ERROR, "SYN001", L(k.line()), "Unterminated string literal.");
            if (k.kind() == Scan.Kind.COMMENT && k.unterminated()) add(Severity.ERROR, "SYN002", L(k.line()), "Unterminated /* comment.");
        }
        // bracket balance
        record Open(char c, int line) {}
        java.util.ArrayDeque<Open> st = new java.util.ArrayDeque<>();
        int line = 1;
        for (int i = 0; i < blank.length(); i++) {
            char c = blank.charAt(i);
            if (c == '\n') { line++; continue; }
            if (c == '(' || c == '[' || c == '{') st.push(new Open(c, line));
            else if (c == ')' || c == ']' || c == '}') {
                Open top = st.poll();
                char want = c == ')' ? '(' : c == ']' ? '[' : '{';
                if (top == null) add(Severity.ERROR, "SYN003", L(line), "Unmatched '" + c + "'.");
                else if (top.c() != want) add(Severity.ERROR, "SYN004", L(line), "Mismatched '" + c + "': expected the closer for '" + top.c() + "' opened on line " + L(top.line()) + ".");
            }
        }
        for (Open o : st) add(Severity.ERROR, "SYN005", L(o.line()), "Unclosed '" + o.c() + "'.");

        missingSemicolons(lines);

        // the real BeanShell parser: this is exactly the grammar ISC rules must satisfy
        try {
            bsh.Parser parser = new bsh.Parser(new StringReader(src));
            while (!parser.Line()) { /* parse every statement */ }
        } catch (bsh.ParseException e) {
            int ln = Math.max(1, e.getErrorLineNumber());
            String msg = String.valueOf(e.getMessage()).replaceAll("\\s+", " ");
            if (msg.length() > 240) msg = msg.substring(0, 240) + "...";
            add(Severity.ERROR, "SYN010", L(ln), "BeanShell cannot parse this: " + msg
                    + (GENERICS.matcher(blank).find() ? " Hint: BeanShell 2.x does not understand generics such as List<String>; remove the type parameters." : ""));
        } catch (Throwable e) {
            add(Severity.ERROR, "SYN011", null, "BeanShell lexer/parser failure: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private void missingSemicolons(String[] lines) {
        List<int[]> idx = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) if (!lines[i].isBlank()) idx.add(new int[]{i});
        Pattern endsOk = Pattern.compile("[;{}(,\\[:?&|+\\-*/=<>.]$");
        Pattern control = Pattern.compile("^(if|for|while|switch|catch|else|try|finally|do|synchronized|case|default)\\b");
        Pattern methodHeader = Pattern.compile("^[\\w<>\\[\\],.]+\\s+\\w+\\s*\\(.*\\)\\s*(throws\\s+[\\w.,\\s]+)?$");
        Pattern contNext = Pattern.compile("^[.+\\-*/?:&|)\\]=,]");
        Pattern stmt1 = Pattern.compile("^(return|throw|break|continue)\\b");
        Pattern stmt2 = Pattern.compile("^[\\w<>\\[\\],.]+\\s+\\w+\\s*=");
        Pattern stmt3 = Pattern.compile("^[\\w.]+\\s*(\\+\\+|--|[+\\-*/]?=)[^=]");
        Pattern stmt4 = Pattern.compile("^[\\w.]+\\s*\\(.*\\)$");
        for (int k = 0; k < idx.size(); k++) {
            int i = idx.get(k)[0];
            String s = lines[i].trim();
            String nxt = k + 1 < idx.size() ? lines[idx.get(k + 1)[0]].trim() : "";
            if (endsOk.matcher(s).find()) continue;
            if (control.matcher(s).find()) continue;
            if (methodHeader.matcher(s).find() && !s.contains("=")) continue;
            if (contNext.matcher(nxt).find() || nxt.startsWith("{")) continue;
            if (Scan.count(s, '(') - Scan.count(s, ')') != 0) continue;
            if (stmt1.matcher(s).find() || stmt2.matcher(s).find() || stmt3.matcher(s).find() || stmt4.matcher(s).find())
                add(Severity.WARN, "SYN006", L(i + 1), "Statement probably missing a terminating ';' (BeanShell requires it).");
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    private void restrictions(String[] lines) {
        for (int i = 0; i < lines.length; i++) {
            String ln = lines[i];
            Matcher m = API_RE.matcher(ln);
            if (m.find()) add(Severity.ERROR, "RES001", L(i + 1), "Blocked call '" + m.group(1) + "()': not allowed in ISC rules. Use IdnRuleUtil (idn) helpers instead.");
            m = BSH_CALL_RE.matcher(ln);
            if (m.find()) add(Severity.ERROR, "RES002", L(i + 1), "Blocked BeanShell command '" + m.group(1) + "(': shell/console commands are not allowed.");
            m = BSH_WORD_RE.matcher(ln);
            if (m.find()) add(Severity.ERROR, "RES002", L(i + 1), "Blocked BeanShell command '" + m.group(1) + "'.");
            for (Literal lit : LITERALS) if (lit.re().matcher(ln).find()) add(Severity.ERROR, "RES003", L(i + 1), "Blocked construct '" + lit.label() + "'.");
            if (Pattern.compile("\\.\\s*toXml\\s*\\(").matcher(ln).find())
                add(Severity.ERROR, "GUI001", L(i + 1), "Do not serialize objects with .toXml(): prohibited in rules.");
        }
    }

    private void guidelines(List<Scan.Tok> toks, String blank, String[] lines, String[] ncl) {
        for (int i = 0; i < lines.length; i++) {
            String ln = lines[i];
            Matcher lm = LOG_CALL.matcher(ln);
            if (lm.find()) {
                String m = lm.group(1);
                if (!List.of("debug", "info", "warn", "error").contains(m) && !m.matches("is\\w+Enabled"))
                    add(Severity.WARN, "GUI002", L(i + 1), "log." + m + "(): guidelines allow log.debug/info/warn/error only.");
                if (m.equals("error")) add(Severity.INFO, "GUI003", L(i + 1), "Use log.error() only for unexpected errors or critical failures.");
                if (i < ncl.length && SENSITIVE.matcher(ncl[i]).find())
                    add(Severity.WARN, "GUI004", L(i + 1), "This log statement appears to log sensitive data. Logging secrets gets a rule rejected.");
            }
            if (LOOP.matcher(ln).find())
                add(Severity.WARN, "GUI005", L(i + 1), "Loop detected. Make sure the exit condition is guaranteed (sp-rv flags loops), and never iterate over all accounts/identities: use a projection query.");
            if (GETATTR_CHAIN.matcher(ln).find())
                add(Severity.WARN, "GUI006", L(i + 1), "Method called directly on a getAttribute(...) result: it may be null. Null-check first.");
        }
        for (int i = 0; i < ncl.length; i++) {
            if (HARDCODED.matcher(ncl[i]).find())
                add(Severity.WARN, "GUI010", L(i + 1), "Looks like a hard-coded credential/test value. No passwords, keys or sensitive values in rule code.");
            if (STRING_EQ.matcher(ncl[i]).find())
                add(Severity.WARN, "BUG001", L(i + 1), "String compared with == or !=. That checks whether both are the same object, not the same text, "
                        + "so it is usually false for values read from identities or accounts. Use \"text\".equals(x) or equalsIgnoreCase().");
            if (SET_MAP.matcher(ncl[i]).find())
                add(Severity.WARN, "API001", L(i + 1), "setMap(...) is not in SailPoint's published rule API for Attributes (the RDK's class stubs "
                        + "do not have it), so RDK tests fail with 'Method setMap not found'. Use putAll(map), which does the same thing.");
            if (VALUEOF_NULL.matcher(ncl[i]).find())
                add(Severity.WARN, "BUG002", L(i + 1), "String.valueOf(...) on a value that can be null. In BeanShell String.valueOf(null) throws "
                        + "NullPointerException (it picks valueOf(char[])). Null-check first, or use \"\" + x.");
        }

        for (Scan.Tok k : toks) {
            if (k.kind() != Scan.Kind.COMMENT) continue;
            String body = k.text().replaceAll("^//+|^/\\*+|\\*+/$", "").trim();
            if (k.text().startsWith("//") && (Pattern.compile("[;{}]\\s*$").matcher(body).find()
                    || Pattern.compile("^[\\w.]+\\([^)]*\\)\\s*$").matcher(body).find()
                    || Pattern.compile("^(if|for|while|return)\\b.*[({]\\s*$").matcher(body).find()))
                add(Severity.WARN, "GUI011", L(k.line()), "Commented-out code. Review standards reject commented-out or unfinished code.");
            if (Pattern.compile("\\b(TODO|FIXME|XXX)\\b").matcher(k.text()).find())
                add(Severity.WARN, "GUI012", L(k.line()), "TODO/FIXME marker: unfinished code is rejected in review.");
        }
        Matcher em = Pattern.compile("catch\\s*\\([^)]*\\)\\s*\\{\\s*\\}").matcher(blank);
        while (em.find()) add(Severity.WARN, "GUI013", L(Scan.lineOf(blank, em.start())), "Empty catch block swallows errors. Log or handle it.");

        List<Integer> nonEmpty = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) if (!lines[i].isBlank()) nonEmpty.add(i);
        Pattern ret = Pattern.compile("^(return|throw)\\b.*;$");
        Pattern okNext = Pattern.compile("^(\\}|case\\b|default\\b|else\\b|catch\\b|finally\\b)");
        for (int k = 0; k + 1 < nonEmpty.size(); k++) {
            String s = lines[nonEmpty.get(k)].trim(), n = lines[nonEmpty.get(k + 1)].trim();
            if (ret.matcher(s).find() && !okNext.matcher(n).find())
                add(Severity.WARN, "GUI014", L(nonEmpty.get(k + 1) + 1), "Unreachable code after '" + s.split("\\s")[0] + "'. Dead code is not allowed.");
        }

        Pattern imp = Pattern.compile("^[ \\t]*import\\s+([\\w.*]+)\\s*;", Pattern.MULTILINE);
        String body = imp.matcher(blank).replaceAll("");
        Matcher im = imp.matcher(blank);
        while (im.find()) {
            String[] parts = im.group(1).split("\\.");
            String last = parts[parts.length - 1];
            if (last.equals("*")) continue;
            if (!Pattern.compile("\\b" + Pattern.quote(last) + "\\b").matcher(body).find())
                add(Severity.INFO, "GUI015", L(Scan.lineOf(blank, im.start())), "Unused import '" + im.group(1) + "'.");
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    private void methods(String blank, ClassUnwrapper.Result u) {
        record Def(String name, int line) {}
        List<Def> defs = new ArrayList<>();
        Matcher dm = DEF_RE.matcher(blank);
        while (dm.find()) {
            if (!isMethodHeader(dm)) continue;
            defs.add(new Def(dm.group(1), Scan.lineOf(blank, dm.start())));
        }
        Set<String> declared = new LinkedHashSet<>();
        for (Def d : defs) declared.add(d.name());

        Set<String> seen = new LinkedHashSet<>();
        Matcher cm = Pattern.compile("(?<![\\w.])([a-z_]\\w*)\\s*\\(").matcher(blank);
        while (cm.find()) {
            String n = cm.group(1);
            if (CONTROL.contains(n) || declared.contains(n) || BSH_CALLS.contains(n) || seen.contains(n)) continue;
            String before = blank.substring(Math.max(0, cm.start() - 12), cm.start());
            if (Pattern.compile("\\bnew\\s*$").matcher(before).find()) continue;
            seen.add(n);
            String sug = null;
            if (IDN_METHODS.contains(n)) sug = "idn." + n; // an IdnRuleUtil method called without idn.
            if (sug == null) for (String d : declared) if (d.contains(n) || n.contains(d)) { sug = d; break; }
            add(Severity.ERROR, "UNDEF001", L(Scan.lineOf(blank, cm.start())), "Call to '" + n + "()' but no method with that name is defined in the rule"
                    + (sug != null ? ". Did you mean '" + sug + "'?" : "."));
        }
        for (Def d : defs) {
            Matcher c = Pattern.compile("(?<![\\w.])" + Pattern.quote(d.name()) + "\\s*\\(").matcher(blank);
            int n = 0;
            while (c.find()) n++;
            if (n <= 1) {
                add(Severity.WARN, "GUI016", L(d.line()), "Method '" + d.name() + "()' is never called. Dead code is not allowed in ISC rules."
                        + (u.wrapped() && d.name().equals("execute")
                        ? " ISC never calls a class-style execute(); only top-level statements run, so anything it assigns stays unset." : ""));
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    private void typeSpecific(String blank, String[] lines, String src) {
        RuleType t = p.type;
        if (t == null) {
            if (!p.typeFromXml) add(Severity.INFO, "TYP000", null, "No rule type known: pass --type to enable type-specific checks.");
            return;
        }
        if (!t.anyVars) {
            for (String n : RuleType.ALL_INPUT_NAMES) {
                if (t.inputs.contains(n)) continue;
                Pattern use = Pattern.compile("(?<![\\w.])" + n + "\\b");
                int ix = -1;
                for (int i = 0; i < lines.length; i++) if (use.matcher(lines[i]).find()) { ix = i; break; }
                if (ix >= 0 && !declared(blank, n))
                    add(Severity.WARN, "VAR001", L(ix + 1), "'" + n + "' is not an input of " + t.xmlName + " rules (inputs: " + String.join(", ", t.inputs)
                            + "). It will be undefined at runtime unless you declare it.");
            }
        }
        // 'return' statements inside helper methods do not count: only the script body's own returns do
        List<int[]> mr = methodRanges(blank);
        boolean retAny = false, retOutside = false;
        Matcher rm = Pattern.compile("\\breturn\\b").matcher(blank);
        while (rm.find()) { retAny = true; if (!inMethod(mr, rm.start())) retOutside = true; }
        if (t.returns != null && !retOutside)
            add(Severity.ERROR, "TYP010", null, t.xmlName + " rules must return a value (" + t.returns + "), but the script body has no return statement."
                    + (retAny ? " Returns inside helper methods do not count." : ""));
        if (t == RuleType.BEFORE_PROVISIONING) {
            Matcher vr = Pattern.compile("\\breturn\\s+[^;\\s]").matcher(blank);
            while (vr.find()) {
                if (!inMethod(mr, vr.start())) {
                    add(Severity.WARN, "TYP012", L(Scan.lineOf(blank, vr.start())), "BeforeProvisioning rules do not return a value; they modify 'plan' in place.");
                    break;
                }
            }
        }
        if ("returnMap".equals(t.returns) && (!src.contains("identityAttributeName") || !src.contains("identityAttributeValue")))
            add(Severity.ERROR, "TYP013", null, t.xmlName + " rules must return a Map with keys 'identityAttributeName' and 'identityAttributeValue'.");
        if (t == RuleType.BUILD_MAP && !Pattern.compile("\\b(map|Map)\\b").matcher(src).find())
            add(Severity.WARN, "TYP014", null, "BuildMap rules should build and return a Map of column to value.");
        if (t == RuleType.ATTRIBUTE_GENERATOR_FROM_TEMPLATE && !Pattern.compile("\\bfield\\b").matcher(src).find())
            add(Severity.INFO, "TYP015", null, "AttributeGeneratorFromTemplate rules normally read the template from 'field'.");
    }

    /** Method names of sailpoint.server.IdnRuleUtil in SailPoint's rule-java-docs 2.0 (the RDK's class stubs). */
    private static final Set<String> IDN_METHODS = Set.of("accountExistsByDisplayName", "accountExistsByNativeIdentity",
            "attrSearchCountAccounts", "attrSearchGetIdentityName", "countAccounts", "countIdentitiesBySearchableIdentityAttribute",
            "dateToString", "findAccountsByAttribute", "findIdentitiesBySearchableIdentityAttribute", "findManagedAttributes",
            "getAccountAttribute", "getAccountAttributeBool", "getAccountAttributeInt", "getAccountByDisplayName",
            "getAccountByNativeIdentity", "getAllAccounts", "getFirstAccount", "getFirstAccountNativeIdentity",
            "getFirstAccountNativeIdentityByAttribute", "getIdentityById", "getManagedAttributeDescription",
            "getManagedAttributeDetails", "getRawAccountAttribute", "getSourceAttributeBySourceId",
            "getSourceAttributeBySourceName", "isUniqueAccountId", "isUniqueLDAPValue", "nullSafeCaseInsensitiveEq",
            "nullSafeCompareTo", "nullSafeEq");

    private static final Pattern DEF_RE = Pattern.compile(
            "^[ \\t]*(?:(?:public|private|protected|static|final|synchronized)\\s+)*[A-Za-z_][\\w.]*(?:\\s*<[^()]*?>)?(?:\\s*\\[\\s*\\])*[ \\t]+(\\w+)\\s*\\([^()]*\\)\\s*(?:throws\\s+[\\w.,\\s]+?)?\\s*\\{",
            Pattern.MULTILINE);

    private static boolean isMethodHeader(Matcher dm) {
        String first = dm.group().trim().split("\\s+")[0];
        return !(CONTROL.contains(dm.group(1)) || CONTROL.contains(first) || KEYWORDS.contains(first));
    }

    /** [open, close] character ranges of method bodies defined in the script. */
    private static List<int[]> methodRanges(String blank) {
        List<int[]> r = new ArrayList<>();
        Matcher dm = DEF_RE.matcher(blank);
        while (dm.find()) {
            if (!isMethodHeader(dm)) continue;
            int open = dm.end() - 1, depth = 0, j = open;
            for (; j < blank.length(); j++) {
                char c = blank.charAt(j);
                if (c == '{') depth++; else if (c == '}' && --depth == 0) break;
            }
            r.add(new int[]{open, j});
        }
        return r;
    }

    private static boolean inMethod(List<int[]> ranges, int idx) {
        for (int[] r : ranges) if (idx > r[0] && idx < r[1]) return true;
        return false;
    }

    private static boolean declared(String blank, String n) {
        Pattern re = Pattern.compile("(\\b(?:[A-Z]\\w*(?:<[^;()]*>)?(?:\\[\\])?|int|long|double|boolean|var|final)\\s+" + n + "\\b)|(\\b" + n + "\\s*=[^=])|(\\(\\s*[^()]*\\b" + n + "\\s*[,)])");
        return re.matcher(blank).find();
    }

    // ---------------------------------------------------------------------------------------------------------------
    private List<Finding> finish() {
        List<Finding> list = new ArrayList<>(new LinkedHashSet<>(out));
        list.sort(Comparator.<Finding, Integer>comparing(f -> f.line() == null ? Integer.MAX_VALUE : f.line())
                .thenComparing(f -> f.severity().ordinal()));
        return list;
    }
}
