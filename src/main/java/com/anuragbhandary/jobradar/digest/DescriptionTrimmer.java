package com.anuragbhandary.jobradar.digest;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cuts a posting's description down to the part a reader judges it on.
 *
 * <p>Descriptions average six kilobytes and most of that is the company's own
 * story, benefits and an equal-opportunity paragraph. A handoff file carrying
 * thirty of them in full is too long to read in one go. What decides a posting is
 * what the role is and what it asks for, so the cut keeps the opening (the role)
 * and the requirements section, and says when it has dropped anything.
 */
final class DescriptionTrimmer {

    /** Characters kept per posting. */
    static final int MAX = 2_500;

    /** How much of the opening survives when the requirements start further down. */
    private static final int HEAD = 600;

    /**
     * Where the requirements start. Two strengths: a heading at the start of a line,
     * or a phrase distinctive enough to find mid-paragraph, because some boards
     * deliver the whole description as one paragraph with the headings run in.
     * "you have" is only trusted as a heading; in prose it is everywhere.
     */
    private static final Pattern REQUIREMENTS = Pattern.compile(
            "(?im)(?:^\\W*(?:(?:minimum|basic|required|preferred)\\s+)?"
                    + "(?:you have|you bring|about you|who you are|your profile|your skills"
                    + "|skills (?:and|&) experience)\\b)"
                    + "|\\b(?:(?:minimum|basic|required)\\s+)?(?:requirements|qualifications)\\b"
                    + "|\\bwhat you(?:'|’)?ll (?:need|bring)\\b|\\bwhat you need\\b"
                    + "|\\bwhat you bring\\b|\\bwhat we(?:'|’)?re looking for\\b"
                    + "|\\bwhat we look for\\b|\\bmust[- ]haves?\\b");

    private DescriptionTrimmer() {
    }

    static String trim(String description) {
        if (description == null || description.isBlank()) {
            return "(no description stored)";
        }
        // Boards separate list items with blank lines; three or more in a row carry
        // no information and cost the reader a screen.
        String text = description.replace("\r", "")
                .replaceAll("[ \\t\\u00a0]+", " ")
                .replaceAll("\\n\\s*\\n(\\s*\\n)+", "\n\n")
                .strip();
        if (text.length() <= MAX) {
            return text;
        }

        // Requirements starting early are already inside the first MAX characters,
        // and skipping past them to a later heading would drop the core list.
        Matcher m = REQUIREMENTS.matcher(text);
        if (m.find() && m.start() > MAX / 2) {
            String head = cutAtWord(text.substring(0, HEAD));
            String rest = cutAtWord(text.substring(m.start(),
                    Math.min(text.length(), m.start() + MAX - HEAD)));
            return head + " […]\n\n" + rest + (m.start() + MAX - HEAD < text.length() ? " […]" : "");
        }
        return cutAtWord(text.substring(0, MAX)) + " […]";
    }

    private static String cutAtWord(String text) {
        int space = text.lastIndexOf(' ');
        return space > text.length() - 40 && space > 0 ? text.substring(0, space) : text;
    }
}
