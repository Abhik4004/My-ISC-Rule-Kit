package com.iscrulekit;

import bsh.EvalError;
import bsh.Interpreter;
import bsh.Primitive;
import bsh.TargetError;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.log4j.Logger;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Executes a rule's source on the real BeanShell interpreter with mock ISC inputs. */
public final class RuleRunner {
    private RuleRunner() {}

    public record LogLine(String level, String message) {}

    public record ErrorInfo(String type, String message, Integer line, String hint) {}

    public record Outcome(boolean ok, Object result, boolean voidResult, String kind, List<LogLine> logs, List<String> notes,
                          Map<String, Object> changed, ErrorInfo error, long ms) {}

    private static final Pattern IDENT = Pattern.compile("^[A-Za-z_$][\\w$]*$");
    private static final Pattern IMPORT = Pattern.compile("^[ \\t]*import\\s+(static\\s+)?([\\w.]+?)(\\.\\*)?\\s*;", Pattern.MULTILINE);

    /**
     * @param rule      parsed rule (its type decides how ISC-injected inputs are treated)
     * @param inputs    JSON object: every key becomes a variable in the rule; "idn" configures the idn mock
     * @param timeoutMs wall-clock limit for the rule
     */
    public static Outcome run(ParsedRule rule, JsonNode inputs, long timeoutMs) {
        long t0 = System.nanoTime();
        List<String> notes = Collections.synchronizedList(new ArrayList<>());
        List<LogLine> logs = Collections.synchronizedList(new ArrayList<>());
        List<String> inputNames = rule.type == null ? List.of() : rule.type.inputs;

        ClassUnwrapper.Result u = ClassUnwrapper.unwrap(rule.source == null ? "" : rule.source, inputNames);
        String source = resolveImports(u.source(), notes);

        ExecutorService ex = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(null, r, "rule-runner", 64L << 20);
            t.setDaemon(true);
            return t;
        });
        try {
            Callable<Outcome> job = () -> execute(source, inputs, notes, logs, t0);
            Future<Outcome> f = ex.submit(job);
            try {
                return f.get(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                f.cancel(true);
                return failure(new ErrorInfo("Timeout", "Rule did not finish within " + timeoutMs + " ms.", null,
                        "Probably an infinite loop. The runaway thread is abandoned; the process exits when the run ends."), logs, notes, t0);
            } catch (ExecutionException e) {
                return failure(describe(e.getCause() == null ? e : e.getCause(), rule.offset), logs, notes, t0);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return failure(new ErrorInfo("Interrupted", "Run was interrupted.", null, null), logs, notes, t0);
            }
        } finally {
            ex.shutdownNow();
        }
    }

    private static Outcome execute(String source, JsonNode inputs, List<String> notes, List<LogLine> logs, long t0) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(bos, true, StandardCharsets.UTF_8);
        Interpreter in = new Interpreter(null, ps, ps, false);

        in.set("log", new Logger("rule", (lvl, msg) -> logs.add(new LogLine(lvl, msg))));

        JsonNode idnNode = inputs != null && inputs.has("idn") ? inputs.get("idn") : null;
        in.set("__idnHandler", new IdnMock(idnNode, notes));
        in.eval("idnMock() { invoke(name, args) { return __idnHandler.call(name, args); } return this; }");
        in.eval("idn = idnMock();");

        Map<String, Object> values = new LinkedHashMap<>();
        if (inputs != null) {
            for (Iterator<Map.Entry<String, JsonNode>> it = inputs.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> e = it.next();
                String name = e.getKey();
                if (name.equals("idn") || name.equals("log")) continue;
                if (!IDENT.matcher(name).matches()) { notes.add("skipped input '" + name + "': not a valid variable name"); continue; }
                values.put(name, Fixture.forVariable(name, e.getValue()));
            }
        }
        Map<String, String> before = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : values.entrySet()) {
            if (e.getValue() == null) in.eval(e.getKey() + " = null;");
            else in.set(e.getKey(), e.getValue());
            if (isStructured(e.getValue())) before.put(e.getKey(), Json.compact(Plain.of(e.getValue())));
        }

        Object r = in.eval(source);

        boolean isVoid = r == Primitive.VOID;
        if (r instanceof Primitive p) r = isVoid ? null : p.getValue();

        Map<String, Object> changed = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : before.entrySet()) {
            Object now = Plain.of(values.get(e.getKey()));
            if (!Json.compact(now).equals(e.getValue())) changed.put(e.getKey(), now);
        }
        String printed = bos.toString(StandardCharsets.UTF_8);
        for (String line : printed.split("\\R")) if (!line.isBlank()) logs.add(new LogLine("OUT", line));

        Object plain = Plain.of(r);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        return new Outcome(true, plain, isVoid, isVoid ? "void" : Plain.kind(r), snapshot(logs), snapshot(notes), changed, null, ms);
    }

    private static boolean isStructured(Object o) {
        return o instanceof PlainConvertible || o instanceof Map || o instanceof Iterable;
    }

    private static <T> List<T> snapshot(List<T> l) {
        synchronized (l) { return new ArrayList<>(l); }
    }

    private static Outcome failure(ErrorInfo err, List<LogLine> logs, List<String> notes, long t0) {
        return new Outcome(false, null, false, "void", snapshot(logs), snapshot(notes), Map.of(), err, (System.nanoTime() - t0) / 1_000_000);
    }

    // ---------------------------------------------------------------------------------------------------------------
    /** Converts BeanShell / Java exceptions into a readable error with a fix hint where we can tell what went wrong. */
    static ErrorInfo describe(Throwable t, int offset) {
        Integer line = null;
        String type, msg;
        Throwable root = t;
        if (t instanceof TargetError te) {
            root = te.getTarget() == null ? t : te.getTarget();
            type = root.getClass().getSimpleName();
            msg = root.getMessage() == null ? root.toString() : root.getMessage();
            line = te.getErrorLineNumber() > 0 ? te.getErrorLineNumber() + offset : null;
        } else if (t instanceof EvalError ee) {
            type = ee.getClass().getSimpleName();
            msg = String.valueOf(ee.getMessage()).replaceAll("\\s+", " ").trim()
                    .replaceFirst("^Sourced file: inline evaluation of: ``.*?'' : ", "");
            line = ee.getErrorLineNumber() > 0 ? ee.getErrorLineNumber() + offset : null;
        } else {
            type = t.getClass().getSimpleName();
            msg = t.getMessage() == null ? t.toString() : t.getMessage();
        }
        if (root instanceof NullPointerException) {
            if (msg.contains("array length because \"value\" is null"))
                return new ErrorInfo(type, "String.valueOf(null) or a similar call was given null.", line,
                        "In BeanShell, String.valueOf(x) with x == null picks valueOf(char[]) and throws. "
                        + "Check for null first, or use \"\" + x / sailpoint.tools.Util.otos(x).");
            msg = "A method was called on null. " + (msg.startsWith("Cannot") ? msg : "");
        }
        return new ErrorInfo(type, msg.trim(), line, hint(msg));
    }

    private static String hint(String msg) {
        Matcher cnf = Pattern.compile("Command not found: ([\\w$]+)\\(").matcher(msg);
        if (cnf.find()) return "No method named '" + cnf.group(1) + "' exists in the rule (check the spelling and argument count). "
                + "'validate' reports this statically as UNDEF001.";
        Matcher m = Pattern.compile("Method ([\\w$.]+)\\(.*?\\) not found in class\\s*'?([\\w.$]+)'?").matcher(msg);
        if (m.find()) return "The mock class " + m.group(2) + " has no method '" + m.group(1) + "'. Add it under src/main/java/sailpoint/..., "
                + "or put the real SailPoint jars on the classpath.";
        m = Pattern.compile("(?:Class or variable not found|Class: ?|Undefined variable or class name|Undefined variable)[: ]+([\\w.$]+)").matcher(msg);
        if (m.find()) return "'" + m.group(1) + "' is unknown: check the import, or whether the name is missing from the test case 'inputs'.";
        if (msg.contains("Parse error") || msg.contains("Encountered")) return "BeanShell could not parse the rule; run 'validate' for the exact line.";
        return null;
    }

    // ---------------------------------------------------------------------------------------------------------------
    /** Drops imports of classes that are not on the classpath (e.g. real SailPoint classes we do not mock). Lines are kept. */
    private static String resolveImports(String src, List<String> notes) {
        String blank = Scan.blank(Scan.tokenize(src));
        Matcher m = IMPORT.matcher(blank);
        List<int[]> drop = new ArrayList<>();
        while (m.find()) {
            if (m.group(1) != null || m.group(3) != null) continue;
            String name = m.group(2);
            if (!exists(name)) {
                drop.add(new int[]{m.start(), m.end()});
                notes.add("import " + name + " is not available in the tester and was ignored (mock it if the rule really uses it)");
            }
        }
        String out = src;
        for (int[] d : drop) out = Scan.blankRange(out, d[0], d[1]);
        return out;
    }

    private static boolean exists(String name) {
        String n = name;
        for (int i = 0; i < 4; i++) {
            try { Class.forName(n, false, RuleRunner.class.getClassLoader()); return true; } catch (ClassNotFoundException | LinkageError e) { /* try nested form */ }
            int dot = n.lastIndexOf('.');
            if (dot < 0) return false;
            n = n.substring(0, dot) + "$" + n.substring(dot + 1);
        }
        return false;
    }
}
