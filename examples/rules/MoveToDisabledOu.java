/**
 * Before Provisioning Rule: moves disabled AD accounts to the Disabled OU.
 *
 * RDK / Eclipse style (class wrapper, inputs re-declared as fields): the kit strips the wrapper for you.
 * This is the fixed version of MoveToDisabledOu.buggy.java.
 */
import java.util.*;
import sailpoint.object.Identity;
import sailpoint.object.Application;
import sailpoint.object.ProvisioningPlan;
import sailpoint.object.ProvisioningPlan.AccountRequest;
import sailpoint.object.ProvisioningPlan.AttributeRequest;

public class MoveToDisabledOu {

    Identity identity = null;
    String disabledOu = "";
    Application application = new Application();

    public String ouFor(String environment) {
        return environment != null && environment.contains("Sandbox") ? "OU=Disabled,DC=test" : "OU=Disabled,DC=prod";
    }

    public AttributeRequest newAttributeRequest(String attributeName, Object attributeValue) {
        AttributeRequest attributeRequest = new ProvisioningPlan.AttributeRequest();
        attributeRequest.setName(attributeName);
        attributeRequest.setOperation(ProvisioningPlan.Operation.Set);
        attributeRequest.setValue(attributeValue);
        return attributeRequest;
    }

    public String getAccountAttributeValue(String applicationName, String nativeIdentity,
            String attributeName) {
        if (idn.accountExistsByNativeIdentity(applicationName, nativeIdentity)) {
            return idn.getRawAccountAttribute(applicationName, nativeIdentity, attributeName);
        }
        return "";
    }

    if (null != plan) {
        log.debug("MoveToDisabledOu begins");
        disabledOu = ouFor(application.getStringAttributeValue("environment"));
        identity = plan.getIdentity();
        String lifecycle = identity.getAttribute("cloudLifecycleState");
        for (AccountRequest accountRequest : plan.getAccountRequests()) {
            String nativeIdentity = accountRequest.getNativeIdentity();
            String description = getAccountAttributeValue("Active Directory", nativeIdentity, "description");
            if (accountRequest.getOperation().equals(AccountRequest.Operation.Disable)
                    && "inactive".equalsIgnoreCase(lifecycle)) {
                accountRequest.add(newAttributeRequest("AC_NewParent", disabledOu));
                accountRequest.add(newAttributeRequest("description", description));
            }
        }
    }
}
