package com.anuragbhandary.jobradar.filter;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds a posting open only to U.S. persons or to holders of a security clearance.
 *
 * <p>A fact about the applicant's passport, not about fit. Swarm Aero's "Early
 * Career" role (2026-09-29) was remote with no country, so it read as open to
 * India; its last paragraph was an ITAR clause limiting it to U.S. citizens and
 * permanent residents.
 *
 * <p>Export-licence boilerplate is deliberately not a match. Hundreds of postings
 * say "an export license may be required" or that employment is "contingent upon
 * obtaining any export license", which is a process the employer runs, not a bar.
 * A clearance counts only when it is required; "a strong plus" is not.
 */
final class ExportControlRequirement {

    private static final Pattern REQUIRED = Pattern.compile(
            "\\bITAR\\b"
                    + "|\\b(?:must|required\\s+to|need\\s+to)\\s+be\\s+(?:a\\s+)?"
                    + "(?:U\\.?S\\.?|United\\s+States)\\s+(?:citizen|person|national)"
                    + "|\\b(?:U\\.?S\\.?|United\\s+States)\\s+citizenship\\s+(?:is\\s+)?"
                    + "(?:required|mandatory|at\\s+the\\s+time\\s+of\\s+hire)"
                    + "|\\brequires?\\b[^.;\\n]{0,40}\\b(?:U\\.?S\\.?|United\\s+States)\\s+citizenship"
                    + "|\\b(?:with|hold)\\s+(?:U\\.?S\\.?|United\\s+States)\\s+citizenship"
                    + "|\\bonly\\s+(?:hire|consider|employ)\\s+(?:U\\.?S\\.?|United\\s+States)\\s+persons?"
                    + "|\\b(?:must|required\\s+to|requires?\\s+(?:you\\s+to\\s+)?|need\\s+to)"
                    + "[^.;\\n]{0,40}\\b(?:obtain|hold|have|possess|maintain)\\b[^.;\\n]{0,40}"
                    + "\\bsecurity\\s+clearance"
                    + "|\\b(?:active|current)\\s+(?:secret|top\\s+secret|TS/SCI|security)\\s+clearance"
                    + "\\s+(?:is\\s+)?required",
            Pattern.CASE_INSENSITIVE);

    private ExportControlRequirement() {
    }

    /** The requirement as the posting states it, or empty. */
    static Optional<String> find(String description) {
        if (description == null || description.isBlank()) {
            return Optional.empty();
        }
        Matcher m = REQUIRED.matcher(description);
        if (!m.find()) {
            return Optional.empty();
        }
        String phrase = description.substring(Math.max(0, m.start() - 20),
                        Math.min(description.length(), m.end() + 60))
                .replaceAll("\\s+", " ").strip();
        return Optional.of("U.S. persons or security clearance only: \"" + phrase + "\"");
    }
}
