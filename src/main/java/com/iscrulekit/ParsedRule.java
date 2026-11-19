package com.iscrulekit;

import java.util.ArrayList;
import java.util.List;

/** A rule file after parsing: either a full ISC {@code <Rule>} XML document or raw BeanShell/Java source. */
public final class ParsedRule {
    public boolean xml;
    public boolean xmlOk = true;
    /** Resolved rule type (null if unknown or unsupported). */
    public RuleType type;
    /** Raw type string from the XML attribute, if any (kept so unsupported types can be reported). */
    public String typeName;
    public boolean typeFromXml;
    public String name = "";
    public String description = "";
    public String language;
    /** The rule source (CDATA content, or the whole file when raw). */
    public String source = "";
    /** Number of lines in the file before the first source line, so source line N is file line N + offset. */
    public int offset;
    public boolean hasCdata;
    public boolean hasDoctype;
    public boolean hasSource;
    public boolean hasNameAttr;
    public boolean hasTypeAttr;
    public int ruleLine = 1;
    /** Argument names from {@code <Signature><Inputs>}, or null when there is no Signature. */
    public List<String> signatureArgs;
    /** Problems found while parsing (reported by the validator). */
    public final List<Finding> findings = new ArrayList<>();
}
