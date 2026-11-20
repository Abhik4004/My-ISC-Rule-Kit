/**

    * The following SailPoint class objects are available for a Before Provisioning Rule:

    * log - Logger to log statements. Note: This executes in the cloud, and logging is currently not exposed to anyone other than SailPoint.
    * idn - sailpoint.server.IdnRuleUtil - Provides a read-only starting point for using the SailPoint API. From this passed reference, the rule can interrogate the Identity Security Cloud data model including identities or account information via helper methods as described in IdnRuleUtil.
    * plan - sailpoint.object.ProvisioningPlan - A set of provisioning instructions which are sent to the source connectors.
    * application - sailpoint.object.Application - Read-only reference to application object that represents the source to which provisioning is being done.

*/

import java.util.Date;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.TimeZone;
import java.time.Year;
import java.util.Random;
import sailpoint.object.Identity;
import sailpoint.object.ProvisioningPlan;
import sailpoint.object.ProvisioningPlan.AccountRequest;
import sailpoint.object.ProvisioningPlan.AttributeRequest;
import sailpoint.object.Attributes;
import sailpoint.rule.Account;

List accountRequests = null;
List existingMemberOfValueList = new ArrayList();
Identity identity = null;
String identityName = "";
String cloudLifecycleState = "";
String isEmergencyDisabled = "";
String identificationNumber = "";
String isBrb = "";
String affiliation = "";
String primaryAffiliation = "";
String nativeIdentity = "";
String is90daysPostInactive = "";
String currentIdentityId = "";
String msExchRecipientTypeDetails = "";
String mailboxEnabledValue ="2147483648";
String mail ="";
String description = "";
String randomPasswordString="";
String LOCAL_SEPARATION_SOURCE = "Local Separation Source [source]";
String UCSFMC_AD_SOURCE_NAME= "Medical Center Active Directory [source]";
String TERM_OU = "";
String ALDA_OU = "";
String REGULAR_USERS_ACTIVE_OU = "";
String TEMP_USERS_ACTIVE_OU = "";
String DISABLED_OU = "";
String TEMP_90_DAY_HOLD_OU = "";
String BIRTHRIGHT_GROUP = "";
String environmentString = "";
String hasMailbox = "";
String requiresMailboxOn = "";
String sAMAccountName = "";
String userPrincipalName = "";
String ucsfmcAd180DayInactivityCheck = "";
String ucsfmcAd60DayNewUserPasswordResetCheck = "";


environmentString = application.getStringAttributeValue("environment");

if(environmentString.contains("Sandbox")) {
    //Assign values of the variables as per Sandbox environment
    TERM_OU = "OU=Termination,DC=ql1,DC=ucsfmedicalcenter,DC=org";
    ALDA_OU = "OU=ALDA,DC=ql1,DC=ucsfmedicalcenter,DC=org";
    REGULAR_USERS_ACTIVE_OU = "OU=MedCenterUsers,DC=ql1,DC=ucsfmedicalcenter,DC=org";
    TEMP_USERS_ACTIVE_OU = "OU=Temp Users,OU=MedCenterUsers,DC=ql1,DC=ucsfmedicalcenter,DC=org";
    DISABLED_OU = "OU=Disabled Accounts,OU=MedCenterUsers,DC=ql1,DC=ucsfmedicalcenter,DC=org";
    TEMP_90_DAY_HOLD_OU = "OU=Temp 90-Day Hold,OU=MedCenterUsers,DC=ql1,DC=ucsfmedicalcenter,DC=org";
    BIRTHRIGHT_GROUP = "CN=DualAuthUsersMedCtr,OU=Application Accounts,OU=MedCenterUsers,DC=ql1,DC=ucsfmedicalcenter,DC=org";
}
else if(environmentString.contains("Stage")){
    //Assign values of the variables as per Stage environment
    TERM_OU = "OU=Termination,DC=ql1,DC=ucsfmedicalcenter,DC=org";
    ALDA_OU = "OU=ALDA,DC=ql1,DC=ucsfmedicalcenter,DC=org";
    REGULAR_USERS_ACTIVE_OU = "OU=MedCenterUsers,DC=ql1,DC=ucsfmedicalcenter,DC=org";
    TEMP_USERS_ACTIVE_OU = "OU=Temp Users,OU=MedCenterUsers,DC=ql1,DC=ucsfmedicalcenter,DC=org";
    DISABLED_OU = "OU=Disabled Accounts,OU=MedCenterUsers,DC=ql1,DC=ucsfmedicalcenter,DC=org";
    TEMP_90_DAY_HOLD_OU = "OU=Temp 90-Day Hold,OU=MedCenterUsers,DC=ql1,DC=ucsfmedicalcenter,DC=org";
    BIRTHRIGHT_GROUP = "CN=DualAuthUsersMedCtr,OU=Application Accounts,OU=MedCenterUsers,DC=ql1,DC=ucsfmedicalcenter,DC=org";
}
else {
    //Assign values of the variables as per Production environment
    TERM_OU = "OU=Termination,DC=ucsfmedicalcenter,DC=org";
    ALDA_OU = "OU=ALDA,DC=ucsfmedicalcenter,DC=org";
    REGULAR_USERS_ACTIVE_OU = "OU=MedCenterUsers,DC=ucsfmedicalcenter,DC=org";
    TEMP_USERS_ACTIVE_OU = "OU=Temp Users,OU=MedCenterUsers,DC=ucsfmedicalcenter,DC=org";
    DISABLED_OU = "OU=Disabled accounts,OU=MedCenterUsers,DC=ucsfmedicalcenter,DC=org";
    TEMP_90_DAY_HOLD_OU = "OU=Temp 90-Day Hold,OU=MedCenterUsers,DC=ucsfmedicalcenter,DC=org";
    BIRTHRIGHT_GROUP = "CN=DualAuthUsersMedCtr,OU=Application Accounts,OU=MedCenterUsers,DC=ucsfmedicalcenter,DC=org";
}


  /**
     * The method is used to return the attributeRequest
     *
     * @param attributeName
     * @param attributeValue
     * @return AttributeRequest attributeRequest
     */
    public AttributeRequest newAttributeRequest(String attributeName, Object attributeValue){

        AttributeRequest attributeRequest = new ProvisioningPlan.AttributeRequest();
        attributeRequest.setName(attributeName);
        attributeRequest.setOperation(ProvisioningPlan.Operation.Set);
        attributeRequest.setValue(attributeValue);

        return attributeRequest;
    }

    /**
     * The method is used to return the accountAttribute
     *
     * @param applicationName
     * @param nativeIdentity
     * @param attributeName
     * @return String accountAttribute
     */
    public String getAccountAttributeValue(String applicationName, String nativeIdentity,String attributeName){
        boolean accountExists = idn.accountExistsByNativeIdentity(applicationName, nativeIdentity);
        if (accountExists)
        {
            String accountAttribute = idn.getRawAccountAttribute(applicationName, nativeIdentity, attributeName);
            return accountAttribute;

        }
        else
        {

            return "";

        }

    }

    /**
     * The method is used to return the accountAttribute
     *
     * @param applicationName
     * @param nativeIdentity
     * @param attributeName
     * @return List accountAttribute
     */
    public List getAccountAttributeValuelist(String applicationName, String nativeIdentity,String attributeName){
        boolean accountExists = idn.accountExistsByNativeIdentity(applicationName, nativeIdentity);
        if (accountExists) {
            Object accountAttribute = idn.getRawAccountAttribute(applicationName, nativeIdentity, attributeName);
            if(accountAttribute != null) {
                List accountAttributeList = new ArrayList();
                if (accountAttribute instanceof List) {
                    accountAttributeList = accountAttribute;
                }
                else if (accountAttribute instanceof String) {
                    accountAttributeList.add(accountAttribute);
                }
                return accountAttributeList;
            }
            return null;
        }
        else {
            return null;
        }
    }


    /**
     * The method is used to generate random password
     *
     * @return random password
     */
    public String generateRandomPasswordString(){

        String CHARACTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#$%^&*";
        Random random = new Random();
        StringBuilder password = new StringBuilder();
        int length = 15;
        for (int i = 0; i <= length; i++) {
            password.append(CHARACTERS.charAt(random.nextInt(CHARACTERS.length())));
        }
        return password.toString();
    }

    if(null!=plan){
        accountRequests = plan.getAccountRequests();
        identity = plan.getIdentity();
        log.debug("UCSFMC Active Directory BeforeProvisioningRule Begins");
        if(null!=accountRequests && !accountRequests.isEmpty() && null!=identity){
            log.debug("AccountRequests assimilated and is not empty");
            currentIdentityId = identity.getId();
            identityName = identity.getName();
            cloudLifecycleState = (identity.getAttribute("cloudLifecycleState") != null ? (String) identity.getAttribute("cloudLifecycleState") : "");
            identificationNumber = (identity.getAttribute("identificationNumber") != null ? (String) identity.getAttribute("identificationNumber") : "");
            affiliation = (identity.getAttribute("affiliation") != null ? (String) identity.getAttribute("affiliation") : "");
            primaryAffiliation = (identity.getAttribute("primaryUserAffiliation") != null ? (String) identity.getAttribute("primaryUserAffiliation") : "");
            isEmergencyDisabled = getAccountAttributeValue(LOCAL_SEPARATION_SOURCE,identificationNumber,"emergencyDisabled");
            isBrb = getAccountAttributeValue(LOCAL_SEPARATION_SOURCE,identificationNumber,"brb");
            is90daysPostInactive = (identity.getAttribute("90DaysPostInactive") != null ? (String) identity.getAttribute("90DaysPostInactive") : "");
            hasMailbox = (identity.getAttribute("hasMailbox") != null ? (String) identity.getAttribute("hasMailbox") : "");
            requiresMailboxOn = (identity.getAttribute("requiresMailboxOn") != null ? (String) identity.getAttribute("requiresMailboxOn") : "");
            ucsfmcAd180DayInactivityCheck = (identity.getAttribute("ucsfmcAd180DayInactivityCheck") != null ? (String) identity.getAttribute("ucsfmcAd180DayInactivityCheck") : "");
            ucsfmcAd60DayNewUserPasswordResetCheck = (identity.getAttribute("ucsfmcAd60DayNewUserPasswordResetCheck") != null ? (String) identity.getAttribute("ucsfmcAd60DayNewUserPasswordResetCheck") : "");

            log.debug("UCSFID:"+identificationNumber+" isEmergencyDisabled:"+isEmergencyDisabled+" isBrb:"+isBrb);

            for(AccountRequest accountRequest : accountRequests) {
                if(null!=accountRequest) {
                    nativeIdentity = accountRequest.getNativeIdentity();
                    msExchRecipientTypeDetails = getAccountAttributeValue(UCSFMC_AD_SOURCE_NAME,nativeIdentity,"msExchRecipientTypeDetails");
                    sAMAccountName = getAccountAttributeValue(UCSFMC_AD_SOURCE_NAME, nativeIdentity, "sAMAccountName");
                    userPrincipalName = getAccountAttributeValue(UCSFMC_AD_SOURCE_NAME, nativeIdentity, "userPrincipalName");

                    HashMap map = new HashMap();
                    map.put("currentIdentityId",currentIdentityId);
                    map.put("isEmergencyDisabled",isEmergencyDisabled);
                    map.put("isBrb",isBrb);
                    map.put("affiliation",affiliation);
                    map.put("cloudLifecycleState",cloudLifecycleState);
                    map.put("primaryAffiliation",primaryAffiliation);
                    map.put("is90daysPostInactive",is90daysPostInactive);
                    map.put("identificationNumber", identificationNumber);
                    map.put("nativeIdentity", nativeIdentity);
                    map.put("requiresMailboxOn", requiresMailboxOn);
                    map.put("hasMailbox", hasMailbox);
                    map.put("sAMAccountName", sAMAccountName);
                    map.put("userPrincipalName", userPrincipalName);

                    if(mailboxEnabledValue.equals(msExchRecipientTypeDetails)){
                        map.put("mailboxStatus","Mailbox exists on UCSFMC Domain");
                    }
                    log.debug("AccountRequest custom arguments assimilated:"+map.toString());
                    Attributes attrArg = new Attributes();
                    attrArg.setMap(map);
                    accountRequest.setArguments(attrArg);
                    log.debug("AcccountRequest custom arguments assigned.");
                    log.debug("msExchRecipientTypeDetails: " + msExchRecipientTypeDetails);


                    /**Logic to set random password for the account and move the user to Temp OU if the account is mail enabled and
                    to Disabled OU is the account is not mail enabled during leaver**/
                    if((accountRequest.getOperation().equals(AccountRequest.Operation.Disable) || accountRequest.getOperation().equals(AccountRequest.Operation.Enable)) && "false".equalsIgnoreCase(is90daysPostInactive) && "inactive".equalsIgnoreCase(cloudLifecycleState)) {
                        log.debug("In block to set random password for the account and move the user to Temp OU if the account is mail enabled and to Disabled OU is the account is not mail enabled during leaver");
                        log.debug("OP: "+accountRequest.getOperation());
                        existingMemberOfValueList = getAccountAttributeValuelist(UCSFMC_AD_SOURCE_NAME,nativeIdentity,"memberOf");
                        if(existingMemberOfValueList != null){
                            for(String existingmemberOfValue : existingMemberOfValueList){
                                if(existingmemberOfValue != null && existingmemberOfValue.toLowerCase().equals(BIRTHRIGHT_GROUP.toLowerCase())){
                                    AttributeRequest attReq = new AttributeRequest();
                                    attReq.setOperation(ProvisioningPlan.Operation.Remove);
                                    attReq.setName("memberOf");
                                    attReq.setValue(BIRTHRIGHT_GROUP);
                                    accountRequest.add(attReq);
                                    break;
                                }
                            }
                        }
                        randomPasswordString = generateRandomPasswordString();
                        accountRequest.add(newAttributeRequest("password",randomPasswordString));
                        accountRequest.add(newAttributeRequest("manager",""));
                        accountRequest.add(newAttributeRequest("msExchExtensionAttribute18",""));
                        if(mailboxEnabledValue.equals(msExchRecipientTypeDetails)){
                            accountRequest.add(newAttributeRequest("AC_NewParent",TEMP_90_DAY_HOLD_OU));
                            accountRequest.add(newAttributeRequest("msExchHideFromAddressLists","true"));
                            }
                        else {
                            accountRequest.add(newAttributeRequest("AC_NewParent",DISABLED_OU));
                        }
                        if (accountRequest.getOperation().equals(AccountRequest.Operation.Enable)) {
                            accountRequest.setOperation(ProvisioningPlan.AccountRequest.Operation.Modify);
                            log.debug("Operation is changed from enable to modify");
                        }
                    }

                    //Logic to move the user to Termination OU when the user is emergency terminated
                    if((accountRequest.getOperation().equals(AccountRequest.Operation.Disable) || accountRequest.getOperation().equals(AccountRequest.Operation.Enable)) && "true".equalsIgnoreCase(isEmergencyDisabled)) {
                        log.debug("In block for emergencey termination");
                        log.debug("OP: "+ accountRequest.getOperation());
                        randomPasswordString = generateRandomPasswordString();
                        accountRequest.add(newAttributeRequest("password",randomPasswordString));
                        accountRequest.add(newAttributeRequest("AC_NewParent",TERM_OU));
                        if (accountRequest.getOperation().equals(AccountRequest.Operation.Enable)) {
                            accountRequest.setOperation(ProvisioningPlan.AccountRequest.Operation.Modify);
                            log.debug("Operation is changed from enable to modify");
                        }
                    }

                    //Logic to move the user to ALDA OU when the user is placed under investigatory leave
                    if((accountRequest.getOperation().equals(AccountRequest.Operation.Disable) || accountRequest.getOperation().equals(AccountRequest.Operation.Enable)) && "true".equalsIgnoreCase(isBrb)) {
                        log.debug("In block for BRB");
                        log.debug("OP: "+ accountRequest.getOperation());
                        randomPasswordString = generateRandomPasswordString();
                        accountRequest.add(newAttributeRequest("password",randomPasswordString));
                        accountRequest.add(newAttributeRequest("AC_NewParent",ALDA_OU));
                        if (accountRequest.getOperation().equals(AccountRequest.Operation.Enable)) {
                            accountRequest.setOperation(ProvisioningPlan.AccountRequest.Operation.Modify);
                            log.debug("Operation is changed from enable to modify");
                        }
                        log.debug("OP:"+accountRequest.getOperation()+" isBrb:"+isBrb+" ALDA OU Movement Invoked");
                    }

                    //Logic to move the user to disabled OU during day 90
                    if((accountRequest.getOperation().equals(AccountRequest.Operation.Enable) || accountRequest.getOperation().equals(AccountRequest.Operation.Disable)) && "true".equalsIgnoreCase(is90daysPostInactive)){
                        log.debug("In block for day 90");
                        log.debug("OP: "+ accountRequest.getOperation());
                        if (accountRequest.getOperation().equals(AccountRequest.Operation.Enable)) {
                            accountRequest.setOperation(ProvisioningPlan.AccountRequest.Operation.Modify);

                        }
                        accountRequest.add(newAttributeRequest("AC_NewParent",DISABLED_OU));
                        accountRequest.add(newAttributeRequest("description","Disabled by SailPoint"));
                    }

                    /**Logic to move the user back to Active OU during Emergency Activation and Return from Investigatory leave.
                    Enable operation is triggered from workflow during Rehire, Emergency Activation, Return from Investigatory leave**/
                    if(accountRequest.getOperation().equals(AccountRequest.Operation.Enable) && "active".equalsIgnoreCase(cloudLifecycleState) && (isEmergencyDisabled.isEmpty() || "false".equalsIgnoreCase(isEmergencyDisabled)) && (isBrb.isEmpty() || "false".equalsIgnoreCase(isBrb))){
                        log.debug("In block to move the user back to Active OU during Rehire, Emergency Activation and Return from Investigatory leave");
                        log.debug("OP: "+ accountRequest.getOperation());
                        if ("affiliate".equalsIgnoreCase(primaryAffiliation)) {
                            if ("newemp".equalsIgnoreCase(affiliation) || "acadap".equalsIgnoreCase(affiliation)) {
                                accountRequest.add(newAttributeRequest("AC_NewParent", REGULAR_USERS_ACTIVE_OU));
                                log.info("User is newemp or acadap, moved to regular users OU");
                            }
                            else {
                                accountRequest.add(newAttributeRequest("AC_NewParent", TEMP_USERS_ACTIVE_OU));
                                log.info("User is an affiliate, moved to temp users OU");
                            }
                        }
                        else {
                            accountRequest.add(newAttributeRequest("AC_NewParent", REGULAR_USERS_ACTIVE_OU));
                            log.info("User is does not have primary affiliation = affiliate, moved to regular users OU");
                        }
                        existingMemberOfValueList = getAccountAttributeValuelist(UCSFMC_AD_SOURCE_NAME,nativeIdentity,"memberOf");
                        if(existingMemberOfValueList != null){
                            for(String existingmemberOfValue : existingMemberOfValueList){
                                if(existingmemberOfValue != null && existingmemberOfValue.toLowerCase().equals(BIRTHRIGHT_GROUP.toLowerCase())){
                                    accountRequest.setOperation(ProvisioningPlan.AccountRequest.Operation.Enable);
                                    log.debug("Operation is changed to enable");
                                    break;
                                }
                                else {
                                    accountRequest.setOperation(ProvisioningPlan.AccountRequest.Operation.Modify);
                                    log.debug("Operation is changed to modify");
                                }
                            }
                        }else{
                            accountRequest.setOperation(ProvisioningPlan.AccountRequest.Operation.Modify);
                            log.debug("Operation is changed to modify");
                        }
                        log.debug("OP:"+accountRequest.getOperation()+" Enable Account and OU Movement Invoked");
                    }


                    /**Logic to enable the account and move the user back to Active OU when user is added to Birthright group and disable the account when user is removed from Birthright group **/
                    if(accountRequest.getOperation().equals(AccountRequest.Operation.Modify)){
                        log.debug("In block that enable  to enable the account when user is added to Birthright group and disable the account when user is removed from Birthright group");
                        log.debug("OP: "+accountRequest.getOperation());
                        AttributeRequest attributeRequest = accountRequest.getAttributeRequest("memberOf");
                        if(null!=attributeRequest) {
                            Object attributeRequestObj = attributeRequest.getValue();
                            List attributeRequestValueList = new ArrayList();

                            if (attributeRequestObj instanceof List) {
                                attributeRequestValueList = attributeRequestObj;
                            }
                            else {
                                attributeRequestValueList.add(attributeRequestObj);
                            }
                            log.debug("Looping through attributeRequestValueList");
                            for(String memberOfValue : attributeRequestValueList){
                                if(attributeRequest.getOperation().name().equals("Remove") && memberOfValue.equalsIgnoreCase(BIRTHRIGHT_GROUP)) {

                                    if ((affiliation.contains("student") || affiliation.contains("applicant")) && "active".equalsIgnoreCase(cloudLifecycleState)){
                                        accountRequest.remove(attributeRequest);
                                        description = getAccountAttributeValue(UCSFMC_AD_SOURCE_NAME,nativeIdentity,"description");
                                        accountRequest.add(newAttributeRequest("description", description));
                                    }
                                    else if("inactive".equalsIgnoreCase(cloudLifecycleState)){
                                        accountRequest.remove(attributeRequest);
                                        description = getAccountAttributeValue(UCSFMC_AD_SOURCE_NAME,nativeIdentity,"description");
                                        accountRequest.add(newAttributeRequest("description", description));
                                    }
                                    else {
                                        log.debug("Birthright group is being removed, changing operation to disable");
                                        accountRequest.setOperation(ProvisioningPlan.AccountRequest.Operation.Disable);
                                        log.debug("Operation is changed from modify to disable");
                                    }
                                }
                                if (attributeRequest.getOperation().name().equals("Add") && (memberOfValue.equalsIgnoreCase(BIRTHRIGHT_GROUP))){
                                    log.debug("Birthright group is being added, changing operation to enable");
                                    if("active".equalsIgnoreCase(cloudLifecycleState)  && (isEmergencyDisabled.isEmpty() || "false".equalsIgnoreCase(isEmergencyDisabled)) && (isBrb.isEmpty() || "false".equalsIgnoreCase(isBrb))){
                                        accountRequest.setOperation(ProvisioningPlan.AccountRequest.Operation.Enable);
                                        if ("affiliate".equalsIgnoreCase(primaryAffiliation)) {
                                            if ("newemp".equalsIgnoreCase(affiliation) || "acadap".equalsIgnoreCase(affiliation)) {
                                                accountRequest.add(newAttributeRequest("AC_NewParent", REGULAR_USERS_ACTIVE_OU));
                                                log.info("User is newemp or acadap, moved to regular users OU");
                                            }
                                            else {
                                                accountRequest.add(newAttributeRequest("AC_NewParent", TEMP_USERS_ACTIVE_OU));
                                                log.info("User is an affiliate, moved to temp users OU");
                                            }
                                        }
                                        else {
                                        accountRequest.add(newAttributeRequest("AC_NewParent", REGULAR_USERS_ACTIVE_OU));
                                        log.info("User is does not have primary affiliation = affiliate, moved to regular users OU");
                                        }
                                    }
                                    log.debug("Operation is changed from modify to enable");
                                }

								if (attributeRequest.getOperation().name().equals("Add") && (!memberOfValue.equalsIgnoreCase(BIRTHRIGHT_GROUP))) {
									log.debug("testing non birthright group");
									if("active".equalsIgnoreCase(cloudLifecycleState)  && (isEmergencyDisabled.isEmpty() || "false".equalsIgnoreCase(isEmergencyDisabled)) && (isBrb.isEmpty() || "false".equalsIgnoreCase(isBrb))){
										accountRequest.setOperation(ProvisioningPlan.AccountRequest.Operation.Enable);
										if ("affiliate".equalsIgnoreCase(primaryAffiliation)) {
                                            if ("newemp".equalsIgnoreCase(affiliation) || "acadap".equalsIgnoreCase(affiliation)) {
												 if ("newemp".equalsIgnoreCase(affiliation) || "acadap".equalsIgnoreCase(affiliation)) {
													 log.info("Checking if the user MEdCenter Account is disabled or enabled");
													 Account adAccount = getSourceAttributeBySourceName(UCSFMC_AD_SOURCE_NAME, "status");
													 log.debug(adAccount);
													 accountRequest.add(newAttributeRequest("AC_NewParent", REGULAR_USERS_ACTIVE_OU));
													log.info("User is newemp or acadap, moved to regular users OU");
												 } else {
														accountRequest.add(newAttributeRequest("AC_NewParent", TEMP_USERS_ACTIVE_OU));
														log.info("User is an affiliate, moved to temp users OU");
														log.info("Checking if the user MEdCenter Account is disabled or enabled");
													 Account adAccount = getSourceAttributeBySourceName(UCSFMC_AD_SOURCE_NAME, "status");
													 log.debug(adAccount);
													}
											}
									} else {
                                        accountRequest.add(newAttributeRequest("AC_NewParent", REGULAR_USERS_ACTIVE_OU));
                                        log.info("User is does not have primary affiliation = affiliate, moved to regular users OU");
                                        }
								}
								log.debug("Operation is changed from modify to enable");
                            }
							}
                        }
                    }
                }
            }
        }
        log.debug("UCSFMC Active Directory BeforeProvisioningRule Ends");
    }
