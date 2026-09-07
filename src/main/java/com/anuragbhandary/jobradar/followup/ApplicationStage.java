package com.anuragbhandary.jobradar.followup;

import java.util.List;
import java.util.Locale;

/**
 * Where an application has got to, normalised from whatever the sheet says.
 *
 * <p>The status column is free text typed by hand, so it holds "Applied",
 * "applied", "Applied ✅", "OA sent", "Round 2" and "Ghosted". Matching it exactly
 * would mean the follow-up logic silently ignores most rows - which is the
 * failure that matters here, because a row it ignores is an application nobody
 * chases.
 */
public enum ApplicationStage {

    /** Sent, nothing back. The only stage a follow-up applies to. */
    APPLIED(List.of("applied", "submitted", "sent", "in progress", "pending")),

    /** Something has happened. Chasing is a different conversation. */
    IN_PROCESS(List.of("screening", "screen", "phone", "recruiter", "oa",
            "online assessment", "assessment", "test", "interview", "round",
            "onsite", "final", "hr round", "technical")),

    /** Over. Never followed up. */
    CLOSED(List.of("rejected", "reject", "declined", "no", "closed", "withdrawn",
            "withdraw", "ghosted", "no response", "expired", "offer", "accepted",
            "hired", "joined")),

    /** Blank, or a word this does not know. Reported, not chased. */
    UNKNOWN(List.of());

    private final List<String> markers;

    ApplicationStage(List<String> markers) {
        this.markers = markers;
    }

    /**
     * Reads a sheet status.
     *
     * <p>Order matters: CLOSED is tested before APPLIED so that "Applied -
     * rejected" is closed rather than open. A row carrying both words is a row
     * whose story ended, and the later word is the one that counts.
     */
    public static ApplicationStage of(String status) {
        if (status == null || status.isBlank()) {
            return UNKNOWN;
        }
        String text = status.toLowerCase(Locale.ROOT);
        for (ApplicationStage stage : List.of(CLOSED, IN_PROCESS, APPLIED)) {
            for (String marker : stage.markers) {
                if (containsWord(text, marker)) {
                    return stage;
                }
            }
        }
        return UNKNOWN;
    }

    /**
     * Whole-word containment.
     *
     * <p>"no" is a CLOSED marker and is inside "not yet", "phone screen" and
     * "Notion". Substring matching here closes applications that are still open,
     * and it does it invisibly - the row simply stops appearing in the follow-up
     * list. Same bug as "ethnicity" containing "city".
     */
    private static boolean containsWord(String haystack, String needle) {
        int from = 0;
        while (true) {
            int at = haystack.indexOf(needle, from);
            if (at < 0) {
                return false;
            }
            boolean startOk = at == 0 || !Character.isLetterOrDigit(haystack.charAt(at - 1));
            int end = at + needle.length();
            boolean endOk = end == haystack.length()
                    || !Character.isLetterOrDigit(haystack.charAt(end));
            if (startOk && endOk) {
                return true;
            }
            from = at + 1;
        }
    }
}
