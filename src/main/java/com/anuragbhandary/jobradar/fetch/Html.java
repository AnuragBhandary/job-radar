package com.anuragbhandary.jobradar.fetch;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns board HTML into plain text.
 *
 * <p>Small and hand-rolled rather than a jsoup dependency, because the job here
 * is narrow: these are ATS description blobs, not arbitrary web pages, and the
 * output is only ever read by a regex and a human.
 *
 * <p>The unescaping runs twice on purpose. Greenhouse entity-escapes the whole
 * content field once, so {@code <p>} arrives as {@code &lt;p&gt;}; any entity
 * that was already in the original HTML, such as {@code &nbsp;}, therefore
 * arrives double-escaped as {@code &amp;nbsp;}. One pass gives back the markup,
 * the second resolves the entities inside it.
 */
public final class Html {

    private static final Pattern TAG = Pattern.compile("<[^>]*>");
    private static final Pattern ENTITY = Pattern.compile("&(#x?[0-9a-fA-F]+|[a-zA-Z]+);");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    /** Tags whose boundaries carry meaning; collapsing them would run words together. */
    private static final Pattern BLOCK_BOUNDARY =
            Pattern.compile("(?i)</?(p|div|br|li|tr|h[1-6]|ul|ol|table|section)\\b[^>]*>");

    private static final Map<String, String> NAMED = Map.ofEntries(
            Map.entry("lt", "<"),
            Map.entry("gt", ">"),
            Map.entry("amp", "&"),
            Map.entry("quot", "\""),
            Map.entry("apos", "'"),
            Map.entry("nbsp", " "),
            Map.entry("ndash", "-"),
            Map.entry("mdash", "-"),
            Map.entry("rsquo", "'"),
            Map.entry("lsquo", "'"),
            Map.entry("rdquo", "\""),
            Map.entry("ldquo", "\""),
            Map.entry("hellip", "..."),
            Map.entry("bull", "*"),
            Map.entry("middot", "*"),
            Map.entry("eacute", "e"),
            Map.entry("uuml", "u"),
            Map.entry("ouml", "o"),
            Map.entry("auml", "a"));

    private Html() {
    }

    /**
     * Unescapes, strips tags and collapses whitespace.
     *
     * @return plain text, never null; an empty string for null or blank input
     */
    public static String toPlainText(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        String s = unescape(unescape(html));
        // Insert a space at block boundaries before dropping tags, so that
        // "<li>Java</li><li>Python</li>" does not become "JavaPython".
        s = BLOCK_BOUNDARY.matcher(s).replaceAll(" ");
        s = TAG.matcher(s).replaceAll("");
        return WHITESPACE.matcher(s).replaceAll(" ").trim();
    }

    /** Resolves one layer of HTML entities, named and numeric. */
    static String unescape(String s) {
        Matcher m = ENTITY.matcher(s);
        StringBuilder out = new StringBuilder(s.length());
        while (m.find()) {
            String body = m.group(1);
            String replacement;
            if (body.startsWith("#")) {
                replacement = numericEntity(body);
            } else {
                replacement = NAMED.get(body.toLowerCase());
            }
            // An entity we do not know is left exactly as it was rather than
            // dropped, so nothing silently disappears from a description.
            m.appendReplacement(out, Matcher.quoteReplacement(
                    replacement != null ? replacement : m.group()));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static String numericEntity(String body) {
        try {
            int codePoint = body.startsWith("#x") || body.startsWith("#X")
                    ? Integer.parseInt(body.substring(2), 16)
                    : Integer.parseInt(body.substring(1));
            return Character.isValidCodePoint(codePoint)
                    ? new String(Character.toChars(codePoint))
                    : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
