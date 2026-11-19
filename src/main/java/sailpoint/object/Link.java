package sailpoint.object;

import com.iscrulekit.PlainConvertible;

import java.util.LinkedHashMap;
import java.util.Map;

/** Mock of sailpoint.object.Link (an account on an application). */
public class Link implements PlainConvertible {
    private String applicationName, nativeIdentity, displayName, id;
    private Attributes<String, Object> attributes = new Attributes<>();

    public String getId() { return id; }
    public void setId(String v) { id = v; }
    public String getApplicationName() { return applicationName; }
    public void setApplicationName(String v) { applicationName = v; }
    public String getNativeIdentity() { return nativeIdentity; }
    public void setNativeIdentity(String v) { nativeIdentity = v; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String v) { displayName = v; }
    public Attributes<String, Object> getAttributes() { return attributes; }
    public void setAttributes(Attributes<String, Object> a) { attributes = a; }

    public Object getAttribute(String name) { return SailPointObject.lookupIn(attributes, name); }

    @Override
    public Object toPlain() {
        Map<String, Object> m = new LinkedHashMap<>();
        if (id != null) m.put("id", id);
        if (applicationName != null) m.put("applicationName", applicationName);
        if (nativeIdentity != null) m.put("nativeIdentity", nativeIdentity);
        if (displayName != null) m.put("displayName", displayName);
        m.put("attributes", attributes);
        return m;
    }
}
