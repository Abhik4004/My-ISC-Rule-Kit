package com.iscrulekit;

import com.iscrulekit.Finding.Severity;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class RuleParser {
    private RuleParser() {}

    private static final Pattern LOOKS_LIKE_XML = Pattern.compile("^\\s*(<\\?xml|<!DOCTYPE|<Rule[\\s>])", Pattern.CASE_INSENSITIVE);
    private static final Pattern SOURCE_OPEN = Pattern.compile("<Source\\b[^>]*>(\\s*<!\\[CDATA\\[)?", Pattern.CASE_INSENSITIVE);

    /**
     * @param text         whole file contents
     * @param fallbackType type to use for raw source (may be null)
     */
    public static ParsedRule parse(String text, RuleType fallbackType) {
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        ParsedRule p = new ParsedRule();
        p.type = fallbackType;
        p.source = text;
        if (!LOOKS_LIKE_XML.matcher(text).find()) return p;

        p.xml = true;
        p.source = "";
        p.hasDoctype = Pattern.compile("<!DOCTYPE\\s+Rule", Pattern.CASE_INSENSITIVE).matcher(text).find();

        String regexSource = null;
        Matcher m = SOURCE_OPEN.matcher(text);
        if (m.find()) {
            int start = m.end();
            p.offset = Scan.count(text.substring(0, start), '\n');
            p.hasCdata = m.group(1) != null;
            int end = text.indexOf(p.hasCdata ? "]]>" : "</Source>", start);
            regexSource = end < 0 ? text.substring(start) : text.substring(start, end);
        }

        Document doc;
        try {
            doc = newBuilder().parse(new InputSource(new StringReader(text)));
        } catch (SAXParseException e) {
            p.xmlOk = false;
            String msg = String.valueOf(e.getMessage()).replaceAll("\\s+", " ").trim();
            String hint = (msg.contains("The content of elements") || msg.contains("must be terminated") || msg.contains("entity") || msg.contains("markup"))
                    ? "  Hint: wrap the whole Source body in <![CDATA[ ... ]]> so characters like < and & are legal." : "";
            p.findings.add(new Finding(Severity.ERROR, "XML001", Math.max(1, e.getLineNumber()),
                    "XML is not well-formed: " + msg + hint));
            p.source = regexSource == null ? "" : regexSource;
            Matcher t = Pattern.compile("<Rule\\b[^>]*\\btype\\s*=\\s*\"([^\"]+)\"", Pattern.CASE_INSENSITIVE).matcher(text);
            if (t.find()) { p.typeName = t.group(1); p.typeFromXml = true; p.type = RuleType.fromName(t.group(1)).orElse(null); }
            Matcher nm = Pattern.compile("<Rule\\b[^>]*\\bname\\s*=\\s*\"([^\"]*)\"", Pattern.CASE_INSENSITIVE).matcher(text);
            if (nm.find()) p.name = nm.group(1);
            return p;
        } catch (Exception e) {
            p.xmlOk = false;
            p.findings.add(new Finding(Severity.ERROR, "XML001", 1, "Could not read the XML: " + e.getMessage()));
            p.source = regexSource == null ? "" : regexSource;
            return p;
        }

        Element root = doc.getDocumentElement();
        Matcher rm = Pattern.compile("<Rule\\b").matcher(text);
        p.ruleLine = rm.find() ? Scan.lineOf(text, rm.start()) : 1;
        if (!"Rule".equals(root.getNodeName())) {
            p.findings.add(new Finding(Severity.ERROR, "XML002", p.ruleLine, "Root element must be <Rule>, found <" + root.getNodeName() + ">."));
        }
        p.hasNameAttr = root.hasAttribute("name");
        p.hasTypeAttr = root.hasAttribute("type");
        p.name = root.getAttribute("name");
        p.language = root.hasAttribute("language") ? root.getAttribute("language") : null;
        if (p.hasTypeAttr) {
            p.typeName = root.getAttribute("type");
            p.typeFromXml = true;
            p.type = RuleType.fromName(p.typeName).orElse(null);
        }
        Element desc = firstChild(root, "Description");
        p.description = desc == null ? "" : desc.getTextContent().trim();
        Element src = firstChild(root, "Source");
        p.hasSource = src != null;
        p.source = src != null ? src.getTextContent() : (regexSource == null ? "" : regexSource);

        Element sig = firstChild(root, "Signature");
        if (sig != null) {
            List<String> args = new ArrayList<>();
            NodeList nl = sig.getElementsByTagName("Argument");
            for (int i = 0; i < nl.getLength(); i++) args.add(((Element) nl.item(i)).getAttribute("name"));
            p.signatureArgs = args;
        }
        return p;
    }

    private static Element firstChild(Element parent, String tag) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && tag.equals(e.getTagName())) return e;
        }
        return null;
    }

    /** XML parser that never touches the network or the file system (the sailpoint.dtd reference is ignored). */
    private static DocumentBuilder newBuilder() throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(false);
        f.setXIncludeAware(false);
        f.setExpandEntityReferences(false);
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        f.setFeature("http://xml.org/sax/features/external-general-entities", false);
        f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        DocumentBuilder b = f.newDocumentBuilder();
        b.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));
        b.setErrorHandler(new DefaultHandler() {
            @Override public void error(SAXParseException e) throws SAXException { throw e; }
            @Override public void fatalError(SAXParseException e) throws SAXException { throw e; }
        });
        return b;
    }
}
