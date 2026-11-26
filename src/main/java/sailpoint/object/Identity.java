package sailpoint.object;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Mock of sailpoint.object.Identity, populated from the test-case JSON. */
public class Identity extends SailPointObject {
    private String firstname, lastname, email, displayName;
    private Identity manager;
    private Attributes<String, Object> attributes = new Attributes<>();
    private List<Link> links = new ArrayList<>();
    /** Any other top-level key from the fixture; reachable via getAttribute(). */
    private final Map<String, Object> extra = new LinkedHashMap<>();

    public String getFirstname() { return firstname; }
    public void setFirstname(String v) { this.firstname = v; }
    public String getLastname() { return lastname; }
    public void setLastname(String v) { this.lastname = v; }
    public String getEmail() { return email; }
    public void setEmail(String v) { this.email = v; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String v) { this.displayName = v; }
    public Identity getManager() { return manager; }
    public void setManager(Identity v) { this.manager = v; }
    public Attributes<String, Object> getAttributes() { return attributes; }
    public void setAttributes(Attributes<String, Object> v) { this.attributes = v; }
    public List<Link> getLinks() { return links; }
    public void setLinks(List<Link> v) { this.links = v; }
    public Map<String, Object> getExtra() { return extra; }

    public Object getAttribute(String name) {
        Object v = lookup(attributes, name);
        if (v != null) return v;
        return switch (String.valueOf(name).toLowerCase()) {
            case "name" -> getName();
            case "id" -> getId();
            case "firstname" -> firstname;
            case "lastname" -> lastname;
            case "email" -> email;
            case "displayname" -> displayName;
            default -> lookup(extra, name);
        };
    }

    public void setAttribute(String name, Object value) { attributes.put(name, value); }

    /** As in SailPoint: the attribute as text (null when missing). */
    public String getStringAttribute(String name) {
        Object v = getAttribute(name);
        if (v instanceof List<?> l) { // like sailpoint.tools.Util.otos: a list becomes "a,b"; an empty one null
            if (l.isEmpty()) return null;
            StringBuilder s = new StringBuilder();
            for (Object o : l) s.append(s.isEmpty() ? "" : ",").append(o);
            return s.toString();
        }
        return v == null ? null : String.valueOf(v);
    }

    public String getFullName() {
        String f = firstname == null ? "" : firstname, l = lastname == null ? "" : lastname;
        String full = (f + " " + l).trim();
        return full.isEmpty() ? null : full;
    }

    public String getDisplayableName() { return displayName != null ? displayName : getFullName() != null ? getFullName() : getName(); }

    public boolean isInactive() {
        Object v = getAttribute("inactive");
        return v != null && Boolean.parseBoolean(String.valueOf(v));
    }

    public List<Link> getLinksByAppIdOrName(String appId, String appName) {
        List<Link> out = new ArrayList<>();
        for (Link l : links) if (appName != null && appName.equals(l.getApplicationName()) || appId != null && appId.equals(l.getApplicationName())) out.add(l);
        return out;
    }

    public Link getLink(String applicationName) {
        for (Link l : links) if (applicationName != null && applicationName.equals(l.getApplicationName())) return l;
        return null;
    }

    @Override
    public Object toPlain() {
        Map<String, Object> m = basePlain();
        if (firstname != null) m.put("firstname", firstname);
        if (lastname != null) m.put("lastname", lastname);
        if (email != null) m.put("email", email);
        if (displayName != null) m.put("displayName", displayName);
        if (manager != null) m.put("manager", manager);
        m.put("attributes", attributes);
        if (!links.isEmpty()) m.put("links", links);
        m.putAll(extra);
        return m;
    }

    @Override public String toString() { return "Identity[" + getName() + "]"; }
}
