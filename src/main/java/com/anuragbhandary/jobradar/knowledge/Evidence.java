package com.anuragbhandary.jobradar.knowledge;

import java.util.List;

/**
 * What a piece of knowledge was based on.
 *
 * <p>Stored so that "why did Job Radar choose this answer?" has a real answer
 * rather than a restatement of the answer. The old system had a free-text
 * {@code note} on {@code Answer} which was good prose and could not be checked
 * against anything: "posting country is REMOTE; authorised = true" tells you the
 * conclusion and not which field it read.
 *
 * <p>It is also the check on a model. An AI proposal has to cite the resume
 * bullets it used, and {@code AnswerProposer} rejects a citation that is not in
 * the material it was given - which is how a fabricated project is caught before
 * anybody reads the prose.
 *
 * @param ref     what was read: a profile path, a resume bullet, a posting field
 * @param excerpt the actual text, short. Null where the ref is self-explaining.
 */
public record Evidence(Kind kind, String ref, String excerpt) {

    public enum Kind {
        /** A field of applicant.yml. */
        PROFILE_FIELD,
        /** A bullet, project or skill from the resume. */
        RESUME_ITEM,
        /** Something the posting itself says. */
        POSTING_FIELD,
        /** Part of the application context: country, work mode, employer. */
        CONTEXT,
        /** A rule the applicant approved. */
        USER_RULE,
        /** An answer used on an earlier application. */
        PRIOR_APPLICATION,
        /** Something a model asserted. Never sufficient on its own. */
        MODEL_CLAIM
    }

    public static Evidence profile(String path, String excerpt) {
        return new Evidence(Kind.PROFILE_FIELD, path, excerpt);
    }

    public static Evidence resume(String ref, String excerpt) {
        return new Evidence(Kind.RESUME_ITEM, ref, excerpt);
    }

    public static Evidence context(String ref, String excerpt) {
        return new Evidence(Kind.CONTEXT, ref, excerpt);
    }

    public static Evidence rule(String ref, String excerpt) {
        return new Evidence(Kind.USER_RULE, ref, excerpt);
    }

    public static Evidence prior(String ref, String excerpt) {
        return new Evidence(Kind.PRIOR_APPLICATION, ref, excerpt);
    }

    /** "context:employment country=DE" - one line, for a review screen. */
    public String describe() {
        String head = kind.name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ')
                + ": " + ref;
        return excerpt == null || excerpt.isBlank() ? head : head + " = " + excerpt;
    }

    /**
     * Serialised for the assertion's own column.
     *
     * <p>Tab and newline separated rather than JSON. This is a short list read
     * whole and never queried by element, the same reasoning that keeps
     * {@code OpenQuestion} out of its own table - and a Jackson round-trip on
     * every assertion write would be a dependency on the shape of a record that
     * is going to change.
     */
    public static String serialise(List<Evidence> evidence) {
        if (evidence == null || evidence.isEmpty()) {
            return null;
        }
        StringBuilder out = new StringBuilder();
        for (Evidence item : evidence) {
            out.append(item.kind().name()).append('\t')
                    .append(clean(item.ref())).append('\t')
                    .append(clean(item.excerpt())).append('\n');
        }
        return out.toString();
    }

    /** Lenient: a line that cannot be read is one lost citation, not an exception. */
    public static List<Evidence> parse(String serialised) {
        if (serialised == null || serialised.isBlank()) {
            return List.of();
        }
        List<Evidence> evidence = new java.util.ArrayList<>();
        for (String line : serialised.split("\n")) {
            String[] parts = line.split("\t", -1);
            if (parts.length < 2 || parts[0].isBlank()) {
                continue;
            }
            try {
                evidence.add(new Evidence(Kind.valueOf(parts[0]), parts[1],
                        parts.length > 2 && !parts[2].isBlank() ? parts[2] : null));
            } catch (IllegalArgumentException e) {
                // An evidence kind this version does not know. Skipped rather
                // than thrown: this is a record of something that already
                // happened, and failing to read it must not fail the run.
            }
        }
        return List.copyOf(evidence);
    }

    private static String clean(String value) {
        return value == null ? "" : value.replace('\t', ' ').replace('\n', ' ').trim();
    }
}
