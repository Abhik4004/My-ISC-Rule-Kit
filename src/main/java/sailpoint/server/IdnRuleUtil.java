package sailpoint.server;

/**
 * Marker so that {@code import sailpoint.server.IdnRuleUtil;} resolves. The real object is not mocked as a Java class:
 * inside tests the {@code idn} variable is a BeanShell scripted object whose methods return the values you list under
 * the "idn" key of a test case (see README), so any IdnRuleUtil method can be mocked without code changes.
 */
public class IdnRuleUtil {
}
