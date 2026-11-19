package com.iscrulekit;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Converts rule values and mock objects into plain JSON-friendly Java (Map, List, String, Number, Boolean, null). */
public final class Plain {
    private Plain() {}

    public static Object of(Object o) {
        if (o == null) return null;
        if (o instanceof PlainConvertible p) return of(p.toPlain());
        if (o instanceof CharSequence) return o.toString();
        if (o instanceof Number || o instanceof Boolean) return o;
        if (o instanceof Character c) return c.toString();
        if (o instanceof Enum<?> e) return e.name();
        if (o instanceof Map<?, ?> m) {
            Map<String, Object> r = new LinkedHashMap<>();
            m.forEach((k, v) -> r.put(String.valueOf(k), of(v)));
            return r;
        }
        if (o instanceof Iterable<?> it) {
            List<Object> r = new ArrayList<>();
            for (Object x : it) r.add(of(x));
            return r;
        }
        if (o.getClass().isArray()) {
            List<Object> r = new ArrayList<>();
            for (int i = 0, n = Array.getLength(o); i < n; i++) r.add(of(Array.get(o, i)));
            return r;
        }
        if (o instanceof Date d) return d.toInstant().toString();
        return o.toString();
    }

    public static String kind(Object o) {
        if (o == null) return "null";
        if (o instanceof CharSequence) return "String";
        if (o instanceof Integer || o instanceof Long || o instanceof Short || o instanceof Byte) return "Integer/Long";
        if (o instanceof Double || o instanceof Float) return "Double";
        if (o instanceof Boolean) return "Boolean";
        if (o instanceof Map) return "Map";
        if (o instanceof Iterable || o.getClass().isArray()) return "List";
        return o.getClass().getSimpleName();
    }
}
