/**
 * Before Provisioning rule for Active Directory leavers and rehires.
 *
 *   Disable -> move to the Disabled OU, clear manager, set a description, hide from address lists
 *              when the account has a mailbox, and remove every group except Domain Users.
 *   Enable  -> move back to the Users OU (Contractors OU for contractors) and clear the description.
 *   Anything else is left alone.
 */
import java.util.ArrayList;
import java.util.List;
import sailpoint.object.Identity;
import sailpoint.object.ProvisioningPlan;
import sailpoint.object.ProvisioningPlan.AccountRequest;
import sailpoint.object.ProvisioningPlan.AttributeRequest;

public class AdLeaverRehire {

    String AD_SOURCE = "Active Directory [source]";
    String KEEP_GROUP = "CN=Domain Users,CN=Users,DC=example,DC=org";

    public String ou(String name) {
        String env = application.getStringAttributeValue("environment");
        String dc = env != null && env.contains("Sandbox") ? "DC=test,DC=example,DC=org" : "DC=example,DC=org";
        return "OU=" + name + "," + dc;
    }

    public AttributeRequest set(String name, Object value) {
        return new AttributeRequest(name, ProvisioningPlan.Operation.Set, value);
    }

    public List currentGroups(String nativeIdentity) {
        Object v = idn.getRawAccountAttribute(AD_SOURCE, nativeIdentity, "memberOf");
        List groups = new ArrayList();
        if (v instanceof List) groups.addAll((List) v);
        else if (v instanceof String && !((String) v).isEmpty()) groups.add(v);
        return groups;
    }

    if (plan == null || plan.getAccountRequests() == null) return;
    Identity identity = plan.getIdentity();
    String employeeType = identity == null ? null : (String) identity.getAttribute("employeeType");

    for (AccountRequest req : plan.getAccountRequests()) {
        String nativeIdentity = req.getNativeIdentity();

        if (AccountRequest.Operation.Disable.equals(req.getOperation())) {
            log.info("Leaver: disabling " + nativeIdentity);
            req.add(set("AC_NewParent", ou("Disabled")));
            req.add(set("manager", null));
            req.add(set("description", "Disabled by ISC"));
            if (idn.getRawAccountAttribute(AD_SOURCE, nativeIdentity, "mail") != null) {
                req.add(set("msExchHideFromAddressLists", "true"));
            }
            List remove = new ArrayList();
            for (Object g : currentGroups(nativeIdentity)) {
                if (!KEEP_GROUP.equalsIgnoreCase((String) g)) remove.add(g);
            }
            if (!remove.isEmpty()) {
                req.add(new AttributeRequest("memberOf", ProvisioningPlan.Operation.Remove, remove));
            }
        }
        else if (AccountRequest.Operation.Enable.equals(req.getOperation())) {
            log.info("Rehire: enabling " + nativeIdentity);
            String target = "contractor".equalsIgnoreCase(employeeType) ? "Contractors" : "Users";
            req.add(set("AC_NewParent", ou(target)));
            req.add(set("description", ""));
        }
    }
}
