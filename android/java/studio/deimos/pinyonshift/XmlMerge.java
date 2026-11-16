package studio.deimos.pinyonshift;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The XML merge of tools/build-mod-archives.py (merge_xml), byte for byte:
 * the patch's elements are merged into the base by tag and key attribute
 * (id, name, model, key or type, else position among their tag), attributes
 * and text replace the base's, `pinyon-add="true"` appends and
 * `pinyon-remove="true"` removes. The tree is read and written as Python's
 * ElementTree reads and writes it (expat's line-end and attribute
 * normalisation, comments kept inside the root, " />" for empty elements),
 * so the headset builds the same file as the PC launcher. Plain Java, so
 * tools can test it on a PC.
 */
final class XmlMerge {
    private static final String[] KEY_ATTRIBUTES = {"id", "name", "model", "key", "type"};
    private static final String REMOVE_ATTRIBUTE = "pinyon-remove";
    private static final String ADD_ATTRIBUTE = "pinyon-add";

    static final class XmlException extends Exception {
        XmlException(String message) {
            super(message);
        }
    }

    /** An element, or a comment (tag null, its text the comment). */
    static final class Node {
        final String tag;
        final LinkedHashMap<String, String> attributes = new LinkedHashMap<>();
        final List<Node> children = new ArrayList<>();
        String text;
        String tail;

        Node(String tag) {
            this.tag = tag;
        }

        boolean isComment() {
            return tag == null;
        }
    }

    private XmlMerge() {}

    /** The patch merged into the base; both UTF-8 XML documents. */
    static byte[] merge(byte[] base, byte[] patch) throws XmlException {
        String baseText = decode(base);
        String newline = baseText.contains("\r\n") ? "\r\n" : "\n";
        Node root = new Parser(baseText).document();
        Node patchRoot = new Parser(decode(patch)).document();
        if (!patchRoot.tag.equals(root.tag)) {
            throw new XmlException("merge root <" + patchRoot.tag + "> does not match <" + root.tag + ">");
        }
        mergeElement(root, patchRoot);
        StringBuilder out = new StringBuilder();
        Matcher declaration = Pattern.compile("^<\\?xml[^>]*\\?>").matcher(stripBom(baseText).replaceFirst("^\\s+", ""));
        if (declaration.find()) out.append(declaration.group()).append('\n');
        serialize(root, out);
        String text = out.toString().replace("\n", newline) + newline;
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static String decode(byte[] data) {
        return new String(data, StandardCharsets.UTF_8);
    }

    private static String stripBom(String text) {
        return text.startsWith("\uFEFF") ? text.substring(1) : text;
    }

    // ---- Merge (_merge_element) ------------------------------------------

    private static String[] key(Node element) {
        Map<String, String> lowered = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : element.attributes.entrySet()) {
            lowered.put(entry.getKey().toLowerCase(Locale.ROOT), entry.getValue());
        }
        for (String attribute : KEY_ATTRIBUTES) {
            if (lowered.containsKey(attribute)) return new String[] {attribute, lowered.get(attribute)};
        }
        return null;
    }

    private static boolean sameKey(String[] a, String[] b) {
        return a != null && b != null && a[0].equals(b[0]) && a[1].equals(b[1]);
    }

    private static boolean flag(Node element, String attribute) {
        String value = element.attributes.get(attribute);
        return value != null && value.toLowerCase(Locale.ROOT).equals("true");
    }

    private static void mergeElement(Node base, Node patch) {
        for (Map.Entry<String, String> entry : patch.attributes.entrySet()) {
            base.attributes.put(entry.getKey(), entry.getValue());
        }
        if (patch.text != null && !patch.text.trim().isEmpty() && patch.children.isEmpty()) {
            base.text = patch.text;
        }
        Map<String, Integer> positions = new LinkedHashMap<>();
        for (Node child : patch.children) {
            if (child.isComment()) continue;
            if (flag(child, ADD_ATTRIBUTE)) {
                child.attributes.remove(ADD_ATTRIBUTE);
                base.children.add(child);
                continue;
            }
            String[] key = key(child);
            List<Node> candidates = new ArrayList<>();
            for (Node node : base.children) {
                if (!node.isComment() && node.tag.equals(child.tag)) candidates.add(node);
            }
            Node match = null;
            if (key != null) {
                for (Node candidate : candidates) {
                    if (sameKey(key(candidate), key)) {
                        match = candidate;
                        break;
                    }
                }
            } else {
                Integer position = positions.get(child.tag);
                int at = position == null ? 0 : position;
                positions.put(child.tag, at + 1);
                match = at < candidates.size() ? candidates.get(at) : null;
            }
            if (flag(child, REMOVE_ATTRIBUTE)) {
                if (match != null) base.children.remove(match);
                continue;
            }
            if (match == null) base.children.add(child);
            else mergeElement(match, child);
        }
    }

    // ---- Writing (ElementTree's _serialize_xml) -------------------------------

    private static void serialize(Node node, StringBuilder out) {
        if (node.isComment()) {
            out.append("<!--").append(node.text == null ? "" : node.text).append("-->");
        } else {
            out.append('<').append(node.tag);
            for (Map.Entry<String, String> entry : node.attributes.entrySet()) {
                out.append(' ').append(entry.getKey()).append("=\"").append(escapeAttribute(entry.getValue()))
                        .append('"');
            }
            boolean hasText = node.text != null && !node.text.isEmpty();
            if (hasText || !node.children.isEmpty()) {
                out.append('>');
                if (hasText) out.append(escapeText(node.text));
                for (Node child : node.children) serialize(child, out);
                out.append("</").append(node.tag).append('>');
            } else {
                out.append(" />");
            }
        }
        if (node.tail != null && !node.tail.isEmpty()) out.append(escapeText(node.tail));
    }

    private static String escapeText(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String escapeAttribute(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
                .replace("\r", "&#13;").replace("\n", "&#10;").replace("\t", "&#09;");
    }

    // ---- Reading (expat + ElementTree's TreeBuilder) ----------------------------

    private static final class Parser {
        private final String s;
        private int at;

        Parser(String text) {
            // XML end-of-line handling: CR LF and lone CR become LF.
            this.s = stripBom(text).replace("\r\n", "\n").replace('\r', '\n');
        }

        Node document() throws XmlException {
            // Prolog: declaration, comments, processing instructions, space.
            while (true) {
                skipSpace();
                if (s.startsWith("<?", at)) {
                    end("?>");
                } else if (s.startsWith("<!--", at)) {
                    end("-->");
                } else if (s.startsWith("<!DOCTYPE", at)) {
                    throw new XmlException("a document type declaration is not supported");
                } else {
                    break;
                }
            }
            if (!s.startsWith("<", at)) throw new XmlException("no root element");
            return element();
        }

        private void skipSpace() {
            while (at < s.length() && Character.isWhitespace(s.charAt(at))) at++;
        }

        private String end(String marker) throws XmlException {
            int found = s.indexOf(marker, at);
            if (found < 0) throw new XmlException("unterminated " + marker);
            String inside = s.substring(at, found);
            at = found + marker.length();
            return inside;
        }

        private String name() throws XmlException {
            int start = at;
            while (at < s.length()) {
                char c = s.charAt(at);
                if (Character.isWhitespace(c) || c == '/' || c == '>' || c == '=') break;
                at++;
            }
            if (start == at) throw new XmlException("a name is missing at " + at);
            return s.substring(start, at);
        }

        // At '<' of a start tag.
        private Node element() throws XmlException {
            at++;
            Node node = new Node(name());
            while (true) {
                skipSpace();
                if (s.startsWith("/>", at)) {
                    at += 2;
                    return node;
                }
                if (s.startsWith(">", at)) {
                    at++;
                    break;
                }
                String attribute = name();
                skipSpace();
                if (!s.startsWith("=", at)) throw new XmlException("= missing after " + attribute);
                at++;
                skipSpace();
                char quote = at < s.length() ? s.charAt(at) : 0;
                if (quote != '"' && quote != '\'') throw new XmlException("unquoted value of " + attribute);
                int close = s.indexOf(quote, at + 1);
                if (close < 0) throw new XmlException("unterminated value of " + attribute);
                String raw = s.substring(at + 1, close);
                at = close + 1;
                // Literal white space in a value is normalised to spaces
                // before character references are expanded.
                node.attributes.put(attribute, unescape(raw.replace('\n', ' ').replace('\t', ' ')));
            }
            // Content: text goes to the element's text until its first child,
            // then to the tail of the child (or comment) before it.
            StringBuilder data = new StringBuilder();
            Node last = null;
            while (true) {
                if (at >= s.length()) throw new XmlException("unterminated <" + node.tag + ">");
                if (s.startsWith("</", at)) {
                    flush(node, last, data);
                    at += 2;
                    String closing = name();
                    skipSpace();
                    if (!closing.equals(node.tag) || !s.startsWith(">", at)) {
                        throw new XmlException("</" + closing + "> does not close <" + node.tag + ">");
                    }
                    at++;
                    return node;
                }
                if (s.startsWith("<!--", at)) {
                    flush(node, last, data);
                    at += 4;
                    Node comment = new Node(null);
                    comment.text = end("-->");
                    node.children.add(comment);
                    last = comment;
                } else if (s.startsWith("<![CDATA[", at)) {
                    at += 9;
                    data.append(end("]]>"));
                } else if (s.startsWith("<?", at)) {
                    end("?>");  // processing instructions are dropped
                } else if (s.startsWith("<", at)) {
                    flush(node, last, data);
                    Node child = element();
                    node.children.add(child);
                    last = child;
                } else {
                    int next = s.indexOf('<', at);
                    if (next < 0) next = s.length();
                    data.append(unescape(s.substring(at, next)));
                    at = next;
                }
            }
        }

        private static void flush(Node parent, Node last, StringBuilder data) {
            if (data.length() == 0) return;
            if (last == null) parent.text = data.toString();
            else last.tail = data.toString();
            data.setLength(0);
        }

        private static String unescape(String text) throws XmlException {
            if (text.indexOf('&') < 0) return text;
            StringBuilder out = new StringBuilder(text.length());
            for (int i = 0; i < text.length(); ) {
                char c = text.charAt(i);
                if (c != '&') {
                    out.append(c);
                    i++;
                    continue;
                }
                int semicolon = text.indexOf(';', i);
                if (semicolon < 0) throw new XmlException("unterminated entity");
                String entity = text.substring(i + 1, semicolon);
                switch (entity) {
                    case "lt": out.append('<'); break;
                    case "gt": out.append('>'); break;
                    case "amp": out.append('&'); break;
                    case "quot": out.append('"'); break;
                    case "apos": out.append('\''); break;
                    default:
                        if (entity.startsWith("#x") || entity.startsWith("#X")) {
                            out.appendCodePoint(Integer.parseInt(entity.substring(2), 16));
                        } else if (entity.startsWith("#")) {
                            out.appendCodePoint(Integer.parseInt(entity.substring(1)));
                        } else {
                            throw new XmlException("unknown entity &" + entity + ";");
                        }
                }
                i = semicolon + 1;
            }
            return out.toString();
        }
    }

    // Kept for callers that need the bytes of a string in UTF-8 with a BOM.
    static byte[] withBom(String text) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0xEF);
        out.write(0xBB);
        out.write(0xBF);
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        out.write(body, 0, body.length);
        return out.toByteArray();
    }
}
