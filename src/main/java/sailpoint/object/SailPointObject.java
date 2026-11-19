package sailpoint.object;

import com.iscrulekit.PlainConvertible;

import java.util.LinkedHashMap;
import java.util.Map;

/** Mock of sailpoint.object.SailPointObject: id + name. */
public abstract class SailPointObject implements PlainConvertible {
    private String id;
    private String name;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    protected static Object lookup(Map<String, Object> m, String key) { return lookupIn(m, key); }

    /** Case-insensitive lookup of a key in a map; returns null when absent. */
    public static Object lookupIn(Map<String, Object> m, String key) {
        if (m == null || key == null) return null;
        if (m.containsKey(key)) return m.get(key);
        for (Map.Entry<String, Object> e : m.entrySet()) if (e.getKey().equalsIgnoreCase(key)) return e.getValue();
        return null;
    }

    protected Map<String, Object> basePlain() {
        Map<String, Object> m = new LinkedHashMap<>();
        if (id != null) m.put("id", id);
        if (name != null) m.put("name", name);
        return m;
    }
}
