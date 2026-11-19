package com.iscrulekit;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Backs the {@code idn} variable. Rules call {@code idn.someMethod(args)}; the return value comes from the test case's
 * "idn" object:
 * <pre>
 *   "idn": {
 *     "accountExistsByNativeIdentity": true,
 *     "getRawAccountAttribute": { "$byLastArg": { "memberOf": ["CN=A"], "brb": "false" }, "$default": "" }
 *   }
 * </pre>
 * A value with {@code $byLastArg} / {@code $default} chooses the result from the last argument of the call. Methods that
 * are not listed return null and are reported as "NOT MOCKED" so you know what to add.
 */
public final class IdnMock {
    private final Map<String, JsonNode> mocks = new LinkedHashMap<>();
    private final List<String> notes;

    public IdnMock(JsonNode idnNode, List<String> notes) {
        this.notes = notes;
        if (idnNode != null && idnNode.isObject()) idnNode.fields().forEachRemaining(f -> mocks.put(f.getKey(), f.getValue()));
    }

    /** Called from the BeanShell scripted object's invoke(name, args). */
    public Object call(String name, Object[] args) {
        StringBuilder sig = new StringBuilder("idn." + name + "(");
        for (int i = 0; i < args.length; i++) sig.append(i == 0 ? "" : ", ").append(Json.compact(Plain.of(args[i])));
        sig.append(")");

        JsonNode v = mocks.get(name);
        if (v == null) {
            note("NOT MOCKED: " + sig + " -> null. Add \"" + name + "\": <value> to the \"idn\" object of the test case.");
            return null;
        }
        if (v.isObject() && (v.has("$byLastArg") || v.has("$default"))) {
            String key = args.length == 0 ? "" : String.valueOf(args[args.length - 1]);
            JsonNode by = v.get("$byLastArg");
            if (by != null && by.has(key)) v = by.get(key);
            else if (v.has("$default")) v = v.get("$default");
            else {
                note("NOT MOCKED: " + sig + " -> null (no $byLastArg entry for '" + key + "' and no $default)");
                return null;
            }
        }
        Object r = Fixture.generic(v);
        note("mock: " + sig + " -> " + Json.compact(r));
        return r;
    }

    private void note(String s) { if (!notes.contains(s)) notes.add(s); }
}
