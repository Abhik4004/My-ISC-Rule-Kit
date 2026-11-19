package sailpoint.object;

import java.util.LinkedHashMap;
import java.util.Map;

/** Mock of sailpoint.object.Application (read-only source reference in rules). */
public class Application extends SailPointObject {
    private String type;
    private Attributes<String, Object> attributes = new Attributes<>();

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public Attributes<String, Object> getAttributes() { return attributes; }
    public void setAttributes(Attributes<String, Object> a) { this.attributes = a; }

    public Object getAttributeValue(String name) { return lookup(attributes, name); }

    public String getStringAttributeValue(String name) {
        Object v = getAttributeValue(name);
        return v == null ? null : v.toString();
    }

    public boolean getBooleanAttributeValue(String name) {
        Object v = getAttributeValue(name);
        return v instanceof Boolean b ? b : v != null && "true".equalsIgnoreCase(v.toString());
    }

    public int getIntAttributeValue(String name) {
        Object v = getAttributeValue(name);
        return v instanceof Number n ? n.intValue() : 0;
    }

    @Override
    public Object toPlain() {
        Map<String, Object> m = new LinkedHashMap<>(basePlain());
        if (type != null) m.put("type", type);
        m.put("attributes", attributes);
        return m;
    }

    @Override public String toString() { return "Application[" + getName() + "]"; }
}
