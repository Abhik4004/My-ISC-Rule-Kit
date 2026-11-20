package com.iscrulekit;

/** Builds an ISC-ready rule XML file (CDATA source, DOCTYPE, Signature for BuildMap). */
public final class RuleXml {
    private RuleXml() {}

    public static String build(String name, RuleType type, String description, String source) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version='1.0' encoding='UTF-8'?>\n");
        sb.append("<!DOCTYPE Rule PUBLIC \"sailpoint.dtd\" \"sailpoint.dtd\">\n");
        sb.append("<Rule name=\"").append(esc(name == null || name.isBlank() ? "My Rule" : name))
                .append("\" type=\"").append(esc(type == null ? "" : type.xmlName)).append("\">\n");
        sb.append("  <Description>").append(esc(description == null || description.isBlank() ? "Describe your rule here." : description))
                .append("</Description>\n");
        if (type != null && !type.signature().isEmpty()) {
            sb.append("  <Signature>\n    <Inputs>\n");
            for (String[] a : type.signature()) sb.append("      <Argument name=\"").append(a[0]).append("\" type=\"").append(a[1]).append("\"/>\n");
            sb.append("    </Inputs>\n  </Signature>\n");
        }
        String body = source.strip().replace("]]>", "]]]]><![CDATA[>");
        sb.append("  <Source><![CDATA[\n").append(body).append("\n  ]]></Source>\n</Rule>\n");
        return sb.toString();
    }

    /** The XML for a loaded rule, plus what was stripped (class wrapper, re-declared inputs). */
    public record Built(String name, String description, String xml, ClassUnwrapper.Result unwrap) {}

    /**
     * ISC-ready XML for any loaded rule: XML, raw BeanShell, RDK-style class, or a file made by 'import'.
     * Name and description: the override, else the XML / import header, else the class name, else a default.
     */
    public static Built fromRule(ParsedRule p, String nameOverride, String descOverride) {
        String[] imported = p.xml ? null : Importer.headerInfo(p.source);
        String source = imported != null ? Importer.withoutHeader(p.source) : p.source;
        ClassUnwrapper.Result u = ClassUnwrapper.unwrap(source, p.type == null ? java.util.List.of() : p.type.inputs);
        String name = nameOverride != null ? nameOverride : !p.name.isEmpty() ? p.name : imported != null && imported[0] != null ? imported[0]
                : u.className().isEmpty() ? "My Rule" : u.className();
        String desc = descOverride != null ? descOverride : imported != null && imported[2] != null ? imported[2] : p.description;
        return new Built(name, desc, build(name, p.type, desc, ClassUnwrapper.tidy(u.source())), u);
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** Suggested file name following the ISC review convention. */
    public static String fileName(RuleType type, String name) {
        String n = (name == null || name.isBlank() ? "Rule" : name).replaceAll("[\\\\/:*?\"<>|]", "_");
        return "Rule - " + (type == null ? "Type" : type.xmlName) + " - " + n + ".xml";
    }
}
