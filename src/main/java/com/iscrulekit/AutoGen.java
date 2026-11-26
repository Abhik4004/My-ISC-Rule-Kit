package com.iscrulekit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iscrulekit.CaseBuilder.AttrReq;
import com.iscrulekit.CaseBuilder.Choice;
import com.iscrulekit.RuleAnalyzer.Analysis;
import com.iscrulekit.RuleAnalyzer.Kind;
import com.iscrulekit.RuleAnalyzer.Slot;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Coverage-guided test generation. Every value the rule checks becomes a "dimension" with a few options (the values it
 * compares against, plus a typical default). The search runs:
 * <ol>
 *   <li>the baseline (every dimension at its default),</li>
 *   <li>one change at a time from the baseline,</li>
 *   <li>random combinations grown from cases already kept (seeded, so re-running gives the same cases),</li>
 * </ol>
 * keeping a case only when it reaches a block of the rule, or produces a result, that no kept case did. The kept set is
 * then trimmed so no case is redundant. Each case records what the rule did as its expectation.
 */
final class AutoGen {
    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    /** A generated case: inputs, what it covers, its outcome, and a readable name. */
    record Found(Choice choice, ObjectNode inputs, Set<String> keys, RuleRunner.Outcome outcome, String name, List<String> covers) {}

    record Result(List<Found> cases, List<Coverage.Block> blocks, Set<Integer> covered, List<Coverage.ValuePath> paths, Set<String> pathsCovered, int runs) {}

    /** One thing that can vary: a slot value, the account operation, or one attribute change of the plan. */
    private record Dim(String label, List<Object> options, int dflt) {}

    private final ParsedRule rule;
    private final Analysis a;
    private final Coverage.Instrumented inst;
    private final long timeoutMs;
    private final List<Dim> dims = new ArrayList<>();
    private final List<Slot> dimSlots = new ArrayList<>(); // slot per dim, null for plan dims
    private final Choice template = new Choice();
    private int runs;

    private AutoGen(ParsedRule rule, long timeoutMs) {
        this.rule = rule;
        this.a = RuleAnalyzer.analyze(rule);
        this.inst = Coverage.instrument(rule);
        this.timeoutMs = timeoutMs;
        buildDims();
    }

    // ===============================================================================================================
    /**
     * @param seeds existing cases (inputs) whose coverage counts as already reached, so only new blocks get new cases
     * @param max   most cases to return
     */
    static Result generate(ParsedRule rule, List<ObjectNode> seeds, int max, long timeoutMs, long budgetMs) {
        return new AutoGen(rule, timeoutMs).search(seeds, max, budgetMs);
    }

    /** Coverage of a set of existing cases (block ids entered). */
    static Set<Integer> coverageOf(ParsedRule rule, List<ObjectNode> inputs, long timeoutMs) {
        AutoGen g = new AutoGen(rule, timeoutMs);
        Set<Integer> s = new LinkedHashSet<>();
        for (ObjectNode in : inputs) s.addAll(Coverage.hits(g.run(in)));
        return s;
    }

    static List<Coverage.Block> blocks(ParsedRule rule) { return Coverage.instrument(rule).blocks(); }

    // ===============================================================================================================
    private void buildDims() {
        if (!a.sourceNames.isEmpty()) template.plan.applicationName = a.sourceNames.iterator().next();
        if (a.type == RuleType.BEFORE_PROVISIONING) {
            List<Object> ops = new ArrayList<>(List.of("Modify"));
            for (String op : a.operations) if (!ops.contains(op)) ops.add(op);
            boolean hasAttrSlots = !a.ofKind(Kind.ATTR_REQUEST).isEmpty();
            int d = 0; // Modify, unless the rule never looks at attribute changes and never checks for Modify
            if (!hasAttrSlots && !a.operations.isEmpty() && !a.operations.contains("Modify")) d = ops.indexOf(a.operations.iterator().next());
            dims.add(new Dim("operation", ops, d));
            dimSlots.add(null);
            List<String> attrOps = new ArrayList<>();
            for (String op : List.of("Add", "Remove", "Set")) if (a.attrOperations.contains(op)) attrOps.add(op);
            if (attrOps.isEmpty()) attrOps.add("Add");
            for (Slot s : a.ofKind(Kind.ATTR_REQUEST)) {
                List<Object> opts = new ArrayList<>();
                List<String> vals = new ArrayList<>(s.values);
                if (vals.isEmpty()) vals.add("test");
                for (String op : attrOps) for (String v : vals) opts.add(new AttrReq(s.key, op, F.textNode(v)));
                opts.add(new AttrReq(s.key, attrOps.get(0), listOf(vals.get(0), "CN=Another Group,OU=Groups,DC=example,DC=org")));
                opts.add("none");
                dims.add(new Dim(s.key, opts, 0));
                dimSlots.add(null);
            }
        }
        for (Slot s : a.important()) {
            if (s.kind == Kind.ATTR_REQUEST) continue;
            List<Object> opts = new ArrayList<>();
            JsonNode d = CaseBuilder.defaultFor(s);
            opts.add(d);
            List<String> raw = new ArrayList<>();
            if (s.bool) raw.addAll(List.of("true", "false"));
            if (s.number) raw.add("1"); // e.g. "already taken" for uniqueness counts
            raw.addAll(s.values);
            raw.addAll(s.patternValues);
            for (String v : raw) {
                JsonNode jv = CaseBuilder.typed(s, v);
                if (!opts.contains(jv)) opts.add(jv);
            }
            if (!s.bool && !s.number && !s.list && !s.values.isEmpty()) { // a value the rule does not check for (reaches the final else)
                JsonNode other = F.textNode("other");
                if (!opts.contains(other)) opts.add(other);
            }
            if (s.list) {
                if (!s.values.isEmpty()) { // the same value as a single string instead of a list
                    JsonNode single = F.textNode(s.values.iterator().next());
                    if (!opts.contains(single)) opts.add(single);
                }
                ArrayNode empty = F.arrayNode();
                if (!opts.contains(empty)) opts.add(empty);
                if (s.values.isEmpty()) { // nothing known about the items: try one plain item, as a list and as text
                    JsonNode one = listOf("test1");
                    if (!opts.contains(one)) opts.add(one);
                    JsonNode text = F.textNode("test1,test2");
                    if (!opts.contains(text)) opts.add(text);
                }
                if (!s.values.isEmpty()) {
                    JsonNode withOther = listOf(s.values.iterator().next(), "CN=Another Group,OU=Groups,DC=example,DC=org");
                    if (!opts.contains(withOther)) opts.add(withOther);
                }
            }
            if ((s.kind == Kind.IDENTITY_ATTR || s.nullable) && !opts.contains(F.nullNode())) opts.add(F.nullNode()); // missing / null
            dims.add(new Dim(label(s), opts, 0));
            dimSlots.add(s);
        }
    }

    private static ArrayNode listOf(String... v) {
        ArrayNode n = F.arrayNode();
        for (String x : v) n.add(x);
        return n;
    }

    private static String label(Slot s) {
        return switch (s.kind) {
            case IDN -> s.key.isEmpty() ? s.method : s.key;
            default -> s.key;
        };
    }

    private Choice choice(int[] assign) {
        Choice c = new Choice();
        c.plan.applicationName = template.plan.applicationName;
        c.fieldName = template.fieldName;
        for (int k = 0; k < dims.size(); k++) {
            Object o = dims.get(k).options().get(assign[k]);
            Slot s = dimSlots.get(k);
            if (s != null) { c.values.put(s.id(), (JsonNode) o); continue; }
            if (dims.get(k).label().equals("operation")) c.plan.operation = (String) o;
            else if (o instanceof AttrReq r) c.plan.attrs.add(r);
        }
        return c;
    }

    private RuleRunner.Outcome run(ObjectNode inputs) {
        runs++;
        return TestSuite.runCase(inst.rule(), new TestSuite.Case("auto", inputs, null, false, false, null, null, List.of()), timeoutMs, false).outcome();
    }

    /** What makes a run distinct: blocks entered, plus a short form of the result. */
    private Set<String> keys(RuleRunner.Outcome o) {
        Set<String> k = new LinkedHashSet<>();
        Set<Integer> hit = Coverage.hits(o);
        for (int h : hit) k.add("b" + h);
        for (Coverage.ValuePath vp : inst.paths()) if (hit.contains(vp.def()) && hit.contains(vp.use())) k.add(vp.key());
        k.add("r:" + resultSignature(o));
        return k;
    }

    /** Final operation(s) and the set of attributes changed, or the error type, or the return value. */
    private String resultSignature(RuleRunner.Outcome o) {
        if (!o.ok()) return "error " + o.error().type();
        Object plan = o.changed().get("plan");
        if (plan != null) {
            StringBuilder b = new StringBuilder();
            for (JsonNode r : Json.toNode(plan).path("accountRequests")) {
                java.util.TreeSet<String> names = new java.util.TreeSet<>();
                for (JsonNode ar : r.path("attributeRequests")) names.add(ar.path("name").asText());
                b.append(r.path("operation").asText()).append(names).append(';');
            }
            return b.toString();
        }
        return o.voidResult() ? "void" : Json.compact(Json.toNode(o.result()));
    }

    // ===============================================================================================================
    private boolean blocksOnly;

    private Result search(List<ObjectNode> seeds, int max, long budgetMs) {
        long deadline = System.currentTimeMillis() + budgetMs;
        blocksOnly = !seeds.isEmpty();
        Set<String> covered = new LinkedHashSet<>();
        for (ObjectNode s : seeds) covered.addAll(keys(run(s)));

        List<int[]> keptAssign = new ArrayList<>();
        List<Found> kept = new ArrayList<>();
        int[] base = new int[dims.size()];
        for (int k = 0; k < dims.size(); k++) base[k] = dims.get(k).dflt();
        Choice baseChoice = choice(base);

        List<int[]> queue = new ArrayList<>();
        queue.add(base);
        for (int k = 0; k < dims.size(); k++)
            for (int o = 0; o < dims.get(k).options().size(); o++) {
                if (o == base[k]) continue;
                int[] v = base.clone();
                v[k] = o;
                queue.add(v);
            }
        for (int[] v : queue) {
            if (System.currentTimeMillis() > deadline) break;
            tryCase(v, covered, keptAssign, kept, baseChoice, v == base && seeds.isEmpty());
        }
        // random combinations grown from kept cases (or the baseline)
        Random rnd = new Random(42);
        int stale = 0;
        while (System.currentTimeMillis() < deadline && stale < 400 && !dims.isEmpty()) {
            int[] parent = keptAssign.isEmpty() || rnd.nextInt(4) == 0 ? base : keptAssign.get(rnd.nextInt(keptAssign.size()));
            int[] v = parent.clone();
            int changes = 1 + rnd.nextInt(Math.min(3, dims.size()));
            for (int c = 0; c < changes; c++) {
                int k = rnd.nextInt(dims.size());
                v[k] = rnd.nextInt(dims.get(k).options().size());
            }
            if (tryCase(v, covered, keptAssign, kept, baseChoice, false)) stale = 0;
            else stale++;
        }
        crossover(covered, keptAssign, kept, baseChoice, deadline + budgetMs / 2);
        List<Found> trimmed = trim(kept, seeds.isEmpty());
        if (trimmed.size() > max) trimmed = trimmed.subList(0, max);
        Set<Integer> blocksHit = new LinkedHashSet<>();
        Set<String> pathsHit = new LinkedHashSet<>();
        for (String k : covered) {
            if (k.startsWith("b")) blocksHit.add(Integer.parseInt(k.substring(1)));
            if (k.startsWith("d")) pathsHit.add(k);
        }
        return new Result(trimmed, inst.blocks(), blocksHit, inst.paths(), pathsHit, runs);
    }

    /**
     * Aims at value paths still unchecked: takes a kept case that reaches the use and copies, one dimension at a time
     * (then all at once), the settings of a kept case that reaches the definition, e.g. "Enable, affiliate -> Temp OU"
     * plus "environment = Stage".
     */
    private void crossover(Set<String> covered, List<int[]> keptAssign, List<Found> kept, Choice base, long deadline) {
        for (Coverage.ValuePath vp : inst.paths()) {
            if (covered.contains(vp.key())) continue;
            String d = "b" + vp.def(), u = "b" + vp.use();
            int n = kept.size();
            int startRuns = runs;
            List<int[]> nearMisses = new ArrayList<>(); // reach the setting but not yet the use
            outer:
            for (int i = 0; i < n; i++) {
                if (!kept.get(i).keys().contains(u)) continue;
                for (int j = 0; j < n; j++) {
                    if (!kept.get(j).keys().contains(d)) continue;
                    int[] from = keptAssign.get(i), donor = keptAssign.get(j);
                    for (int k = 0; k < from.length; k++) {
                        if (from[k] == donor[k]) continue;
                        if (System.currentTimeMillis() > deadline || runs - startRuns > 150) break outer;
                        int[] v = from.clone();
                        v[k] = donor[k];
                        tryCase(v, covered, keptAssign, kept, base, false);
                        if (covered.contains(vp.key())) break outer;
                        if (lastKeys.contains(d) && nearMisses.size() < 5) nearMisses.add(v);
                    }
                }
            }
            // one more change around each near miss, e.g. environment = Production *and* the Production group DN
            near:
            for (int[] v : nearMisses) {
                for (int k = 0; k < v.length; k++)
                    for (int o = 0; o < dims.get(k).options().size(); o++) {
                        if (covered.contains(vp.key())) break near;
                        if (o == v[k]) continue;
                        if (System.currentTimeMillis() > deadline || runs - startRuns > 400) break near;
                        int[] w = v.clone();
                        w[k] = o;
                        tryCase(w, covered, keptAssign, kept, base, false);
                    }
            }
        }
    }

    private Set<String> lastKeys = Set.of();

    private boolean tryCase(int[] v, Set<String> covered, List<int[]> keptAssign, List<Found> kept, Choice base, boolean always) {
        Choice c = choice(v);
        ObjectNode inputs = CaseBuilder.inputs(a, c);
        RuleRunner.Outcome o = run(inputs);
        Set<String> ks = keys(o);
        lastKeys = ks;
        Set<String> fresh = new LinkedHashSet<>(ks);
        fresh.removeAll(covered);
        if (blocksOnly) fresh.removeIf(k -> k.startsWith("r:")); // updating existing cases: only new code (or a new value path) earns a new case
        if (fresh.isEmpty() && !always) return false;
        covered.addAll(ks);
        keptAssign.add(v);
        kept.add(new Found(c, inputs, ks, o, name(c, base, o, always), covers(ks)));
        return true;
    }

    /** Smallest set of kept cases with the same coverage: greedy set cover, baseline first (when there are no seeds). */
    private static List<Found> trim(List<Found> kept, boolean keepBaseline) {
        List<Found> pool = new ArrayList<>(kept), out = new ArrayList<>();
        Set<String> need = new LinkedHashSet<>();
        for (Found f : kept) need.addAll(f.keys());
        if (keepBaseline && !pool.isEmpty()) { out.add(pool.remove(0)); need.removeAll(out.get(0).keys()); }
        while (!need.isEmpty() && !pool.isEmpty()) {
            Found best = null;
            int bestGain = 0;
            for (Found f : pool) {
                int gain = 0;
                for (String k : f.keys()) if (need.contains(k)) gain++;
                if (gain > bestGain) { best = f; bestGain = gain; }
            }
            if (best == null) break;
            out.add(best);
            pool.remove(best);
            need.removeAll(best.keys());
        }
        List<Found> ordered = new ArrayList<>(); // keep discovery order: easier to read
        for (Found f : kept) if (out.contains(f)) ordered.add(f);
        return ordered;
    }

    private List<String> covers(Set<String> ks) {
        Map<Integer, Coverage.Block> byId = new LinkedHashMap<>();
        for (Coverage.Block b : inst.blocks()) byId.put(b.id(), b);
        List<String> l = new ArrayList<>();
        for (String k : ks) if (k.startsWith("b")) {
            Coverage.Block b = byId.get(Integer.parseInt(k.substring(1)));
            if (b != null) l.add("line " + b.line() + ": " + b.what());
        }
        for (Coverage.ValuePath vp : inst.paths()) if (ks.contains(vp.key()))
            l.add(vp.var() + " set on line " + vp.defLine() + ", used on line " + vp.useLine());
        return l;
    }

    // ===============================================================================================================
    private String name(Choice c, Choice base, RuleRunner.Outcome o, boolean isBase) {
        String what = isBase ? "typical values" : CaseWizard.describe(a, c, base, false);
        String req = requestText(c, base, isBase);
        String input = String.join(", ", Arrays.stream(new String[] {req, what}).filter(x -> x != null && !x.isEmpty()).toList());
        if (input.isEmpty()) input = "typical values";
        return input + "  ->  " + outcomeText(o);
    }

    private String requestText(Choice c, Choice base, boolean isBase) {
        if (a.type != RuleType.BEFORE_PROVISIONING) return "";
        if (!isBase && c.plan.operation.equals(base.plan.operation) && c.plan.attrs.equals(base.plan.attrs)) return "";
        StringBuilder b = new StringBuilder(c.plan.operation);
        for (AttrReq r : c.plan.attrs) b.append(" ").append(r.name()).append(" ").append(r.op()).append(" ").append(CaseBuilder.brief(r.value()));
        return b.toString();
    }

    static String outcomeText(RuleRunner.Outcome o) {
        if (!o.ok()) return "throws " + o.error().type();
        Object plan = o.changed().get("plan");
        if (plan != null) {
            List<String> parts = new ArrayList<>();
            for (JsonNode r : Json.toNode(plan).path("accountRequests")) {
                List<String> attrs = new ArrayList<>();
                for (JsonNode ar : r.path("attributeRequests")) attrs.add(ar.path("name").asText());
                parts.add(r.path("operation").asText() + (attrs.isEmpty() ? "" : " with " + String.join(", ", new LinkedHashSet<>(attrs))));
            }
            return String.join("; ", parts);
        }
        if (o.voidResult()) return "returns nothing";
        return "returns " + CaseBuilder.brief(Json.toNode(o.result()));
    }

    /** The case as JSON: name, auto marker, what it covers, inputs, and the recorded result. */
    static ObjectNode toJson(Found f, RuleType type) {
        ObjectNode c = F.objectNode();
        c.put("name", f.name());
        c.put("auto", true);
        ArrayNode cov = c.putArray("covers");
        f.covers().forEach(cov::add);
        c.set("inputs", f.inputs());
        RuleRunner.Outcome clean = new RuleRunner.Outcome(f.outcome().ok(), f.outcome().result(), f.outcome().voidResult(), f.outcome().kind(),
                Coverage.realLogs(f.outcome()), f.outcome().notes(), f.outcome().changed(), f.outcome().error(), f.outcome().ms());
        CaseBuilder.record(c, type, clean);
        return c;
    }
}
