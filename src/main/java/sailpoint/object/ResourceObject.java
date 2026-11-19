package sailpoint.object;

import com.iscrulekit.PlainConvertible;

import java.util.LinkedHashMap;
import java.util.Map;

/** Mock of sailpoint.object.ResourceObject (an aggregated account, read-only in Correlation rules). */
public class ResourceObject implements PlainConvertible {
    private String identity, uuid, displayName;
    private Attributes<String, Object> attributes = new Attributes<>();

    /** Native identity of the account (named getIdentity() in the real API). */
    public String getIdentity() { return identity; }
    public void setIdentity(String v) { identity = v; }
    public String getUuid() { return uuid; }
    public void setUuid(String v) { uuid = v; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String v) { displayName = v; }
    public Attributes<String, Object> getAttributes() { return attributes; }
    public void setAttributes(Attributes<String, Object> a) { attributes = a; }

    public Object getAttribute(String name) { return SailPointObject.lookupIn(attributes, name); }

    public String getStringAttribute(String name) {
        Object v = getAttribute(name);
        return v == null ? null : v.toString();
    }

    @Override
    public Object toPlain() {
        Map<String, Object> m = new LinkedHashMap<>();
        if (identity != null) m.put("identity", identity);
        if (uuid != null) m.put("uuid", uuid);
        if (displayName != null) m.put("displayName", displayName);
        m.put("attributes", attributes);
        return m;
    }
}
