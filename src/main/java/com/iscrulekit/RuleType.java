package com.iscrulekit;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Supported ISC cloud rule types, their injected input variables and return contract (from the SailPoint developer docs). */
public enum RuleType {
    ATTRIBUTE_GENERATOR("AttributeGenerator", "Account Profile Attribute Generator", "value", false,
            "log", "idn", "identity", "application", "field"),
    ATTRIBUTE_GENERATOR_FROM_TEMPLATE("AttributeGeneratorFromTemplate", "Attribute Generator from Template", "value", false,
            "log", "idn", "identity", "field"),
    BEFORE_PROVISIONING("BeforeProvisioning", "Before Provisioning", null, false,
            "log", "idn", "plan", "application", "integration"),
    BUILD_MAP("BuildMap", "BuildMap (delimited file)", "map", false,
            "log", "cols", "record", "application", "schema"),
    CORRELATION("Correlation", "Correlation", "returnMap", false,
            "log", "idn", "account", "application"),
    IDENTITY_ATTRIBUTE("IdentityAttribute", "Identity Attribute", "attributeValue", false,
            "log", "idn", "identity", "oldValue"),
    MANAGER_CORRELATION("ManagerCorrelation", "Manager Correlation", "returnMap", false,
            "log", "idn", "link", "managerAttributeValue"),
    TRANSFORM("Transform", "Transform", "value", true,
            "log", "idn");

    /** Rule types SailPoint has deprecated, with the replacement to use. */
    public static final Map<String, String> DEPRECATED = Map.of(
            "CertificationExclusion", "use campaign filters",
            "IdentitySelector", "use role standard assignment criteria",
            "Integration", "use BeforeProvisioning");

    /** Names that may be ISC-injected inputs for some rule type; used to spot variables that do not exist for this type. */
    public static final List<String> ALL_INPUT_NAMES = List.of(
            "identity", "application", "account", "link", "plan", "integration", "field",
            "oldValue", "managerAttributeValue", "cols", "record", "schema", "context");

    public final String xmlName;
    public final String label;
    /** Name of the value the rule must return, or null when the rule returns nothing. */
    public final String returns;
    /** True for rule types whose extra variables are arbitrary (Transform). */
    public final boolean anyVars;
    public final List<String> inputs;

    RuleType(String xmlName, String label, String returns, boolean anyVars, String... inputs) {
        this.xmlName = xmlName;
        this.label = label;
        this.returns = returns;
        this.anyVars = anyVars;
        this.inputs = List.of(inputs);
    }

    /** &lt;Signature&gt; arguments for the XML file (BuildMap only). */
    public List<String[]> signature() {
        if (this != BUILD_MAP) return List.of();
        return List.of(new String[]{"log", "Log"}, new String[]{"cols", "List"}, new String[]{"record", "List"},
                new String[]{"application", "Hashtable"}, new String[]{"schema", "Schema"});
    }

    public static Optional<RuleType> fromName(String s) {
        if (s == null) return Optional.empty();
        String n = s.trim().replace("-", "").replace("_", "").replace(" ", "");
        for (RuleType t : values()) if (t.xmlName.equalsIgnoreCase(n)) return Optional.of(t);
        return Optional.empty();
    }

    /** Best-effort guess from a header comment or the variables the code uses. */
    public static Optional<RuleType> guess(String src) {
        String head = src.substring(0, Math.min(src.length(), 2000)).toLowerCase();
        String[][] pairs = {{"before provisioning", "BeforeProvisioning"}, {"manager correlation", "ManagerCorrelation"},
                {"correlation", "Correlation"}, {"attribute generator", "AttributeGenerator"},
                {"identity attribute", "IdentityAttribute"}, {"buildmap", "BuildMap"}, {"transform", "Transform"}};
        for (String[] p : pairs) if (head.contains(p[0])) return fromName(p[1]);
        if (src.matches("(?s).*\\bplan\\s*\\.\\s*(getAccountRequests|getIdentity).*")) return Optional.of(BEFORE_PROVISIONING);
        if (src.contains("identityAttributeName")) return Optional.of(src.matches("(?s).*\\blink\\b.*") ? MANAGER_CORRELATION : CORRELATION);
        if (src.matches("(?s).*\\bcols\\b.*\\brecord\\b.*")) return Optional.of(BUILD_MAP);
        if (src.matches("(?s).*\\boldValue\\b.*")) return Optional.of(IDENTITY_ATTRIBUTE);
        return Optional.empty();
    }
}
