package com.iscrulekit;

/** Starter test-case files per rule type, printed by {@code iscrule scaffold <type>}. */
public final class Scaffold {
    private Scaffold() {}

    public static String cases(RuleType t) {
        return switch (t) {
            case ATTRIBUTE_GENERATOR -> """
                {
                  "cases": [
                    {
                      "name": "typical identity",
                      "inputs": {
                        "identity": { "name": "tsmith", "firstname": "Tyler", "lastname": "Smith", "email": "tyler.smith@example.com",
                                      "attributes": { "department": "Engineering" } },
                        "application": { "name": "Active Directory", "attributes": {} },
                        "field": { "name": "sAMAccountName" },
                        "idn": {}
                      },
                      "expect": "tsmith"
                    }
                  ]
                }
                """;
            case ATTRIBUTE_GENERATOR_FROM_TEMPLATE -> """
                {
                  "cases": [
                    {
                      "name": "template field",
                      "inputs": {
                        "identity": { "name": "tsmith", "firstname": "Tyler", "lastname": "Smith" },
                        "field": { "name": "displayName", "template": "$(firstname) $(lastname)" },
                        "idn": {}
                      }
                    }
                  ]
                }
                """;
            case BEFORE_PROVISIONING -> """
                {
                  "cases": [
                    {
                      "name": "disable of an inactive identity",
                      "inputs": {
                        "plan": {
                          "identity": { "id": "2c9180", "name": "jdoe",
                                        "attributes": { "cloudLifecycleState": "inactive", "identificationNumber": "12345" } },
                          "accountRequests": [
                            { "applicationName": "Active Directory", "nativeIdentity": "CN=jdoe,OU=Users,DC=example,DC=org",
                              "operation": "Disable", "attributeRequests": [] }
                          ]
                        },
                        "application": { "name": "Active Directory", "attributes": { "environment": "Sandbox" } },
                        "integration": null,
                        "idn": {
                          "accountExistsByNativeIdentity": true,
                          "getRawAccountAttribute": { "$byLastArg": { "description": "Staff" }, "$default": "" }
                        }
                      },
                      "expectChanged": { "plan": { "accountRequests": [ { "operation": "Disable" } ] } },
                      "expectLogContains": [ "Begins" ]
                    }
                  ]
                }
                """;
            case BUILD_MAP -> """
                {
                  "cases": [
                    {
                      "name": "trims values",
                      "inputs": {
                        "cols": ["id", "name"],
                        "record": ["1", " Ann "],
                        "application": { "name": "CSV" },
                        "schema": { "name": "account" }
                      },
                      "expect": { "id": "1", "name": "Ann" }
                    }
                  ]
                }
                """;
            case CORRELATION -> """
                {
                  "cases": [
                    {
                      "name": "account with employee id",
                      "inputs": {
                        "account": { "attributes": { "employeeId": "1001" } },
                        "application": { "name": "HR" },
                        "idn": {}
                      },
                      "expect": { "identityAttributeName": "employeeNumber", "identityAttributeValue": "1001" }
                    }
                  ]
                }
                """;
            case IDENTITY_ATTRIBUTE -> """
                {
                  "cases": [
                    {
                      "name": "department mapping",
                      "inputs": {
                        "identity": { "name": "tsmith", "attributes": { "department": "Engineering" } },
                        "oldValue": null,
                        "idn": {}
                      },
                      "expect": "ENG"
                    }
                  ]
                }
                """;
            case MANAGER_CORRELATION -> """
                {
                  "cases": [
                    {
                      "name": "manager by id",
                      "inputs": {
                        "link": { "applicationName": "HR", "nativeIdentity": "1001", "attributes": { "manager": "1000" } },
                        "managerAttributeValue": "1000",
                        "idn": {}
                      },
                      "expect": { "identityAttributeName": "employeeNumber", "identityAttributeValue": "1000" }
                    }
                  ]
                }
                """;
            case TRANSFORM -> """
                {
                  "cases": [
                    {
                      "name": "last token",
                      "inputs": { "text": "a, b ,c", "delimiter": ",", "idn": {} },
                      "expect": "c"
                    }
                  ]
                }
                """;
        };
    }
}
