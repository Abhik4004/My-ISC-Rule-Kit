package sailpoint.tools;

import java.util.Collection;
import java.util.Map;

/** Mock of the commonly used static helpers in sailpoint.tools.Util. */
public final class Util {
    private Util() {}

    public static boolean isNullOrEmpty(String s) { return s == null || s.isEmpty(); }
    public static boolean isNullOrEmpty(Collection<?> c) { return c == null || c.isEmpty(); }
    public static boolean isNullOrEmpty(Map<?, ?> m) { return m == null || m.isEmpty(); }
    public static boolean isNullOrEmpty(Object[] a) { return a == null || a.length == 0; }
    public static boolean isNotNullOrEmpty(String s) { return !isNullOrEmpty(s); }
    public static boolean isEmpty(Collection<?> c) { return c == null || c.isEmpty(); }
    public static boolean isEmpty(String s) { return s == null || s.isEmpty(); }

    public static boolean nullSafeEq(Object a, Object b) { return a == null ? b == null : a.equals(b); }
    public static boolean nullSafeCaseInsensitiveEq(String a, String b) { return a == null ? b == null : a.equalsIgnoreCase(b); }
    public static int nullSafeSize(Collection<?> c) { return c == null ? 0 : c.size(); }

    public static String otos(Object o) { return o == null ? null : o.toString(); }
    public static int otoi(Object o) { return atoi(o == null ? null : o.toString()); }
    public static boolean otob(Object o) { return o instanceof Boolean b ? b : o != null && "true".equalsIgnoreCase(o.toString()); }

    public static int atoi(String s) {
        try { return s == null ? 0 : Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return 0; }
    }
}
