package com.anuragbhandary.jobradar.apply.resume.rewrite;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Reads a model's rewrite and refuses anything that is not an answer for the item
 * that was asked about.
 *
 * <p>A reply naming a different sourceId is rejected, not re-attached. The id is
 * the only thing tying a sentence to the accomplishment it describes, and a model
 * that got it wrong has told us it lost track of which one that was.
 */
public final class RewriteParser {

    private static final ObjectMapper JSON = new ObjectMapper();

    private RewriteParser() {
    }

    /** @param wrongSource the reply named another item, or none */
    public record Parsed(boolean ok, String text, String error, boolean wrongSource) {

        static Parsed failed(String error) {
            return new Parsed(false, null, error, false);
        }
    }

    public static Parsed parse(String reply, String expectedSourceId) {
        if (reply == null || reply.isBlank()) {
            return Parsed.failed("empty reply");
        }
        JsonNode node;
        try {
            node = JSON.readTree(strip(reply));
        } catch (Exception e) {
            return Parsed.failed("not JSON");
        }
        if (node == null || !node.isObject()) {
            return Parsed.failed("not a JSON object");
        }
        JsonNode id = node.get("sourceId");
        if (id == null || !id.isTextual() || id.asText().isBlank()) {
            return new Parsed(false, null, "no sourceId", true);
        }
        if (!id.asText().strip().equals(expectedSourceId)) {
            return new Parsed(false, null, "answered for '" + id.asText() + "', not '"
                    + expectedSourceId + "'", true);
        }
        JsonNode text = node.get("rewrittenText");
        if (text == null || !text.isTextual() || text.asText().isBlank()) {
            return Parsed.failed("empty rewrite");
        }
        String cleaned = text.asText().replaceAll("\\s+", " ").strip()
                .replaceAll("^[\"“]+|[\"”]+$", "").strip();
        return cleaned.isEmpty() ? Parsed.failed("empty rewrite")
                : new Parsed(true, cleaned, null, false);
    }

    static String strip(String reply) {
        String cleaned = reply.strip();
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.replaceAll("^```[a-zA-Z]*\\s*", "").replaceAll("```\\s*$", "");
        }
        int open = cleaned.indexOf('{');
        int close = cleaned.lastIndexOf('}');
        return open >= 0 && close > open ? cleaned.substring(open, close + 1) : cleaned;
    }
}
