package sailpoint.object;

import com.iscrulekit.PlainConvertible;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Mock of sailpoint.object.ProvisioningPlan with the nested AccountRequest / AttributeRequest classes and the two
 * Operation enums. Mutations made by a BeforeProvisioning rule are visible in the test report.
 */
public class ProvisioningPlan implements PlainConvertible {

    /** Attribute-level operations. */
    public enum Operation { Set, Add, Remove, Retain, Revoke }

    private Identity identity;
    private List<AccountRequest> accountRequests = new ArrayList<>();

    public Identity getIdentity() { return identity; }
    public void setIdentity(Identity identity) { this.identity = identity; }
    public List<AccountRequest> getAccountRequests() { return accountRequests; }
    public void setAccountRequests(List<AccountRequest> l) { this.accountRequests = l; }
    public void add(AccountRequest r) { accountRequests.add(r); }

    public AccountRequest getAccountRequest(String applicationName) {
        for (AccountRequest r : accountRequests) if (applicationName != null && applicationName.equals(r.getApplicationName())) return r;
        return null;
    }

    @Override
    public Object toPlain() {
        Map<String, Object> m = new LinkedHashMap<>();
        if (identity != null) m.put("identity", identity);
        m.put("accountRequests", accountRequests);
        return m;
    }

    public static class AccountRequest implements PlainConvertible {
        /** Account-level operations. */
        public enum Operation { Create, Modify, Delete, Disable, Enable, Unlock, Lock }

        private String applicationName, nativeIdentity;
        private Operation operation;
        private List<AttributeRequest> attributeRequests = new ArrayList<>();
        private Attributes<String, Object> arguments;

        public String getApplicationName() { return applicationName; }
        public void setApplicationName(String v) { applicationName = v; }
        public String getNativeIdentity() { return nativeIdentity; }
        public void setNativeIdentity(String v) { nativeIdentity = v; }
        public Operation getOperation() { return operation; }
        public void setOperation(Operation v) { operation = v; }
        public List<AttributeRequest> getAttributeRequests() { return attributeRequests; }
        public void setAttributeRequests(List<AttributeRequest> l) { attributeRequests = l; }
        public Attributes<String, Object> getArguments() { return arguments; }
        public void setArguments(Attributes<String, Object> a) { arguments = a; }

        public void add(AttributeRequest r) { attributeRequests.add(r); }
        public boolean remove(AttributeRequest r) { return attributeRequests.remove(r); }

        public AttributeRequest getAttributeRequest(String name) {
            for (AttributeRequest r : attributeRequests) if (name != null && name.equals(r.getName())) return r;
            return null;
        }

        @Override
        public Object toPlain() {
            Map<String, Object> m = new LinkedHashMap<>();
            if (applicationName != null) m.put("applicationName", applicationName);
            if (nativeIdentity != null) m.put("nativeIdentity", nativeIdentity);
            if (operation != null) m.put("operation", operation);
            m.put("attributeRequests", attributeRequests);
            if (arguments != null) m.put("arguments", arguments);
            return m;
        }
    }

    public static class AttributeRequest implements PlainConvertible {
        private String name;
        private Operation operation;
        private Object value;

        public AttributeRequest() {}

        public AttributeRequest(String name, Operation operation, Object value) {
            this.name = name;
            this.operation = operation;
            this.value = value;
        }

        public String getName() { return name; }
        public void setName(String v) { name = v; }
        public Operation getOperation() { return operation; }
        public void setOperation(Operation v) { operation = v; }
        public Object getValue() { return value; }
        public void setValue(Object v) { value = v; }

        @Override
        public Object toPlain() {
            Map<String, Object> m = new LinkedHashMap<>();
            if (name != null) m.put("name", name);
            if (operation != null) m.put("operation", operation);
            m.put("value", value);
            return m;
        }
    }
}
