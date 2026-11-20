package com.iscrulekit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iscrulekit.RuleAnalyzer.Analysis;
import com.iscrulekit.RuleAnalyzer.Kind;
import com.iscrulekit.RuleAnalyzer.Slot;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds the "inputs" of a test case from chosen slot values, and records what a run did as expectations. */
final class CaseBuilder {
    private CaseBuilder() {}

    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    /** One attribute request in a BeforeProvisioning plan. */
    record AttrReq(String name, String op, JsonNode value) {}

    /** The account request of a BeforeProvisioning plan. */
    static final class Plan {
        String operation = "Modify";
        String applicationName = "Active Directory [source]";
        String nativeIdentity = "CN=Test User,OU=Users,DC=example,DC=org";
        final List<AttrReq> attrs = new ArrayList<>();
    }

    /** Values chosen for the analysis slots (slot id -> JSON value), plus the plan for BeforeProvisioning. */
    static final class Choice {
        final Map<String, JsonNode> values = new LinkedHashMap<>();
        final Plan plan = new Plan();
        String fieldName = "attribute";
    }

    // ---------------------------------------------------------------------------------------------------------------
    static ObjectNode inputs(Analysis a, Choice c) {
        ObjectNode in = F.objectNode();
        RuleType t = a.type == null ? RuleType.TRANSFORM : a.type;
        switch (t) {
            case BEFORE_PROVISIONING -> {
                ObjectNode plan = in.putObject("plan");
                plan.set("identity", identity(a, c));
                ObjectNode req = plan.putArray("accountRequests").addObject();
                req.put("applicationName", c.plan.applicationName);
                req.put("nativeIdentity", c.plan.nativeIdentity);
                req.put("operation", c.plan.operation);
                ArrayNode ars = req.putArray("attributeRequests");
                for (AttrReq r : c.plan.attrs) {
                    ObjectNode o = ars.addObject();
                    o.put("name", r.name());
                    o.put("operation", r.op());
                    o.set("value", r.value());
                }
                in.set("application", application(a, c));
                in.putNull("integration");
            }
            case ATTRIBUTE_GENERATOR -> {
                in.set("identity", identity(a, c));
                in.set("application", application(a, c));
                in.putObject("field").put("name", c.fieldName);
            }
            case ATTRIBUTE_GENERATOR_FROM_TEMPLATE -> {
                in.set("identity", identity(a, c));
                in.putObject("field").put("name", c.fieldName);
            }
            case CORRELATION -> {
                ObjectNode acct = in.putObject("account");
                JsonNode id = c.values.get(slotId(Kind.ACCOUNT_FIELD, "identity"));
                acct.put("identity", id == null || id.isNull() ? "test-account-1" : id.asText());
                acct.set("attributes", attrs(a, c, Kind.ACCOUNT_ATTR));
                in.set("application", application(a, c));
            }
            case MANAGER_CORRELATION -> {
                ObjectNode link = in.putObject("link");
                link.put("applicationName", c.plan.applicationName);
                link.put("nativeIdentity", "test-account-1");
                link.set("attributes", attrs(a, c, Kind.LINK_ATTR));
                in.set("managerAttributeValue", c.values.getOrDefault(slotId(Kind.INPUT, "managerAttributeValue"), F.nullNode()));
            }
            case IDENTITY_ATTRIBUTE -> {
                in.set("identity", identity(a, c));
                in.set("oldValue", c.values.getOrDefault(slotId(Kind.INPUT, "oldValue"), F.nullNode()));
            }
            case BUILD_MAP -> {
                ArrayNode cols = in.putArray("cols"), rec = in.putArray("record");
                for (Slot s : a.ofKind(Kind.COLUMN)) {
                    cols.add(s.key);
                    JsonNode v = c.values.get(s.id());
                    rec.add(v == null || v.isNull() ? "" : v.asText());
                }
                in.set("application", application(a, c));
                in.putObject("schema").put("name", "account");
            }
            case TRANSFORM -> {
                for (Slot s : a.ofKind(Kind.INPUT)) {
                    JsonNode v = c.values.get(s.id());
                    if (v != null) in.set(s.key, v);
                }
            }
        }
        if (t != RuleType.BUILD_MAP) in.set("idn", idn(a, c));
        return in;
    }

    static String slotId(Kind k, String key) { return new Slot(k, null, key).id(); }

    private static ObjectNode identity(Analysis a, Choice c) {
        ObjectNode id = F.objectNode();
        id.put("id", "2c9180000000000000000000000test");
        JsonNode name = c.values.get(slotId(Kind.IDENTITY_FIELD, "name"));
        id.put("name", name == null || name.isNull() ? "testuser" : name.asText());
        for (Slot s : a.ofKind(Kind.IDENTITY_FIELD)) {
            if (s.key.equals("name")) continue;
            JsonNode v = c.values.get(s.id());
            if (v != null && !v.isNull()) id.set(s.key, v);
        }
        id.set("attributes", attrs(a, c, Kind.IDENTITY_ATTR));
        return id;
    }

    private static ObjectNode application(Analysis a, Choice c) {
        ObjectNode app = F.objectNode();
        app.put("name", c.plan.applicationName);
        app.set("attributes", attrs(a, c, Kind.APP_ATTR));
        return app;
    }

    private static ObjectNode attrs(Analysis a, Choice c, Kind k) {
        ObjectNode o = F.objectNode();
        for (Slot s : a.ofKind(k)) {
            JsonNode v = c.values.get(s.id());
            if (v != null && !v.isNull()) o.set(s.key, v);
        }
        return o;
    }

    /** "idn": { method: value } or { method: { "$byLastArg": {key: value}, "$default": ... } }. */
    private static ObjectNode idn(Analysis a, Choice c) {
        ObjectNode idn = F.objectNode();
        Map<String, List<Slot>> byMethod = new LinkedHashMap<>();
        for (Slot s : a.ofKind(Kind.IDN)) byMethod.computeIfAbsent(s.method, k -> new ArrayList<>()).add(s);
        for (Map.Entry<String, List<Slot>> e : byMethod.entrySet()) {
            Slot first = e.getValue().get(0);
            JsonNode dflt = first.bool ? F.booleanNode(true) : first.number ? F.numberNode(0) : F.textNode("");
            boolean keyed = e.getValue().stream().anyMatch(s -> !s.key.isEmpty());
            if (!keyed) {
                JsonNode v = c.values.get(first.id());
                idn.set(e.getKey(), v == null ? dflt : v);
                continue;
            }
            ObjectNode m = idn.putObject(e.getKey());
            ObjectNode by = m.putObject("$byLastArg");
            for (Slot s : e.getValue()) {
                JsonNode v = c.values.get(s.id());
                if (v != null && !s.key.isEmpty()) by.set(s.key, v);
            }
            JsonNode unkeyed = e.getValue().stream().filter(s -> s.key.isEmpty()).map(s -> c.values.get(s.id())).filter(v -> v != null).findFirst().orElse(null);
            m.set("$default", unkeyed != null ? unkeyed : dflt);
        }
        return idn;
    }

    // ---------------------------------------------------------------------------------------------------------------
    /** The value a slot gets when nobody chooses: "false" beats "true", "active" beats others, else the first candidate. */
    static JsonNode defaultFor(Slot s) {
        if (s.bool) return F.booleanNode(true);
        if (s.number) return F.numberNode(0);
        List<String> v = new ArrayList<>(s.values);
        if (s.kind == Kind.IDENTITY_FIELD || s.kind == Kind.ACCOUNT_ATTR || s.kind == Kind.LINK_ATTR || s.kind == Kind.INPUT || s.kind == Kind.COLUMN) v.remove("");
        String pick = v.contains("false") ? "false" : v.contains("active") ? "active" : v.isEmpty() ? null : v.get(0);
        if (pick == null) return sample(s);
        return typed(s, pick);
    }

    /** A plausible value for a slot nobody compares against anything. */
    static JsonNode sample(Slot s) {
        String k = s.key.toLowerCase();
        String v = switch (k) {
            case "firstname" -> "Tyler";
            case "lastname" -> "Smith";
            case "email" -> "tyler.smith@example.org";
            case "displayname" -> "Tyler Smith";
            case "name", "identity" -> "tsmith";
            default -> k.contains("mail") ? "tyler.smith@example.org" : k.contains("date") ? "2026-01-15"
                    : k.contains("id") || k.contains("number") ? "1001" : s.kind == Kind.IDN || s.kind == Kind.IDENTITY_ATTR ? "" : "test";
        };
        return typed(s, v);
    }

    /** Text typed by a user (or a candidate) turned into the slot's JSON type. Lists are separated with ';'. */
    static JsonNode typed(Slot s, String text) {
        if (text == null) return F.nullNode();
        String t = text.trim();
        if (t.equalsIgnoreCase("null")) return F.nullNode();
        if (s.bool) return F.booleanNode(t.equalsIgnoreCase("true") || t.equalsIgnoreCase("y") || t.equalsIgnoreCase("yes"));
        if (s.number && t.matches("-?\\d+")) return F.numberNode(Long.parseLong(t));
        if (s.list) {
            ArrayNode arr = F.arrayNode();
            if (!t.isEmpty()) for (String part : t.split("\\s*;\\s*")) arr.add(part);
            return arr;
        }
        return F.textNode(text);
    }

    // ---------------------------------------------------------------------------------------------------------------
    /** Turns what a run did into assertions: expect / expectChanged / expectError. Password values are not pinned. */
    static void record(ObjectNode testCase, RuleType type, RuleRunner.Outcome o) {
        if (!o.ok()) {
            testCase.put("expectError", o.error().type());
            return;
        }
        if (type == RuleType.BEFORE_PROVISIONING) {
            Object after = o.changed().get("plan");
            JsonNode plan = after != null ? Json.toNode(after) : testCase.path("inputs").path("plan");
            testCase.set("expectChanged", planExpectation(plan));
            return;
        }
        if (!o.voidResult()) testCase.set("expect", Json.toNode(o.result()));
    }

    static ObjectNode planExpectation(JsonNode plan) {
        ObjectNode exp = F.objectNode();
        ArrayNode reqs = exp.putObject("plan").putArray("accountRequests");
        for (JsonNode r : plan.path("accountRequests")) {
            ObjectNode e = reqs.addObject();
            if (r.has("operation")) e.set("operation", r.get("operation"));
            ArrayNode ars = e.putArray("attributeRequests");
            for (JsonNode ar : r.path("attributeRequests")) {
                ObjectNode x = ars.addObject();
                x.set("name", ar.get("name"));
                if (ar.has("operation")) x.set("operation", ar.get("operation"));
                if (ar.has("value") && !ar.path("name").asText().toLowerCase().contains("password")) x.set("value", ar.get("value"));
            }
        }
        return exp;
    }

    /** Short readable form of a value for case names: DNs become their CN, long text is cut. */
    static String brief(JsonNode v) {
        if (v == null || v.isNull()) return "null";
        if (v.isArray()) {
            List<String> parts = new ArrayList<>();
            v.forEach(x -> parts.add(brief(x)));
            return String.join("+", parts);
        }
        String s = v.asText();
        if (s.isEmpty()) return "(empty)";
        if (s.matches("(?i)^(CN|OU)=[^,]+,.*")) { // DN: "CN @ domain" so Sandbox and Production DNs stay distinguishable
            List<String> dc = new ArrayList<>();
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?i)\\bDC=([^,]+)").matcher(s);
            while (m.find()) dc.add(m.group(1));
            s = s.substring(3, s.indexOf(',')) + (dc.isEmpty() ? "" : " @ " + String.join(".", dc));
            return s;
        }
        return s.length() > 30 ? s.substring(0, 27) + "..." : s;
    }
}
