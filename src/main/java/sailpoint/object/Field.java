package sailpoint.object;

import com.iscrulekit.PlainConvertible;

import java.util.LinkedHashMap;
import java.util.Map;

/** Mock of sailpoint.object.Field (the provisioning attribute being generated). */
public class Field implements PlainConvertible {
    private String name, type, displayName, template;
    private final Map<String, Object> attributes = new LinkedHashMap<>();

    public String getName() { return name; }
    public void setName(String v) { name = v; }
    public String getType() { return type; }
    public void setType(String v) { type = v; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String v) { displayName = v; }
    public String getTemplate() { return template; }
    public void setTemplate(String v) { template = v; }
    public Map<String, Object> getAttributes() { return attributes; }

    public Object getAttribute(String key) {
        if ("template".equalsIgnoreCase(key) && template != null) return template;
        return SailPointObject.lookupIn(attributes, key);
    }

    @Override
    public Object toPlain() {
        Map<String, Object> m = new LinkedHashMap<>();
        if (name != null) m.put("name", name);
        if (type != null) m.put("type", type);
        if (displayName != null) m.put("displayName", displayName);
        if (template != null) m.put("template", template);
        m.putAll(attributes);
        return m;
    }
}
