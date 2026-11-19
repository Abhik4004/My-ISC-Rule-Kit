package sailpoint.object;

import java.util.LinkedHashMap;
import java.util.Map;

/** Mock of sailpoint.object.IntegrationConfig (BeforeProvisioning 'integration' input). */
public class IntegrationConfig extends SailPointObject {
    @Override
    public Object toPlain() {
        Map<String, Object> m = new LinkedHashMap<>(basePlain());
        return m;
    }
}
