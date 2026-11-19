package com.iscrulekit;

import com.fasterxml.jackson.databind.JsonNode;
import sailpoint.object.Application;
import sailpoint.object.Attributes;
import sailpoint.object.Field;
import sailpoint.object.Identity;
import sailpoint.object.IntegrationConfig;
import sailpoint.object.Link;
import sailpoint.object.ProvisioningPlan;
import sailpoint.object.ProvisioningPlan.AccountRequest;
import sailpoint.object.ProvisioningPlan.AttributeRequest;
import sailpoint.object.ResourceObject;
import sailpoint.object.Schema;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Turns the JSON of a test case into the Java objects the rule receives as its input variables. */
public final class Fixture {
    private Fixture() {}

    /** Builds the value for an input variable. Well-known ISC names become mock ISC objects; anything else is plain Java. */
    public static Object forVariable(String name, JsonNode n) {
        if (n == null || n.isNull()) return null;
        if (n.isObject()) {
            switch (name) {
                case "identity": return identity(n);
                case "application": return application(n);
                case "plan": return plan(n);
                case "account": return account(n);
                case "link": return link(n);
                case "field": return field(n);
                case "schema": return schema(n);
                case "integration": return integration(n);
                default: break;
            }
        }
        return generic(n);
    }

    /** JSON to plain Java: String, Integer/Long/Double, Boolean, ArrayList, LinkedHashMap. */
    public static Object generic(JsonNode n) {
        if (n == null || n.isNull() || n.isMissingNode()) return null;
        if (n.isTextual()) return n.asText();
        if (n.isBoolean()) return n.booleanValue();
        if (n.isIntegralNumber()) return n.canConvertToInt() ? (Object) n.intValue() : (Object) n.longValue();
        if (n.isNumber()) return n.doubleValue();
        if (n.isArray()) {
            List<Object> l = new ArrayList<>();
            for (JsonNode x : n) l.add(generic(x));
            return l;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        n.fields().forEachRemaining(f -> m.put(f.getKey(), generic(f.getValue())));
        return m;
    }

    private static String text(JsonNode n, String key) {
        JsonNode v = n.get(key);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static Attributes<String, Object> attrs(JsonNode n) {
        Attributes<String, Object> a = new Attributes<>();
        if (n != null && n.isObject()) n.fields().forEachRemaining(f -> a.put(f.getKey(), generic(f.getValue())));
        return a;
    }

    public static Identity identity(JsonNode n) {
        Identity i = new Identity();
        Set<String> known = Set.of("id", "name", "firstname", "lastname", "email", "displayName", "manager", "attributes", "links");
        i.setId(text(n, "id"));
        i.setName(text(n, "name"));
        i.setFirstname(text(n, "firstname"));
        i.setLastname(text(n, "lastname"));
        i.setEmail(text(n, "email"));
        i.setDisplayName(text(n, "displayName"));
        if (n.hasNonNull("manager") && n.get("manager").isObject()) i.setManager(identity(n.get("manager")));
        i.setAttributes(attrs(n.get("attributes")));
        if (n.has("links") && n.get("links").isArray()) {
            List<Link> links = new ArrayList<>();
            for (JsonNode l : n.get("links")) links.add(link(l));
            i.setLinks(links);
        }
        for (Iterator<Map.Entry<String, JsonNode>> it = n.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> f = it.next();
            if (!known.contains(f.getKey())) i.getExtra().put(f.getKey(), generic(f.getValue()));
        }
        return i;
    }

    public static Application application(JsonNode n) {
        Application a = new Application();
        a.setId(text(n, "id"));
        a.setName(text(n, "name"));
        a.setType(text(n, "type"));
        a.setAttributes(attrs(n.get("attributes")));
        return a;
    }

    public static Link link(JsonNode n) {
        Link l = new Link();
        l.setId(text(n, "id"));
        l.setApplicationName(text(n, "applicationName"));
        l.setNativeIdentity(text(n, "nativeIdentity"));
        l.setDisplayName(text(n, "displayName"));
        l.setAttributes(attrs(n.get("attributes")));
        return l;
    }

    public static ResourceObject account(JsonNode n) {
        ResourceObject r = new ResourceObject();
        r.setIdentity(text(n, "identity") != null ? text(n, "identity") : text(n, "nativeIdentity"));
        r.setUuid(text(n, "uuid"));
        r.setDisplayName(text(n, "displayName"));
        r.setAttributes(attrs(n.get("attributes")));
        return r;
    }

    public static Field field(JsonNode n) {
        Field f = new Field();
        f.setName(text(n, "name"));
        f.setType(text(n, "type"));
        f.setDisplayName(text(n, "displayName"));
        f.setTemplate(text(n, "template"));
        n.fields().forEachRemaining(e -> {
            if (!Set.of("name", "type", "displayName", "template").contains(e.getKey())) f.getAttributes().put(e.getKey(), generic(e.getValue()));
        });
        return f;
    }

    public static Schema schema(JsonNode n) {
        Schema s = new Schema();
        s.setName(text(n, "name"));
        s.setObjectType(text(n, "objectType"));
        s.setIdentityAttribute(text(n, "identityAttribute"));
        s.setDisplayAttribute(text(n, "displayAttribute"));
        return s;
    }

    public static IntegrationConfig integration(JsonNode n) {
        IntegrationConfig c = new IntegrationConfig();
        c.setId(text(n, "id"));
        c.setName(text(n, "name"));
        return c;
    }

    public static ProvisioningPlan plan(JsonNode n) {
        ProvisioningPlan p = new ProvisioningPlan();
        if (n.hasNonNull("identity") && n.get("identity").isObject()) p.setIdentity(identity(n.get("identity")));
        if (n.has("accountRequests") && n.get("accountRequests").isArray()) {
            for (JsonNode r : n.get("accountRequests")) {
                AccountRequest ar = new AccountRequest();
                ar.setApplicationName(text(r, "applicationName"));
                ar.setNativeIdentity(text(r, "nativeIdentity"));
                if (r.hasNonNull("operation")) ar.setOperation(enumValue(AccountRequest.Operation.class, r.get("operation").asText()));
                if (r.has("attributeRequests") && r.get("attributeRequests").isArray()) {
                    for (JsonNode x : r.get("attributeRequests")) {
                        AttributeRequest at = new AttributeRequest();
                        at.setName(text(x, "name"));
                        if (x.hasNonNull("operation")) at.setOperation(enumValue(ProvisioningPlan.Operation.class, x.get("operation").asText()));
                        at.setValue(generic(x.get("value")));
                        ar.add(at);
                    }
                }
                if (r.hasNonNull("arguments")) ar.setArguments(attrs(r.get("arguments")));
                p.add(ar);
            }
        }
        return p;
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String name) {
        for (E e : type.getEnumConstants()) if (e.name().equalsIgnoreCase(name)) return e;
        throw new IllegalArgumentException("'" + name + "' is not a valid " + type.getSimpleName() + " (allowed: "
                + java.util.Arrays.toString(type.getEnumConstants()) + ")");
    }
}
