package sailpoint.object;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Mock of sailpoint.object.Attributes: an ordered map with typed getters. */
@SuppressWarnings({"unchecked", "rawtypes"})
public class Attributes<K, V> extends LinkedHashMap<K, V> {
    private static final long serialVersionUID = 1L;

    public Attributes() {}

    public Attributes(Map<? extends K, ? extends V> m) { super(m); }

    public String getString(String key) {
        Object v = get(key);
        return v == null ? null : v.toString();
    }

    public boolean getBoolean(String key) { return getBoolean(key, false); }

    public boolean getBoolean(String key, boolean dflt) {
        Object v = get(key);
        if (v == null) return dflt;
        return v instanceof Boolean b ? b : "true".equalsIgnoreCase(v.toString());
    }

    public int getInt(String key) {
        Object v = get(key);
        if (v instanceof Number n) return n.intValue();
        try { return v == null ? 0 : Integer.parseInt(v.toString().trim()); } catch (NumberFormatException e) { return 0; }
    }

    public long getLong(String key) {
        Object v = get(key);
        if (v instanceof Number n) return n.longValue();
        try { return v == null ? 0L : Long.parseLong(v.toString().trim()); } catch (NumberFormatException e) { return 0L; }
    }

    public List getList(String key) {
        Object v = get(key);
        if (v == null) return null;
        if (v instanceof List l) return l;
        List r = new ArrayList();
        r.add(v);
        return r;
    }

    public Map getMap() { return this; }

    public void setMap(Map m) {
        if (m == this) return;
        clear();
        if (m != null) putAll(m);
    }
}
