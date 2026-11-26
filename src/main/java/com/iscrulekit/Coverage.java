package com.iscrulekit;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Branch coverage for a rule: every {@code if}, {@code else}, loop and {@code catch} block gets a marker
 * ({@code log.debug("@@cov:N");}) inserted right after its opening brace, on the same line, so line numbers do not
 * move. A run of the marked copy reports which blocks were entered. Only used while generating tests; the rule you
 * deploy is never changed.
 */
final class Coverage {
    private Coverage() {}

    /** One block of the rule: id, file line of its opening brace, and what it is ("if (...)", "else of line 12", ...). */
    record Block(int id, int line, String what) {}

    /** A value path: variable {@code var} gets a value in block {@code def} and is read in block {@code use}. */
    record ValuePath(String var, int def, int use, int defLine, int useLine) {
        String key() { return "d" + def + ":" + use; }
    }

    record Instrumented(ParsedRule rule, List<Block> blocks, List<ValuePath> paths) {}

    static final String MARK = "@@cov:";
    private static final Pattern KEYWORD = Pattern.compile("\\b(if|for|while|catch|else)\\b");

    static Instrumented instrument(ParsedRule p) {
        String src = p.source == null ? "" : p.source;
        String blank = Scan.blank(Scan.tokenize(src));
        List<Block> blocks = new ArrayList<>();
        List<Object[]> inserts = new ArrayList<>(); // [position, text]
        List<int[]> spans = new ArrayList<>();   // [open brace, close brace, block id]
        Matcher m = KEYWORD.matcher(blank);
        while (m.find()) {
            String kw = m.group(1);
            int at = m.end();
            int condStart = -1, condEnd = -1;
            if (!kw.equals("else")) {
                at = skipWs(blank, at);
                if (at >= blank.length() || blank.charAt(at) != '(') continue;
                condStart = at;
                at = matchParen(blank, at);
                if (at < 0) continue;
                condEnd = at;
                at++;
            } else {
                int next = skipWs(blank, at);
                if (blank.startsWith("if", next) && (next + 2 >= blank.length() || !Character.isJavaIdentifierPart(blank.charAt(next + 2)))) continue; // "else if": the if is the block
            }
            at = skipWs(blank, at);
            if (at >= blank.length()) continue;
            boolean braced = blank.charAt(at) == '{';
            int end;
            if (braced) end = matchBrace(blank, at);
            else { // a single statement: "if (x) return y;" -> marked as "if (x) { mark; return y; }" on the same line
                if (startsWithKeyword(blank, at)) continue; // nested if/for/while/try/switch without braces: not marked
                end = statementEnd(blank, at);
                if (end < 0) continue;
            }
            int line = Scan.lineOf(src, at) + p.offset;
            String what = kw.equals("else") ? "else" : kw + " " + oneLine(src.substring(condStart, condEnd + 1));
            if (kw.equals("else")) {
                int closing = previousNonWs(blank, m.start() - 1);
                Integer ifLine = closing >= 0 ? ifLineFor(blank, closing) : null;
                what = ifLine == null ? "else" : "else of the if on line " + (ifLine + p.offset);
            }
            int id = blocks.size() + 1;
            blocks.add(new Block(id, line, what));
            String mark = " log.debug(\"" + MARK + id + "\");";
            if (braced) inserts.add(new Object[] {at + 1, mark});
            else {
                inserts.add(new Object[] {at, "{" + mark + " "});
                inserts.add(new Object[] {end + 1, " }"});
            }
            spans.add(new int[] {at, end, id, chainPredecessor(blank, m.start(), kw, spans)});
        }
        inserts.sort((x, y) -> Integer.compare((Integer) y[0], (Integer) x[0])); // from the end, so positions stay valid
        StringBuilder out = new StringBuilder(src);
        for (Object[] ins : inserts) out.insert((int) (Integer) ins[0], (String) ins[1]);
        ParsedRule copy = copy(p, out.toString());
        String dump = System.getenv("ISCRULE_DUMP_COVERAGE"); // debugging aid: write the marked copy to this file
        if (dump != null && !dump.isBlank()) {
            try { java.nio.file.Files.writeString(java.nio.file.Path.of(dump), out.toString()); } catch (java.io.IOException ignored) { /* best effort */ }
        }
        return new Instrumented(copy, blocks, valuePaths(blank, spans, blocks));
    }

    /** Block ids entered during a run (from the marker log lines). */
    static Set<Integer> hits(RuleRunner.Outcome o) {
        Set<Integer> s = new LinkedHashSet<>();
        for (RuleRunner.LogLine l : o.logs()) {
            String msg = l.message();
            if (msg != null && msg.startsWith(MARK)) {
                try { s.add(Integer.parseInt(msg.substring(MARK.length()).trim())); } catch (NumberFormatException ignored) { /* not ours */ }
            }
        }
        return s;
    }

    /** The outcome without the marker log lines (what the rule itself logged). */
    static List<RuleRunner.LogLine> realLogs(RuleRunner.Outcome o) {
        List<RuleRunner.LogLine> l = new ArrayList<>();
        for (RuleRunner.LogLine x : o.logs()) if (x.message() == null || !x.message().startsWith(MARK)) l.add(x);
        return l;
    }

    private static ParsedRule copy(ParsedRule p, String source) {
        ParsedRule c = new ParsedRule();
        c.xml = p.xml;
        c.xmlOk = p.xmlOk;
        c.type = p.type;
        c.typeName = p.typeName;
        c.typeFromXml = p.typeFromXml;
        c.name = p.name;
        c.description = p.description;
        c.language = p.language;
        c.source = source;
        c.offset = p.offset;
        c.hasCdata = p.hasCdata;
        c.hasDoctype = p.hasDoctype;
        c.hasSource = p.hasSource;
        c.hasNameAttr = p.hasNameAttr;
        c.hasTypeAttr = p.hasTypeAttr;
        c.ruleLine = p.ruleLine;
        c.signatureArgs = p.signatureArgs;
        return c;
    }

    private static final Pattern IDENT = Pattern.compile("\\b[A-Za-z_$][\\w$]*\\b");

    /**
     * Def-use pairs for variables that get a value in two or more different blocks (typically per-environment
     * constants): each (block that sets it, block that reads it) pair should run together at least once, so every
     * branch's value is checked where it is used.
     */
    private static List<ValuePath> valuePaths(String blank, List<int[]> spans, List<Block> blocks) {
        java.util.Map<String, Set<Integer>> defs = new java.util.LinkedHashMap<>();
        java.util.Map<String, Set<Integer>> uses = new java.util.LinkedHashMap<>();
        Matcher m = IDENT.matcher(blank);
        while (m.find()) {
            String name = m.group();
            if (Character.isDigit(name.charAt(0))) continue;
            int after = skipWs(blank, m.end());
            int before = previousNonWs(blank, m.start() - 1);
            if (before >= 0 && blank.charAt(before) == '.') continue; // a.field / a.method()
            boolean assign = after < blank.length() - 1 && blank.charAt(after) == '=' && blank.charAt(after + 1) != '=';
            Integer block = innermost(spans, m.start());
            if (block == null) continue;
            (assign ? defs : uses).computeIfAbsent(name, k -> new LinkedHashSet<>()).add(block);
        }
        java.util.Map<Integer, Integer> lineOf = new java.util.HashMap<>();
        for (Block b : blocks) lineOf.put(b.id(), b.line());
        List<ValuePath> out = new ArrayList<>();
        for (java.util.Map.Entry<String, Set<Integer>> e : defs.entrySet()) {
            if (e.getValue().size() < 2) continue; // set in one place only: nothing to combine
            for (int d : e.getValue())
                for (int u : uses.getOrDefault(e.getKey(), Set.of()))
                    if (u != d && !isAncestor(spans, u, d) && !isAncestor(spans, d, u) && !redefinedBefore(spans, e.getValue(), d, u)
                            && !exclusive(spans, d, u))
                        out.add(new ValuePath(e.getKey(), d, u, lineOf.getOrDefault(d, 0), lineOf.getOrDefault(u, 0)));
        }
        return out;
    }

    /** For an "else" or "else if" block: the id of the block whose closing brace comes right before it, else 0. */
    private static int chainPredecessor(String blank, int keywordStart, String kw, List<int[]> spans) {
        int i = previousNonWs(blank, keywordStart - 1);
        if (!kw.equals("else")) { // "else if": step back over the else
            if (i < 3 || !blank.startsWith("else", i - 3)) return 0;
            i = previousNonWs(blank, i - 4);
        }
        if (i < 0 || (blank.charAt(i) != '}' && blank.charAt(i) != ';')) return 0; // ';' ends a braceless branch
        for (int[] s : spans) if (s[1] == i) return s[2];
        return 0;
    }

    private static final Pattern STATEMENT_KEYWORD = Pattern.compile("(if|for|while|do|try|switch|else|synchronized)\\b");

    private static boolean startsWithKeyword(String s, int at) {
        return STATEMENT_KEYWORD.matcher(s).region(at, Math.min(s.length(), at + 14)).lookingAt();
    }

    /** Index of the ';' that ends the statement starting at {@code at} (brackets respected), or -1. */
    private static int statementEnd(String s, int at) {
        int depth = 0;
        for (int i = at; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(' || c == '[') depth++;
            else if (c == ')' || c == ']') depth--;
            else if (c == '{' && depth == 0) return -1; // a block inside a single statement: leave it alone
            else if (c == ';' && depth == 0) return i;
        }
        return -1;
    }

    /** True when the two blocks are branches of the same if / else if / else chain (never both run). */
    private static boolean exclusive(List<int[]> spans, int a, int b) {
        return root(spans, a) == root(spans, b);
    }

    private static int root(List<int[]> spans, int id) {
        int cur = id;
        for (int guard = 0; guard < 1000; guard++) {
            int pred = 0;
            for (int[] s : spans) if (s[2] == cur) pred = s[3];
            if (pred == 0) return cur;
            cur = pred;
        }
        return cur;
    }

    /**
     * True when the variable is set again in a block that encloses the use (and does not enclose the first setting), so
     * the value from {@code def} can never reach {@code use}.
     */
    private static boolean redefinedBefore(List<int[]> spans, Set<Integer> defBlocks, int def, int use) {
        for (int a : defBlocks) {
            if (a == def) continue;
            boolean enclosesUse = a == use || isAncestor(spans, a, use);
            boolean enclosesDef = isAncestor(spans, a, def);
            if (enclosesUse && !enclosesDef) return true;
        }
        return false;
    }

    private static Integer innermost(List<int[]> spans, int pos) {
        int[] best = null;
        for (int[] s : spans) if (s[0] < pos && pos < s[1] && (best == null || s[0] > best[0])) best = s;
        return best == null ? null : best[2];
    }

    /** True when block {@code outer} contains block {@code inner}. */
    private static boolean isAncestor(List<int[]> spans, int outer, int inner) {
        int[] o = null, i = null;
        for (int[] s : spans) { if (s[2] == outer) o = s; if (s[2] == inner) i = s; }
        return o != null && i != null && o[0] < i[0] && i[1] <= o[1];
    }

    private static int matchBrace(String s, int open) {
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return i;
        }
        return s.length();
    }

    private static int skipWs(String s, int i) {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        return i;
    }

    private static int previousNonWs(String s, int i) {
        while (i >= 0 && Character.isWhitespace(s.charAt(i))) i--;
        return i;
    }

    private static int matchParen(String s, int open) {
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') depth++;
            else if (c == ')' && --depth == 0) return i;
        }
        return -1;
    }

    /** For the '}' before an else: the line of the 'if' whose block it closes. */
    private static Integer ifLineFor(String s, int closingBrace) {
        if (s.charAt(closingBrace) != '}') return null;
        int depth = 0;
        for (int i = closingBrace; i >= 0; i--) {
            char c = s.charAt(i);
            if (c == '}') depth++;
            else if (c == '{' && --depth == 0) {
                int j = previousNonWs(s, i - 1);
                if (j >= 0 && s.charAt(j) == ')') { // find the matching '(' and the keyword before it
                    int d = 0;
                    for (int k = j; k >= 0; k--) {
                        if (s.charAt(k) == ')') d++;
                        else if (s.charAt(k) == '(' && --d == 0) return Scan.lineOf(s, k);
                    }
                }
                return Scan.lineOf(s, i);
            }
        }
        return null;
    }

    private static String oneLine(String s) {
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() > 90 ? t.substring(0, 87) + "...)" : t;
    }
}
