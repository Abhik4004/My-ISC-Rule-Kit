package com.iscrulekit;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Writes a JUnit 5 test class in the style of SailPoint's Rule Development Kit (RDK) from a cases file: each case
 * becomes a {@code @Test} that builds the inputs (Mockito mocks for Identity / Application / Link / account and
 * {@code idn}, real ProvisioningPlan / AccountRequest / AttributeRequest / Field objects), reads the rule from its XML with
 * {@code RuleXmlUtils.readRuleSourceFromFilePath}, runs it on a BeanShell {@code Interpreter}, and asserts the result.
 * <p>
 * Method signatures (which {@code idn} overload to stub, which getters exist) are read from the RDK's own
 * {@code rule-java-docs} jar, so the generated code compiles against SailPoint's classes.
 */
final class RdkGen {
    private final Api api;
    private final StringBuilder b = new StringBuilder();
    private final Set<String> imports = new java.util.TreeSet<>();
    private boolean needsMap, needsTestLog;

    private RdkGen(Api api) { this.api = api; }

    /** Reflection over the SailPoint stub jar of an RDK checkout (never initialises SailPoint classes). */
    static final class Api implements AutoCloseable {
        private final URLClassLoader cl;

        Api(Path rdk) throws IOException {
            Path jar;
            Path lib = rdk.resolve("lib/sailpoint/rule-java-docs");
            try (Stream<Path> s = Files.walk(lib)) {
                jar = s.filter(p -> p.getFileName().toString().matches("rule-java-docs-[\\d.]+\\.jar")).sorted().reduce((x, y) -> y).orElse(null);
            } catch (IOException e) {
                jar = null;
            }
            if (jar == null) throw new Main.UsageException("no rule-java-docs jar under " + lib + ": is " + rdk + " an RDK checkout?");
            cl = new URLClassLoader(new URL[] {jar.toUri().toURL()}, ClassLoader.getPlatformClassLoader());
        }

        Class<?> cls(String name) {
            try {
                return Class.forName(name, false, cl);
            } catch (ClassNotFoundException | LinkageError e) {
                return null;
            }
        }

        boolean has(String cls, String method, int params) {
            Class<?> c = cls(cls);
            if (c == null) return false;
            try {
                for (Method m : c.getMethods()) if (m.getName().equals(method) && m.getParameterCount() == params) return true;
            } catch (LinkageError e) {
                return false;
            }
            return false;
        }

        /** The idn overload to stub: the one with the most parameters, preferring String/Object parameters. */
        Method idnMethod(String name) {
            Class<?> c = cls("sailpoint.server.IdnRuleUtil");
            if (c == null) return null;
            Method best = null;
            int bestScore = -1;
            for (Method m : c.getMethods()) {
                if (!m.getName().equals(name)) continue;
                int score = m.getParameterCount() * 10;
                for (Class<?> p : m.getParameterTypes()) if (p == String.class || p == Object.class || p.isPrimitive()) score++;
                if (score > bestScore) { best = m; bestScore = score; }
            }
            return best;
        }

        @Override public void close() throws IOException { cl.close(); }
    }

    // ===============================================================================================================
    static String testClass(Path rdk, String className, String ruleXmlRelative, RuleType type, List<TestSuite.Case> cases)
            throws IOException {
        try (Api api = new Api(rdk)) {
            return new RdkGen(api).render(className, ruleXmlRelative, type, cases);
        }
    }

    private String render(String className, String ruleXml, RuleType type, List<TestSuite.Case> cases) {
        imports.addAll(List.of("bsh.EvalError", "bsh.Interpreter", "org.apache.log4j.LogManager", "org.apache.log4j.Logger",
                "org.junit.jupiter.api.Test", "sailpoint.rdk.utils.RuleXmlUtils", "sailpoint.tools.GeneralException",
                "static org.mockito.Mockito.mock", "static org.mockito.Mockito.when"));
        StringBuilder body = new StringBuilder();
        Set<String> used = new HashSet<>();
        int n = 0;
        for (TestSuite.Case c : cases) {
            n++;
            b.setLength(0);
            testMethod(JUnitGen.methodName(n, c.name(), used), c, type);
            body.append(b);
        }
        StringBuilder out = new StringBuilder("package sailpoint;\n\n");
        for (String imp : imports) if (imp.startsWith("static ")) out.append("import ").append(imp).append(";\n");
        out.append('\n');
        for (String imp : imports) if (!imp.startsWith("static ")) out.append("import ").append(imp).append(";\n");
        out.append("\n/**\n");
        out.append(" * Tests for ").append(ruleXml).append(" (").append(type == null ? "rule" : type.label + " rule").append(").\n");
        out.append(" * Generated by 'iscrule rdk' from the rule's test cases. Re-running 'iscrule rdk' rewrites this file;\n");
        out.append(" * to keep manual changes, remove this comment's first line (the kit then leaves the file alone).\n");
        out.append(" *\n * Run with: mvn test -Dtest=").append(className).append("\n */\n");
        out.append("public class ").append(className).append(" {\n");
        out.append("    Logger log = LogManager.getLogger(").append(className).append(".class);\n\n");
        out.append("    private static final String RULE_FILENAME = ").append(JUnitGen.javaString(ruleXml)).append(";\n");
        out.append(body);
        helpers(out);
        out.append("}\n");
        return out.toString();
    }

    static final String MARKER = "Generated by 'iscrule rdk'";

    private void line(String s) { b.append("        ").append(s).append('\n'); }

    private void testMethod(String method, TestSuite.Case c, RuleType type) {
        b.append("\n    /** ").append(c.name().replace("*/", "* /")).append(" */\n");
        b.append("    @Test\n    public void ").append(method).append("() throws GeneralException, EvalError {\n");
        line("Interpreter i = new Interpreter();");
        b.append('\n');
        JsonNode in = c.inputs();
        boolean logCheck = !c.expectLogContains().isEmpty();
        if (logCheck) { needsTestLog = true; line("TestLog testLog = new TestLog(log);"); }

        idn(in.get("idn"));
        Iterator<Map.Entry<String, JsonNode>> it = in.fields();
        Map<String, String> vars = new LinkedHashMap<>();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> f = it.next();
            String k = f.getKey();
            if (k.equals("idn")) continue;
            JsonNode v = f.getValue();
            String var = switch (k) {
                case "identity" -> identity("identity", v);
                case "application" -> application(v);
                case "plan" -> plan(v);
                case "account" -> account(v);
                case "link" -> link(v);
                case "field" -> field(v);
                case "schema" -> schema(v);
                default -> lit(v);
            };
            vars.put(k, var);
        }
        b.append('\n');
        line("i.set(\"log\", " + (logCheck ? "testLog" : "log") + ");");
        if (in.has("idn")) line("i.set(\"idn\", idn);");
        for (Map.Entry<String, String> e : vars.entrySet()) line("i.set(" + JUnitGen.javaString(e.getKey()) + ", " + e.getValue() + ");");
        b.append('\n');
        line("String source = RuleXmlUtils.readRuleSourceFromFilePath(RULE_FILENAME);");

        if (c.expectError()) {
            imports.add("static org.junit.jupiter.api.Assertions.assertThrows");
            line("EvalError error = assertThrows(EvalError.class, () -> i.eval(source));");
            if (c.expectErrorContains() != null && !c.expectErrorContains().isEmpty()) {
                imports.add("static org.junit.jupiter.api.Assertions.assertTrue");
                line("String errorText = error.getClass().getName() + \": \" + error;");
                line("assertTrue(errorText.contains(" + JUnitGen.javaString(c.expectErrorContains()) + "), errorText);");
            }
            b.append("    }\n");
            return;
        }
        boolean returns = type != null && type.returns != null;
        line(returns ? "Object result = i.eval(source);" : "i.eval(source);");
        if (c.hasExpect()) assertResult(c.expect());
        if (c.expectChanged() != null) assertChanged(c.expectChanged(), vars.containsKey("plan"));
        for (String text : c.expectLogContains()) {
            imports.add("static org.junit.jupiter.api.Assertions.assertTrue");
            line("assertTrue(testLog.contains(" + JUnitGen.javaString(text) + "), \"no log line contains \" + " + JUnitGen.javaString(JUnitGen.javaString(text)) + ");");
        }
        if (!c.hasExpect() && c.expectChanged() == null && c.expectLogContains().isEmpty())
            line("// no expected result in this case: it only checks that the rule runs without an error");
        b.append("    }\n");
    }

    // ---------------------------------------------------------------------------------------------------------------
    // inputs
    private void idn(JsonNode idn) {
        if (idn == null) return;
        imports.add("sailpoint.server.IdnRuleUtil");
        line("IdnRuleUtil idn = mock(IdnRuleUtil.class);");
        Iterator<Map.Entry<String, JsonNode>> it = idn.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> f = it.next();
            Method m = api.idnMethod(f.getKey());
            if (m == null) {
                line("// idn." + f.getKey() + "(...) is not a method of IdnRuleUtil, so it cannot be mocked: the rule will fail if it calls it");
                continue;
            }
            JsonNode v = f.getValue();
            if (v.isObject() && (v.has("$byLastArg") || v.has("$default"))) {
                if (v.has("$default")) line("when(idn." + m.getName() + "(" + matchers(m, null) + ")).thenReturn(" + typed(v.get("$default"), m.getReturnType()) + ");");
                JsonNode by = v.path("$byLastArg");
                Iterator<Map.Entry<String, JsonNode>> e = by.fields();
                while (e.hasNext()) {
                    Map.Entry<String, JsonNode> x = e.next();
                    line("when(idn." + m.getName() + "(" + matchers(m, x.getKey()) + ")).thenReturn(" + typed(x.getValue(), m.getReturnType()) + ");");
                }
            } else {
                line("when(idn." + m.getName() + "(" + matchers(m, null) + ")).thenReturn(" + typed(v, m.getReturnType()) + ");");
            }
        }
    }

    private String matchers(Method m, String lastArg) {
        List<String> parts = new ArrayList<>();
        Class<?>[] ps = m.getParameterTypes();
        for (int k = 0; k < ps.length; k++) {
            Class<?> p = ps[k];
            if (k == ps.length - 1 && lastArg != null) {
                imports.add("static org.mockito.ArgumentMatchers.eq");
                parts.add("eq(" + JUnitGen.javaString(lastArg) + ")");
            } else if (p == boolean.class) { imports.add("static org.mockito.ArgumentMatchers.anyBoolean"); parts.add("anyBoolean()"); }
            else if (p == int.class) { imports.add("static org.mockito.ArgumentMatchers.anyInt"); parts.add("anyInt()"); }
            else if (p == long.class) { imports.add("static org.mockito.ArgumentMatchers.anyLong"); parts.add("anyLong()"); }
            else { imports.add("static org.mockito.ArgumentMatchers.any"); parts.add("any()"); }
        }
        return String.join(", ", parts);
    }

    private String typed(JsonNode v, Class<?> type) {
        if (v == null || v.isNull()) return type.isPrimitive() ? (type == boolean.class ? "false" : "0") : "null";
        if (type == boolean.class || type == Boolean.class) return String.valueOf(v.isBoolean() ? v.booleanValue() : "true".equalsIgnoreCase(v.asText()));
        if (type == int.class || type == Integer.class) return String.valueOf(v.isNumber() ? v.intValue() : parseInt(v.asText()));
        if (type == long.class || type == Long.class) return (v.isNumber() ? v.longValue() : parseInt(v.asText())) + "L";
        if (type == String.class) return v == null || v.isNull() ? "null" : JUnitGen.javaString(v.isTextual() ? v.asText() : Json.compact(v));
        return lit(v);
    }

    private static long parseInt(String s) {
        try { return Long.parseLong(s.trim()); } catch (NumberFormatException e) { return 0; }
    }

    private String identity(String var, JsonNode id) {
        if (id == null || id.isNull()) return "null";
        imports.add("sailpoint.object.Identity");
        line("Identity " + var + " = mock(Identity.class);");
        stub(var, "sailpoint.object.Identity", "getId", id.get("id"));
        stub(var, "sailpoint.object.Identity", "getName", id.get("name"));
        for (String[] f : new String[][] {{"firstname", "getFirstname"}, {"lastname", "getLastname"}, {"email", "getEmail"}, {"displayName", "getDisplayName"}})
            stub(var, "sailpoint.object.Identity", f[1], id.get(f[0]));
        attributes(var, "sailpoint.object.Identity", id.path("attributes"), "getAttribute", "getStringAttribute");
        return var;
    }

    private String application(JsonNode app) {
        if (app == null || app.isNull()) return "null";
        imports.add("sailpoint.object.Application");
        line("Application application = mock(Application.class);");
        stub("application", "sailpoint.object.Application", "getName", app.get("name"));
        attributes("application", "sailpoint.object.Application", app.path("attributes"), "getAttributeValue", "getStringAttributeValue");
        return "application";
    }

    private String account(JsonNode acct) {
        if (acct == null || acct.isNull()) return "null";
        imports.add("sailpoint.object.ResourceObject");
        line("ResourceObject account = mock(ResourceObject.class);");
        stub("account", "sailpoint.object.ResourceObject", "getIdentity", acct.get("identity"));
        stub("account", "sailpoint.object.ResourceObject", "getDisplayName", acct.get("displayName"));
        attributes("account", "sailpoint.object.ResourceObject", acct.path("attributes"), "getAttribute", "getStringAttribute");
        return "account";
    }

    private String link(JsonNode l) {
        if (l == null || l.isNull()) return "null";
        imports.add("sailpoint.object.Link");
        line("Link link = mock(Link.class);");
        stub("link", "sailpoint.object.Link", "getApplicationName", l.get("applicationName"));
        stub("link", "sailpoint.object.Link", "getNativeIdentity", l.get("nativeIdentity"));
        attributes("link", "sailpoint.object.Link", l.path("attributes"), "getAttribute", null);
        return "link";
    }

    private String field(JsonNode f) {
        if (f == null || f.isNull()) return "null";
        imports.add("sailpoint.object.Field");
        line("Field field = new Field();");
        if (f.hasNonNull("name")) line("field.setName(" + JUnitGen.javaString(f.get("name").asText()) + ");");
        return "field";
    }

    private String schema(JsonNode s) {
        if (s == null || s.isNull()) return "null";
        imports.add("sailpoint.object.Schema");
        line("Schema schema = new Schema();");
        if (s.hasNonNull("name")) line("schema.setName(" + JUnitGen.javaString(s.get("name").asText()) + ");");
        return "schema";
    }

    private String plan(JsonNode p) {
        if (p == null || p.isNull()) return "null";
        imports.add("sailpoint.object.ProvisioningPlan");
        imports.add("sailpoint.object.ProvisioningPlan.AccountRequest");
        String identity = p.has("identity") ? identity("identity", p.get("identity")) : null;
        line("ProvisioningPlan plan = new ProvisioningPlan();");
        if (identity != null && !identity.equals("null")) line("plan.setIdentity(" + identity + ");");
        int k = 0;
        for (JsonNode r : p.path("accountRequests")) {
            k++;
            String var = "request" + k;
            line("AccountRequest " + var + " = new AccountRequest(" + (r.hasNonNull("operation") ? "AccountRequest.Operation." + r.get("operation").asText() : "null") + ", "
                    + str(r.get("applicationName")) + ", null, " + str(r.get("nativeIdentity")) + ");");
            for (JsonNode ar : r.path("attributeRequests")) {
                imports.add("sailpoint.object.ProvisioningPlan.AttributeRequest");
                line(var + ".add(new AttributeRequest(" + str(ar.get("name")) + ", "
                        + (ar.hasNonNull("operation") ? "ProvisioningPlan.Operation." + ar.get("operation").asText() : "ProvisioningPlan.Operation.Set") + ", "
                        + lit(ar.get("value")) + "));");
            }
            line("plan.add(" + var + ");");
        }
        return "plan";
    }

    private void stub(String var, String cls, String getter, JsonNode v) {
        if (v == null || v.isNull() || !api.has(cls, getter, 0)) return;
        line("when(" + var + "." + getter + "()).thenReturn(" + JUnitGen.javaString(v.asText()) + ");");
    }

    private void attributes(String var, String cls, JsonNode attrs, String objectGetter, String stringGetter) {
        Iterator<Map.Entry<String, JsonNode>> it = attrs.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> f = it.next();
            String key = JUnitGen.javaString(f.getKey());
            if (objectGetter != null && api.has(cls, objectGetter, 1)) line("when(" + var + "." + objectGetter + "(" + key + ")).thenReturn(" + lit(f.getValue()) + ");");
            if (stringGetter != null && f.getValue().isTextual() && api.has(cls, stringGetter, 1))
                line("when(" + var + "." + stringGetter + "(" + key + ")).thenReturn(" + lit(f.getValue()) + ");");
        }
    }

    private static String str(JsonNode v) { return v == null || v.isNull() ? "null" : JUnitGen.javaString(v.asText()); }

    /** Java expression for a JSON value: String, int/long/double, boolean, null, a mutable List, or map(...). */
    private String lit(JsonNode v) {
        if (v == null || v.isNull() || v.isMissingNode()) return "null";
        if (v.isTextual()) return JUnitGen.javaString(v.asText());
        if (v.isBoolean()) return String.valueOf(v.booleanValue());
        if (v.isIntegralNumber()) return v.canConvertToInt() ? String.valueOf(v.intValue()) : v.longValue() + "L";
        if (v.isNumber()) return String.valueOf(v.doubleValue());
        if (v.isArray()) {
            imports.add("java.util.ArrayList");
            imports.add("java.util.Arrays");
            List<String> parts = new ArrayList<>();
            v.forEach(x -> parts.add(lit(x)));
            return "new ArrayList<>(Arrays.asList(" + String.join(", ", parts) + "))";
        }
        needsMap = true;
        List<String> parts = new ArrayList<>();
        v.fields().forEachRemaining(f -> parts.add(JUnitGen.javaString(f.getKey()) + ", " + lit(f.getValue())));
        return "map(" + String.join(", ", parts) + ")";
    }

    // ---------------------------------------------------------------------------------------------------------------
    // assertions
    private void assertResult(JsonNode exp) {
        if (exp == null || exp.isNull()) {
            imports.add("static org.junit.jupiter.api.Assertions.assertNull");
            line("assertNull(result);");
            return;
        }
        imports.add("static org.junit.jupiter.api.Assertions.assertEquals");
        line("assertEquals(" + lit(exp) + ", result);");
    }

    private void assertChanged(JsonNode exp, boolean hasPlan) {
        JsonNode reqs = exp.path("plan").path("accountRequests");
        if (!hasPlan || !reqs.isArray()) {
            line("// expectChanged only covers the plan in RDK tests; this case expected: " + Json.compact(exp).replace("*/", "* /"));
            return;
        }
        imports.add("static org.junit.jupiter.api.Assertions.assertEquals");
        imports.add("java.util.List");
        imports.add("sailpoint.object.ProvisioningPlan.AttributeRequest");
        line("assertEquals(" + reqs.size() + ", plan.getAccountRequests().size());");
        for (int k = 0; k < reqs.size(); k++) {
            JsonNode r = reqs.get(k);
            String rv = "result" + (k + 1);
            line("AccountRequest " + rv + " = plan.getAccountRequests().get(" + k + ");");
            if (r.hasNonNull("operation")) line("assertEquals(AccountRequest.Operation." + r.get("operation").asText() + ", " + rv + ".getOperation());");
            if (!r.has("attributeRequests")) continue;
            JsonNode ars = r.get("attributeRequests");
            String lv = "attrs" + (k + 1);
            line("List<AttributeRequest> " + lv + " = attributeRequests(" + rv + ");");
            line("assertEquals(" + ars.size() + ", " + lv + ".size(), \"number of attribute requests\");");
            for (int a = 0; a < ars.size(); a++) {
                JsonNode ar = ars.get(a);
                String at = lv + ".get(" + a + ")";
                if (ar.hasNonNull("name")) line("assertEquals(" + str(ar.get("name")) + ", " + at + ".getName());");
                if (ar.hasNonNull("operation")) line("assertEquals(ProvisioningPlan.Operation." + ar.get("operation").asText() + ", " + at + ".getOperation());");
                if (ar.has("value")) {
                    if (ar.get("value").isNull()) {
                        imports.add("static org.junit.jupiter.api.Assertions.assertNull");
                        line("assertNull(" + at + ".getValue());");
                    } else line("assertEquals(" + lit(ar.get("value")) + ", " + at + ".getValue());");
                }
            }
        }
    }

    private void helpers(StringBuilder out) {
        if (imports.contains("java.util.List") && imports.contains("sailpoint.object.ProvisioningPlan.AttributeRequest")) {
            out.append("\n    private static List<AttributeRequest> attributeRequests(AccountRequest request) {\n");
            out.append("        List<AttributeRequest> list = request.getAttributeRequests();\n");
            out.append("        return list == null ? new java.util.ArrayList<>() : list;\n    }\n");
        }
        if (needsMap) {
            out.append("\n    private static java.util.Map<String, Object> map(Object... keysAndValues) {\n");
            out.append("        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();\n");
            out.append("        for (int k = 0; k < keysAndValues.length; k += 2) m.put((String) keysAndValues[k], keysAndValues[k + 1]);\n");
            out.append("        return m;\n    }\n");
        }
        if (needsTestLog) {
            out.append("""

                        /** Passes log calls to the real logger and remembers them, so a test can check what was logged. */
                        public static class TestLog {
                            private final Logger log;
                            private final java.util.List<String> lines = new java.util.ArrayList<>();
                            TestLog(Logger log) { this.log = log; }
                            public void trace(Object m) { lines.add(String.valueOf(m)); log.trace(m); }
                            public void debug(Object m) { lines.add(String.valueOf(m)); log.debug(m); }
                            public void info(Object m) { lines.add(String.valueOf(m)); log.info(m); }
                            public void warn(Object m) { lines.add(String.valueOf(m)); log.warn(m); }
                            public void error(Object m) { lines.add(String.valueOf(m)); log.error(m); }
                            public void error(Object m, Throwable t) { lines.add(String.valueOf(m)); log.error(m, t); }
                            public boolean isDebugEnabled() { return true; }
                            public boolean isTraceEnabled() { return true; }
                            boolean contains(String text) { return lines.stream().anyMatch(l -> l.contains(text)); }
                        }
                    """);
        }
    }
}
