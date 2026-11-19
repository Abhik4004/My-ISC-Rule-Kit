package sailpoint.object;

import com.iscrulekit.PlainConvertible;

import java.util.LinkedHashMap;
import java.util.Map;

/** Mock of sailpoint.object.Schema (BuildMap rules). */
public class Schema implements PlainConvertible {
    private String name, objectType, identityAttribute, displayAttribute;

    public String getName() { return name; }
    public void setName(String v) { name = v; }
    public String getObjectType() { return objectType != null ? objectType : name; }
    public void setObjectType(String v) { objectType = v; }
    public String getIdentityAttribute() { return identityAttribute; }
    public void setIdentityAttribute(String v) { identityAttribute = v; }
    public String getDisplayAttribute() { return displayAttribute; }
    public void setDisplayAttribute(String v) { displayAttribute = v; }

    @Override
    public Object toPlain() {
        Map<String, Object> m = new LinkedHashMap<>();
        if (name != null) m.put("name", name);
        if (objectType != null) m.put("objectType", objectType);
        if (identityAttribute != null) m.put("identityAttribute", identityAttribute);
        if (displayAttribute != null) m.put("displayAttribute", displayAttribute);
        return m;
    }
}
