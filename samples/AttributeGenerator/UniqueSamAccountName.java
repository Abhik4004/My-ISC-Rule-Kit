/**
 * Attribute Generator rule: unique sAMAccountName for Active Directory.
 *
 *   first initial + last name, lowercase, letters only, at most 20 characters.
 *   When the value is taken, appends 1, 2, 3 ... (trimming the base so the result still fits in 20).
 *
 * RDK class style: the kit strips the class wrapper. Inputs (identity, application, field, idn, log)
 * are injected by ISC, so they are not declared here.
 */
public class UniqueSamAccountName {

    int MAX_LENGTH = 20;
    int MAX_TRIES = 99;

    public String clean(String s) {
        if (s == null) return "";
        return s.toLowerCase().replaceAll("[^a-z]", "");
    }

    public boolean isUnique(String value) {
        return idn.isUniqueLDAPValue(identity.getName(), application.getName(), "sAMAccountName", value);
    }

    String first = clean(identity.getFirstname());
    String last = clean(identity.getLastname());
    if (first.isEmpty() || last.isEmpty()) {
        log.warn("Missing first or last name for " + identity.getName());
        return null;
    }

    String base = first.substring(0, 1) + last;
    if (base.length() > MAX_LENGTH) base = base.substring(0, MAX_LENGTH);
    if (isUnique(base)) return base;

    for (int i = 1; i <= MAX_TRIES; i++) {
        String suffix = String.valueOf(i);
        String stem = base.length() + suffix.length() > MAX_LENGTH ? base.substring(0, MAX_LENGTH - suffix.length()) : base;
        String candidate = stem + suffix;
        if (isUnique(candidate)) {
            log.debug("sAMAccountName " + base + " taken, using " + candidate);
            return candidate;
        }
    }
    throw new Exception("No unique sAMAccountName for " + identity.getName() + " after " + MAX_TRIES + " tries");
}
