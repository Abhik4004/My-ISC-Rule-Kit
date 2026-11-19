/**
 * Before Provisioning Rule: moves disabled AD accounts to the Disabled OU.
 *
 * RDK / Eclipse style (class wrapper, inputs re-declared as fields). This version has two deliberate bugs
 * so you can see what the validator and tester report.
 */
import java.util.*;
import sailpoint.object.Identity;
import sailpoint.object.Application;
import sailpoint.object.ProvisioningPlan;
import sailpoint.object.ProvisioningPlan.AccountRequest;
import sailpoint.object.ProvisioningPlan.AttributeRequest;

public class MoveToDisabledOuTest {

    Identity identity = null;
    String DISABLED_OU = "";
    Application application = new Application();

    // BUG 1: ISC never calls execute(), so DISABLED_OU is never set.
    public void execute() {
        String env = application.getStringAttributeValue("environment");
        DISABLED_OU = env.contains("Sandbox") ? "OU=Disabled,DC=test" : "OU=Disabled,DC=prod";
    }

    public AttributeRequest newAttributeRequest(String attributeName, Object attributeValue) {
        AttributeRequest attributeRequest = new ProvisioningPlan.AttributeRequest();
        attributeRequest.setName(attributeName);
        attributeRequest.setOperation(ProvisioningPlan.Operation.Set);
        attributeRequest.setValue(attributeValue);
        return attributeRequest;
    }

    // BUG 2: the method name is mistyped (doubled), so the calls below do not resolve.
    public String getAccountAttributeValuegetAccountAttributeValue(String applicationName, String nativeIdentity,
            String attributeName) {
        if (idn.accountExistsByNativeIdentity(applicationName, nativeIdentity)) {
            return idn.getRawAccountAttribute(applicationName, nativeIdentity, attributeName);
        }
        return "";
    }

    if (null != plan) {
        log.debug("MoveToDisabledOu begins");
        identity = plan.getIdentity();
        String lifecycle = identity.getAttribute("cloudLifecycleState");
        for (AccountRequest accountRequest : plan.getAccountRequests()) {
            String nativeIdentity = accountRequest.getNativeIdentity();
            String description = getAccountAttributeValue("Active Directory", nativeIdentity, "description");
            if (accountRequest.getOperation().equals(AccountRequest.Operation.Disable)
                    && "inactive".equalsIgnoreCase(lifecycle)) {
                accountRequest.add(newAttributeRequest("AC_NewParent", DISABLED_OU));
                accountRequest.add(newAttributeRequest("description", description));
            }
        }
    }
}
