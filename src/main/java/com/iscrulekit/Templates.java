package com.iscrulekit;

/** Starter rule files for {@code iscrule new <type> <name>}: the inputs ISC gives the rule and what it must return. */
final class Templates {
    private Templates() {}

    static String rule(RuleType t, String name) {
        String header = """
                /**
                 * %s rule: %s
                 *
                 * Inputs ISC gives this rule: %s
                 * Must return: %s
                 *
                 * Test it:   iscrule add-test <this file>     (asks what to test, runs it, saves the case)
                 *            iscrule run <this file> <this file's .cases.json>
                 * Deploy it: iscrule export <this file> -o "Rule - %s - %s.xml"
                 */
                """.formatted(t.label, name, String.join(", ", t.inputs),
                t.returns == null ? "nothing (change the plan in place)" : t.returns, t.xmlName, name);
        String body = switch (t) {
            case ATTRIBUTE_GENERATOR -> """
                    String first = identity.getFirstname();
                    String last = identity.getLastname();
                    if (first == null || last == null) {
                        log.warn("Missing first or last name for " + identity.getName());
                        return null;
                    }
                    String value = (first.substring(0, 1) + last).toLowerCase().replaceAll("[^a-z0-9]", "");
                    // Uniqueness: idn.isUniqueLDAPValue(identity.getName(), application.getName(), field.getName(), value)
                    return value;
                    """;
            case ATTRIBUTE_GENERATOR_FROM_TEMPLATE -> """
                    String value = identity.getFirstname() + " " + identity.getLastname();
                    return value.trim();
                    """;
            case BEFORE_PROVISIONING -> """
                    import sailpoint.object.Identity;
                    import sailpoint.object.ProvisioningPlan;
                    import sailpoint.object.ProvisioningPlan.AccountRequest;
                    import sailpoint.object.ProvisioningPlan.AttributeRequest;

                    if (plan == null || plan.getAccountRequests() == null) return;
                    Identity identity = plan.getIdentity();
                    String lifecycle = identity == null ? null : (String) identity.getAttribute("cloudLifecycleState");

                    for (AccountRequest req : plan.getAccountRequests()) {
                        if (AccountRequest.Operation.Disable.equals(req.getOperation()) && "inactive".equalsIgnoreCase(lifecycle)) {
                            log.info("Disabling " + req.getNativeIdentity());
                            req.add(new AttributeRequest("description", ProvisioningPlan.Operation.Set, "Disabled by ISC"));
                        }
                    }
                    """;
            case BUILD_MAP -> """
                    import java.util.HashMap;
                    import java.util.Map;

                    Map map = new HashMap();
                    for (int i = 0; i < cols.size(); i++) {
                        String value = i < record.size() ? (String) record.get(i) : null;
                        map.put(cols.get(i), value == null || value.trim().isEmpty() ? null : value.trim());
                    }
                    return map;
                    """;
            case CORRELATION -> """
                    import java.util.HashMap;
                    import java.util.Map;

                    Map returnMap = new HashMap();
                    String employeeId = account.getStringAttribute("employeeId");
                    if (employeeId != null && !employeeId.trim().isEmpty()) {
                        returnMap.put("identityAttributeName", "employeeNumber");
                        returnMap.put("identityAttributeValue", employeeId.trim());
                    }
                    return returnMap;
                    """;
            case IDENTITY_ATTRIBUTE -> """
                    String status = (String) identity.getAttribute("hrStatus");
                    if (status == null) return oldValue;
                    if ("T".equalsIgnoreCase(status)) return "inactive";
                    return "active";
                    """;
            case MANAGER_CORRELATION -> """
                    import java.util.HashMap;
                    import java.util.Map;

                    Map returnMap = new HashMap();
                    if (managerAttributeValue != null) {
                        returnMap.put("identityAttributeName", "employeeNumber");
                        returnMap.put("identityAttributeValue", managerAttributeValue.toString().trim());
                    }
                    return returnMap;
                    """;
            case TRANSFORM -> """
                    // Transform inputs are whatever the transform JSON passes in; check they exist with "== void".
                    if (value == void || value == null) return null;
                    return value.toString().trim();
                    """;
        };
        return header + body;
    }
}
