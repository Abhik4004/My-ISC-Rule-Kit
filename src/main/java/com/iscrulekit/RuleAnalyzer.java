package com.iscrulekit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a rule's source and works out which inputs it depends on and which values make it take a different path.
 * <p>
 * Example: {@code cloudLifecycleState = identity.getAttribute("cloudLifecycleState")} followed by
 * {@code "inactive".equalsIgnoreCase(cloudLifecycleState)} gives the slot {@code identity cloudLifecycleState} with the
 * candidate value {@code inactive}. Values reach a slot through local variables, helper methods that wrap {@code idn}
 * calls, for-each loops and string constants ({@code memberOfValue.equalsIgnoreCase(BIRTHRIGHT_GROUP)}).
 * <p>
 * It is a heuristic (regular expressions over the source, not a full parser): good enough to suggest test values,
 * never relied on for pass/fail.
 */
final class RuleAnalyzer {
    private RuleAnalyzer() {}

    enum Kind {
        IDENTITY_ATTR("identity attribute"), IDENTITY_FIELD("identity"), APP_ATTR("source setting"), IDN("idn"),
        ACCOUNT_ATTR("account attribute"), ACCOUNT_FIELD("account"), LINK_ATTR("account attribute"), ATTR_REQUEST("plan attribute"),
        INPUT("input"), COLUMN("column");
        final String label;
        Kind(String label) { this.label = label; }
    }

    /** One value the rule reads, with the values it compares that input against. */
    static final class Slot {
        final Kind kind;
        final String method; // idn method name for IDN, else null
        final String key;    // attribute / field / input name ("" when the idn call has no literal key)
        final Set<String> values = new LinkedHashSet<>();
        /** Values built from startsWith/endsWith/contains checks (e.g. "svc_account" for startsWith("svc_")): used for variations, never as the default. */
        final Set<String> patternValues = new LinkedHashSet<>();
        /** Human-readable form of those checks, e.g. starts with "svc_". */
        final List<String> patterns = new ArrayList<>();
        boolean list, bool, number, nullable;

        Slot(Kind kind, String method, String key) { this.kind = kind; this.method = method; this.key = key; }

        String id() { return kind + ":" + (method == null ? "" : method + ":") + key; }

        String label() {
            return switch (kind) {
                case IDN -> "idn." + method + "(" + (key.isEmpty() ? "" : "\"" + key + "\"") + ")";
                case IDENTITY_FIELD -> "identity " + key;
                default -> kind.label + " " + key;
            };
        }

        /** Slots whose value decides a branch, or that the rule cannot work without. */
        boolean important() {
            return !values.isEmpty() || !patternValues.isEmpty() || bool || number || nullable || kind == Kind.IDENTITY_FIELD || kind == Kind.IDENTITY_ATTR
                    || kind == Kind.ACCOUNT_ATTR || kind == Kind.ACCOUNT_FIELD
                    || kind == Kind.LINK_ATTR || kind == Kind.INPUT || kind == Kind.COLUMN;
        }
    }

    static final class Analysis {
        RuleType type;
        final Map<String, Slot> slots = new LinkedHashMap<>();
        /** AccountRequest.Operation values the rule mentions (Create, Modify, Enable, ...). */
        final Set<String> operations = new LinkedHashSet<>();
        /** Attribute-request operations the rule mentions (Add, Remove, Set). */
        final Set<String> attrOperations = new LinkedHashSet<>();
        /** String literals that look like ISC source names. */
        final Set<String> sourceNames = new LinkedHashSet<>();
        /** NAME -> every string literal assigned to it, in order. */
        final Map<String, List<String>> constants = new LinkedHashMap<>();

        List<Slot> important() { return slots.values().stream().filter(Slot::important).toList(); }
        List<Slot> others() { return slots.values().stream().filter(s -> !s.important()).toList(); }
        List<Slot> ofKind(Kind k) { return slots.values().stream().filter(s -> s.kind == k).toList(); }
    }

    private static final String STR = "\"((?:[^\"\\\\]|\\\\.)*)\"";
    private static final String ID = "[A-Za-z_$][\\w$]*";
    /** An optional cast before an operand, e.g. (String) g. */
    private static final String CAST = "(?:\\(\\s*[A-Z][\\w.]*\\s*\\)\\s*)?";
    /** An operand: a.b.c, optionally ending in a call with simple arguments, optionally followed by no-arg calls. */
    private static final String OPERAND = CAST + ID + "(?:\\s*\\.\\s*" + ID + ")*(?:\\s*\\([^()]*\\))?(?:\\s*\\.\\s*" + ID + "\\s*\\(\\s*\\))*";
    private static final Pattern CONST = Pattern.compile("\\b(" + ID + ")\\s*=\\s*" + STR + "\\s*;");
    private static final Pattern ASSIGN = Pattern.compile("\\b(" + ID + ")\\s*=(?!=)\\s*([^;{}]+);");
    private static final Pattern FOREACH = Pattern.compile("\\bfor\\s*\\(\\s*(?:final\\s+)?[\\w.<>\\[\\]]+\\s+(" + ID + ")\\s*:\\s*([^()]*(?:\\([^()]*\\)[^()]*)*)\\)");
    private static final Pattern METHOD = Pattern.compile("(?m)^[ \\t]*(?:public\\s+|private\\s+|protected\\s+|static\\s+)*[\\w.<>\\[\\]]+\\s+(" + ID + ")\\s*\\(([^)]*)\\)\\s*(?:throws [\\w., ]+)?\\{");
    private static final Pattern IDN_CALL = Pattern.compile("\\bidn\\s*\\.\\s*(" + ID + ")\\s*\\(([^;]*?)\\)\\s*[;)&|!=?:,.+<>*/-]");
    private static final Pattern LAST_LITERAL = Pattern.compile(STR + "\\s*$");
    private static final Pattern LIT_EQ_OP = Pattern.compile(STR + "\\s*\\.\\s*(?:equals|equalsIgnoreCase)\\s*\\(\\s*(" + OPERAND + ")\\s*\\)");
    private static final Pattern OP_EQ_LIT = Pattern.compile("(" + OPERAND + ")\\s*\\.\\s*(equals|equalsIgnoreCase|contains|startsWith|endsWith|matches)\\s*\\(\\s*" + STR + "\\s*\\)");
    private static final Pattern OP_EQEQ_LIT = Pattern.compile("(" + OPERAND + ")\\s*[=!]=\\s*" + STR);
    private static final Pattern LIT_EQEQ_OP = Pattern.compile(STR + "\\s*[=!]=\\s*(" + OPERAND + ")");
    private static final Pattern OP_EQ_OP = Pattern.compile("(" + OPERAND + ")\\s*\\.\\s*(?:equals|equalsIgnoreCase|contains)\\s*\\(\\s*(" + OPERAND + ")\\s*\\)");
    private static final Pattern OP_NULL = Pattern.compile("(" + OPERAND + ")\\s*[=!]=\\s*null\\b|\\bnull\\s*[=!]=\\s*(" + OPERAND + ")");
    private static final Pattern OP_EMPTY = Pattern.compile("(" + OPERAND + ")\\s*\\.\\s*isEmpty\\s*\\(\\s*\\)");
    private static final Pattern MAP_GET = Pattern.compile("\\b(?:map|row|data)\\s*\\.\\s*get\\s*\\(\\s*" + STR + "\\s*\\)");
    private static final Pattern VOID_CHECK = Pattern.compile("\\b(" + ID + ")\\s*[=!]=\\s*void\\b");
    private static final Pattern ACCOUNT_OP = Pattern.compile("AccountRequest\\s*\\.\\s*Operation\\s*\\.\\s*(\\w+)");
    private static final Pattern ATTR_OP = Pattern.compile("(?<!AccountRequest\\.)\\bOperation\\s*\\.\\s*(\\w+)");
    private static final Pattern NAME_EQ = Pattern.compile("getOperation\\s*\\(\\s*\\)\\s*\\.\\s*name\\s*\\(\\s*\\)\\s*\\.\\s*equals\\w*\\s*\\(\\s*" + STR);
    /** Names that are never plain input values: keywords, and the ISC objects the kit builds itself. */
    private static final Set<String> TRANSFORM_NOT_INPUTS = Set.of("log", "idn", "null", "true", "false", "void", "this",
            "identity", "application", "plan", "account", "link", "field", "schema", "integration", "cols", "record");

    static Analysis analyze(ParsedRule rule) {
        Analysis a = new Analysis();
        a.type = rule.type;
        List<String> inputs = rule.type == null ? List.of() : rule.type.inputs;
        String unwrapped = ClassUnwrapper.unwrap(rule.source == null ? "" : rule.source, inputs).source();
        String src = Scan.noComments(Scan.tokenize(unwrapped));
        new Walker(a, src).run();
        return a;
    }

    /** Holds the state of one analysis. */
    private static final class Walker {
        final Analysis a;
        final String src;
        final Map<String, Slot> vars = new LinkedHashMap<>();
        final Map<String, String> helpers = new LinkedHashMap<>(); // helper method -> idn method it wraps
        final Map<String, String> helperKeys = new LinkedHashMap<>(); // helper method -> fixed last argument of that idn call
        final Set<String> identityVars = new LinkedHashSet<>(List.of("identity"));

        Walker(Analysis a, String src) { this.a = a; this.src = src; }

        void run() {
            constants();
            helpers();
            Matcher m = Pattern.compile("\\bIdentity\\s+(" + ID + ")\\b").matcher(src);
            while (m.find()) identityVars.add(m.group(1));
            m = Pattern.compile("\\b(" + ID + ")\\s*=\\s*(?:\\(\\s*Identity\\s*\\)\\s*)?\\w+\\s*\\.\\s*getIdentity\\s*\\(").matcher(src);
            while (m.find()) identityVars.add(m.group(1));

            directSources();
            typeSpecificInputs(); // plain inputs (value, oldValue, ...) first, so variables computed from them link back
            for (int pass = 0; pass < 3; pass++) bindings();
            comparisons();

            m = ACCOUNT_OP.matcher(src);
            while (m.find()) a.operations.add(m.group(1));
            m = ATTR_OP.matcher(src);
            while (m.find()) if (List.of("Set", "Add", "Remove", "Retain", "Revoke").contains(m.group(1))) a.attrOperations.add(m.group(1));
            m = NAME_EQ.matcher(src);
            while (m.find()) if (List.of("Set", "Add", "Remove", "Retain", "Revoke").contains(m.group(1))) a.attrOperations.add(m.group(1));
            sourceNames();

        }

        /** Literals that look like source names, most used first (counting uses through constants). */
        void sourceNames() {
            Map<String, Integer> uses = new LinkedHashMap<>();
            Matcher m = Pattern.compile(STR).matcher(src);
            while (m.find()) if (m.group(1).contains("[source]")) uses.merge(m.group(1), 1, Integer::sum);
            for (Map.Entry<String, List<String>> c : a.constants.entrySet()) {
                for (String v : c.getValue()) {
                    if (!uses.containsKey(v)) continue;
                    Matcher r = Pattern.compile("\\b" + Pattern.quote(c.getKey()) + "\\b").matcher(src);
                    int n = -1; // the declaration itself does not count
                    while (r.find()) n++;
                    uses.merge(v, Math.max(n, 0), Integer::sum);
                }
            }
            uses.entrySet().stream().sorted((x, y) -> y.getValue() - x.getValue()).forEach(e -> a.sourceNames.add(e.getKey()));
        }

        void constants() {
            Matcher m = CONST.matcher(src);
            while (m.find()) {
                List<String> l = a.constants.computeIfAbsent(m.group(1), k -> new ArrayList<>());
                if (!l.contains(m.group(2))) l.add(m.group(2));
            }
        }

        /** Methods defined in the rule that wrap an idn call, e.g. getAccountAttributeValue(app, id, attr) -> getRawAccountAttribute. */
        void helpers() {
            Matcher m = METHOD.matcher(src);
            while (m.find()) {
                String name = m.group(1);
                if (List.of("if", "for", "while", "switch", "catch", "synchronized").contains(name)) continue;
                String body = body(m.end() - 1);
                String[] params = m.group(2).trim().isEmpty() ? new String[0] : m.group(2).split(",");
                String last = params.length == 0 ? null : params[params.length - 1].trim().replaceAll(".*\\s", "");
                Matcher c = IDN_CALL.matcher(body);
                String pick = null, pickArgs = null;
                while (c.find()) {
                    if (last != null && c.group(2).matches("(?s).*\\b" + Pattern.quote(last) + "\\s*$")) { pick = c.group(1); pickArgs = c.group(2); break; }
                    if (pick == null) { pick = c.group(1); pickArgs = c.group(2); }
                }
                if (pick != null) {
                    helpers.put(name, pick);
                    Matcher lit = LAST_LITERAL.matcher(pickArgs.trim());
                    if (lit.find()) helperKeys.put(name, lit.group(1)); // the key is fixed inside the helper
                }
            }
        }

        String body(int openBrace) {
            int depth = 0;
            for (int i = openBrace; i < src.length(); i++) {
                char ch = src.charAt(i);
                if (ch == '"') { i = skipString(i); continue; }
                if (ch == '{') depth++;
                else if (ch == '}' && --depth == 0) return src.substring(openBrace, i + 1);
            }
            return src.substring(openBrace);
        }

        int skipString(int i) {
            for (int j = i + 1; j < src.length(); j++) {
                if (src.charAt(j) == '\\') { j++; continue; }
                if (src.charAt(j) == '"') return j;
            }
            return src.length();
        }

        /** Registers every place an input is read, so slots appear in source order even when nothing is compared. */
        void directSources() {
            Matcher m = Pattern.compile("\\b(" + ID + ")\\s*\\.\\s*(getAttribute|getStringAttribute|getStringAttributeValue|getAttributeValue|getBooleanAttributeValue|getIntAttributeValue|getFirstname|getLastname|getEmail|getDisplayName|getName|getIdentity|getNativeIdentity)\\s*\\(([^()]*)\\)").matcher(src);
            while (m.find()) sourceOf(m.group());
            m = IDN_CALL.matcher(src);
            while (m.find()) {
                String wrapped = helperAt(m.start());
                if (wrapped == null) sourceOf(m.group());
                else if (!wrapped.equals(m.group(1))) idnSlot(m.group(1), ""); // e.g. accountExistsByNativeIdentity inside a helper
            }
            for (String h : helpers.keySet()) {
                Matcher c = Pattern.compile("\\b" + Pattern.quote(h) + "\\s*\\(([^;]*?)\\)\\s*[;)&|!=?:,.+<>*/-]").matcher(src);
                while (c.find()) if (!isDeclaration(c.start())) sourceOf(c.group());
            }
            m = Pattern.compile("getAttributeRequest\\s*\\(\\s*" + STR + "\\s*\\)").matcher(src);
            while (m.find()) slot(Kind.ATTR_REQUEST, null, m.group(1));
        }

        /** The idn method wrapped by the helper whose body contains pos, or null when pos is not inside a helper. */
        String helperAt(int pos) {
            Matcher m = METHOD.matcher(src);
            while (m.find()) {
                if (!helpers.containsKey(m.group(1))) continue;
                int start = m.end() - 1, end = start + body(start).length();
                if (pos > start && pos < end) return helpers.get(m.group(1));
            }
            return null;
        }

        boolean isDeclaration(int pos) {
            int lineStart = src.lastIndexOf('\n', pos) + 1;
            String before = src.substring(lineStart, pos).trim();
            return before.matches("(?:public|private|protected|static|\\s)*[\\w.<>\\[\\]]+");
        }

        /** The input an expression reads, or null. Creates the slot on first sight. */
        Slot sourceOf(String expr) {
            String e = expr.trim();
            Matcher m;
            for (String iv : identityVars) {
                m = Pattern.compile("\\b" + Pattern.quote(iv) + "\\s*\\.\\s*get(?:String)?Attribute\\s*\\(\\s*" + STR).matcher(e);
                if (m.find()) return slot(Kind.IDENTITY_ATTR, null, m.group(1));
                m = Pattern.compile("\\b" + Pattern.quote(iv) + "\\s*\\.\\s*get(Firstname|Lastname|Email|DisplayName)\\s*\\(\\s*\\)").matcher(e);
                if (m.find()) return slot(Kind.IDENTITY_FIELD, null, m.group(1).toLowerCase());
            }
            m = Pattern.compile("\\bapplication\\s*\\.\\s*get\\w*AttributeValue\\s*\\(\\s*" + STR).matcher(e);
            if (m.find()) return slot(Kind.APP_ATTR, null, m.group(1));
            m = Pattern.compile("\\baccount\\s*\\.\\s*get(?:String)?Attribute\\s*\\(\\s*" + STR).matcher(e);
            if (m.find()) return slot(Kind.ACCOUNT_ATTR, null, m.group(1));
            m = Pattern.compile("\\baccount\\s*\\.\\s*getIdentity\\s*\\(\\s*\\)").matcher(e);
            if (m.find()) return slot(Kind.ACCOUNT_FIELD, null, "identity");
            m = Pattern.compile("\\blink\\s*\\.\\s*getAttribute\\s*\\(\\s*" + STR).matcher(e);
            if (m.find()) return slot(Kind.LINK_ATTR, null, m.group(1));
            m = Pattern.compile("getAttributeRequest\\s*\\(\\s*" + STR + "\\s*\\)").matcher(e);
            if (m.find()) return slot(Kind.ATTR_REQUEST, null, m.group(1));
            m = IDN_CALL.matcher(e + ";");
            if (m.find()) return idnSlot(m.group(1), m.group(2));
            for (Map.Entry<String, String> h : helpers.entrySet()) {
                m = Pattern.compile("\\b" + Pattern.quote(h.getKey()) + "\\s*\\(([^;]*)\\)").matcher(e);
                if (m.find()) return idnSlot(h.getValue(), helperKeys.containsKey(h.getKey()) ? "\"" + helperKeys.get(h.getKey()) + "\"" : m.group(1));
            }
            return null;
        }

        Slot idnSlot(String method, String args) {
            Matcher lit = LAST_LITERAL.matcher(args.trim());
            Slot s = slot(Kind.IDN, method, lit.find() ? lit.group(1) : "");
            String ml = method.toLowerCase();
            if (ml.startsWith("is") || ml.startsWith("accountexists") || ml.startsWith("exists") || ml.startsWith("has")) s.bool = true;
            if (method.startsWith("count") || method.contains("Count")) s.number = true;
            return s;
        }

        Slot slot(Kind k, String method, String key) {
            Slot s = new Slot(k, method, key);
            return a.slots.computeIfAbsent(s.id(), x -> s);
        }

        void bindings() {
            Matcher m = ASSIGN.matcher(src);
            while (m.find()) if (helperAt(m.start()) == null) bind(m.group(1), m.group(2), false);
            m = FOREACH.matcher(src);
            while (m.find()) bind(m.group(1), m.group(2), true);
        }

        void bind(String var, String rhs, boolean element) {
            Slot s = sourceOf(rhs);
            if (s == null) {
                Matcher w = Pattern.compile("\\b(" + ID + ")\\b").matcher(rhs);
                while (w.find() && s == null) {
                    String id = w.group(1);
                    if (!id.equals(var) && vars.containsKey(id)) s = vars.get(id);
                    else if (!id.equals(var) && a.slots.containsKey(new Slot(Kind.INPUT, null, id).id())) s = a.slots.get(new Slot(Kind.INPUT, null, id).id());
                }
            }
            if (s == null) return;
            if (element) s.list = true;
            vars.putIfAbsent(var, s);
        }

        Slot resolve(String operand) {
            String o = operand.trim().replaceFirst("^\\(\\s*[A-Z][\\w.]*\\s*\\)\\s*", "").replaceAll("(\\s*\\.\\s*(toLowerCase|toUpperCase|trim|toString|name|getValue)\\s*\\(\\s*\\))+$", "");
            if (o.matches(ID)) {
                if (vars.containsKey(o)) return vars.get(o);
                if (a.type != null && (a.type.anyVars || a.type.inputs.contains(o)) && !TRANSFORM_NOT_INPUTS.contains(o) && !isLocal(o))
                    return slot(Kind.INPUT, null, o);
                return null;
            }
            return sourceOf(o);
        }

        /** True when the name is declared or assigned in the rule (so it is not an input). */
        boolean isLocal(String name) {
            return Pattern.compile("\\b(?!return\\b|throw\\b|case\\b|else\\b|new\\b)[\\w.<>\\[\\]]+\\s+" + Pattern.quote(name) + "\\s*[=;:,)]").matcher(src).find()
                    || Pattern.compile("\\b" + Pattern.quote(name) + "\\s*=(?!=)").matcher(src).find();
        }

        void comparisons() {
            Matcher m = LIT_EQ_OP.matcher(src);
            while (m.find()) add(resolve(m.group(2)), m.group(1));
            m = OP_EQ_LIT.matcher(src);
            while (m.find()) {
                String how = m.group(2), lit = m.group(3);
                if (how.startsWith("equals") || (how.equals("contains") && lit.matches("\\w{2,}"))) add(resolve(m.group(1)), lit);
                else addPattern(resolve(m.group(1)), how, lit);
            }
            m = OP_EQEQ_LIT.matcher(src);
            while (m.find()) add(resolve(m.group(1)), m.group(2));
            m = LIT_EQEQ_OP.matcher(src);
            while (m.find()) add(resolve(m.group(2)), m.group(1));
            m = OP_EQ_OP.matcher(src);
            while (m.find()) {
                String l = strip(m.group(1)), r = strip(m.group(2));
                if (a.constants.containsKey(r)) for (String v : a.constants.get(r)) if (!v.isEmpty()) add(resolve(m.group(1)), v);
                if (a.constants.containsKey(l)) for (String v : a.constants.get(l)) if (!v.isEmpty()) add(resolve(m.group(2)), v);
            }
            m = OP_NULL.matcher(src);
            while (m.find()) {
                Slot sl = resolve(m.group(1) != null ? m.group(1) : m.group(2));
                if (sl != null && sl.kind != Kind.ATTR_REQUEST) sl.nullable = true;
            }
            m = OP_EMPTY.matcher(src);
            while (m.find()) {
                Slot s = resolve(m.group(1));
                if (s != null && !s.list && s.kind != Kind.ATTR_REQUEST) s.values.add("");
            }
        }

        String strip(String operand) {
            return operand.trim().replaceFirst("^\\(\\s*[A-Z][\\w.]*\\s*\\)\\s*", "").replaceAll("(\\s*\\.\\s*(toLowerCase|toUpperCase|trim|toString)\\s*\\(\\s*\\))+$", "");
        }

        void add(Slot s, String value) {
            if (s == null) return;
            if (s.kind == Kind.ATTR_REQUEST && List.of("Add", "Remove", "Set", "Retain", "Revoke").contains(value)) return; // operation names
            s.values.add(value.replace("\\\"", "\""));
        }

        /** startsWith("svc_") -> variation value "svc_account"; endsWith(".org") -> "test.org"; contains("@") -> a sample with "@". */
        void addPattern(Slot s, String how, String lit) {
            if (s == null || s.kind == Kind.ATTR_REQUEST) return;
            String l = lit.replace("\\\"", "\"");
            switch (how) {
                case "startsWith" -> { s.patterns.add("starts with \"" + l + "\""); s.patternValues.add(l + "account"); }
                case "endsWith" -> { s.patterns.add("ends with \"" + l + "\""); s.patternValues.add("test" + l); }
                case "contains" -> {
                    s.patterns.add("contains \"" + l + "\"");
                    String sample = CaseBuilder.sample(s).asText();
                    s.patternValues.add(sample.contains(l) ? sample : "a" + l + "b");
                    s.patternValues.add(sample.contains(l) ? sample.replace(l, "") : sample); // one without it
                }
                default -> {
                    s.patterns.add("matches \"" + l + "\"");
                    if (l.matches("\\^?(\\\\\\\\d|\\[0-9\\])[+*]\\$?")) s.patternValues.add("12345"); // digits only
                }
            }
        }

        void typeSpecificInputs() {
            if (a.type == null) return;
            switch (a.type) {
                case IDENTITY_ATTRIBUTE -> { if (src.matches("(?s).*\\boldValue\\b.*")) slot(Kind.INPUT, null, "oldValue"); }
                case MANAGER_CORRELATION -> slot(Kind.INPUT, null, "managerAttributeValue");
                case TRANSFORM -> {
                    if (src.matches("(?s).*\\bvalue\\b.*") && !isLocal("value")) slot(Kind.INPUT, null, "value");
                    Matcher m = VOID_CHECK.matcher(src);
                    while (m.find()) slot(Kind.INPUT, null, m.group(1));
                }
                case BUILD_MAP -> {
                    Matcher m = MAP_GET.matcher(src);
                    while (m.find()) slot(Kind.COLUMN, null, m.group(1));
                }
                case ATTRIBUTE_GENERATOR, ATTRIBUTE_GENERATOR_FROM_TEMPLATE -> {
                    if (src.contains("getName()") && a.ofKind(Kind.IDENTITY_FIELD).stream().noneMatch(s -> s.key.equals("name")))
                        slot(Kind.IDENTITY_FIELD, null, "name");
                }
                default -> { }
            }
        }
    }
}
