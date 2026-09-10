package com.anuragbhandary.jobradar.knowledge;

import java.util.Locale;
import java.util.Objects;

/**
 * Where a piece of knowledge applies.
 *
 * <p>The field the old system did not have, and the reason a sponsorship answer
 * given for a German role could silently answer an Indian one. An
 * {@code ExtraAnswer} was a {@code {match, answer}} pair matched by substring:
 * it applied everywhere, always, and there was nowhere to say otherwise.
 *
 * <h2>Specificity</h2>
 * The ordering is deterministic, documented here and asserted in
 * {@code ScopeTest}. More specific wins:
 *
 * <pre>
 *   APPLICATION  &gt; COMPANY &gt; COUNTRY &gt; WORK_MODE &gt; STRATEGIC_CLASS &gt; GLOBAL
 *   (one posting)                                                    (everywhere)
 * </pre>
 *
 * <p>COUNTRY sits above WORK_MODE because there are two hundred countries and
 * seven work modes: "in Germany" says more than "for a remote job". STRATEGIC_CLASS
 * is the broadest of the conditional levels - it has four values - so it sits
 * just above GLOBAL.
 *
 * <p><b>ROLE_FAMILY is deliberately absent.</b> It was in the plan and nothing
 * needs it: "why this company" is COMPANY, "describe your Kafka experience" is a
 * different concept rather than a differently scoped one, and a level no concept
 * defaults to is a level that only makes the ordering harder to reason about.
 * The role family is still carried on {@link ApplicationContext} for grounding
 * and for later use.
 *
 * @param value the thing being matched: an ISO country code, a company name, a
 *              posting id, a work mode name. Null for {@link Level#GLOBAL}, which
 *              matches everything and therefore has nothing to match on.
 */
public record Scope(Level level, String value) {

    /** Ordered most specific first. The ordinal <em>is</em> the specificity. */
    public enum Level {

        /** This one posting, and nothing else. Where a session answer lands. */
        APPLICATION,

        /** Every application to this employer. "Why do you want to work here?" */
        COMPANY,

        /**
         * Every job in one country. The level that matters most: sponsorship,
         * work authorisation and salary all turn on it, and all three are wrong
         * for one country if answered for another.
         */
        COUNTRY,

        /** Onsite, hybrid, or one of the four kinds of remote. */
        WORK_MODE,

        /** One of the four lanes: India home/other, international relocation/remote. */
        STRATEGIC_CLASS,

        /**
         * Everywhere. Correct for a name, a passport, a degree - facts that do
         * not change with the employer or the country.
         *
         * <p>Not offered at all for a concept marked context-sensitive. See
         * {@link Concept#allowsScope}.
         */
        GLOBAL;

        /** Lower is more specific. Compared, never printed. */
        public int specificity() {
            return ordinal();
        }

        public boolean isMoreSpecificThan(Level other) {
            return other != null && specificity() < other.specificity();
        }
    }

    public Scope {
        Objects.requireNonNull(level, "scope level");
        value = level == Level.GLOBAL ? null : normalise(value);
    }

    public static Scope global() {
        return new Scope(Level.GLOBAL, null);
    }

    public static Scope country(String countryCode) {
        return new Scope(Level.COUNTRY, countryCode);
    }

    public static Scope company(String company) {
        return new Scope(Level.COMPANY, company);
    }

    public static Scope application(Long postingId) {
        return new Scope(Level.APPLICATION, postingId == null ? null : postingId.toString());
    }

    public static Scope of(Level level, String value) {
        return new Scope(level, value);
    }

    public int specificity() {
        return level.specificity();
    }

    /**
     * A scope that names a level but not a value.
     *
     * <p>Written by the migration of the old {@code extra-answers} list, where an
     * answer's country was never recorded because the old model had no country.
     * It matches nothing, so the assertion is inert until somebody chooses where
     * it applies - which is exactly the state such an answer should be in, rather
     * than being guessed into a global rule.
     */
    public boolean isPending() {
        return level != Level.GLOBAL && value == null;
    }

    /**
     * Whether this scope covers the situation described by the context.
     *
     * <p>The whole safety property of the knowledge system is one line of this
     * method: a COUNTRY scope matches only when the context's employment country
     * is the same country. An assertion learned for Germany is simply not a
     * candidate when the resolver is asked about India, so it cannot win, cannot
     * conflict, and cannot be reasoned about by accident.
     */
    public boolean appliesTo(ApplicationContext context) {
        if (context == null) {
            return level == Level.GLOBAL;
        }
        return switch (level) {
            case GLOBAL -> true;
            case APPLICATION -> matches(context.postingId() == null
                    ? null : context.postingId().toString());
            case COMPANY -> matches(context.company());
            // The employment country, not the posting's country. For a role
            // worked from Mumbai for a Berlin company those are different, and
            // the one that governs the answer is where the employee sits.
            case COUNTRY -> matches(context.employmentCountryCode());
            case WORK_MODE -> matches(context.workMode() == null
                    ? null : context.workMode().name());
            case STRATEGIC_CLASS -> matches(context.strategicClass() == null
                    ? null : context.strategicClass().name());
        };
    }

    private boolean matches(String candidate) {
        // An unknown context value never matches a specific scope. Falling back to
        // "matches everything" here is exactly how a German answer would reach an
        // application whose country could not be worked out.
        return value != null && candidate != null && value.equals(normalise(candidate));
    }

    private static String normalise(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim().toLowerCase(Locale.ROOT);
        // One token, never a list. A form that submits several hidden value
        // pickers under one name arrives as "nl,,onsite,india_home", and storing
        // that as a scope produces an assertion that matches nothing and looks
        // like it was saved. Refusing it here is cheaper than trusting a caller.
        return trimmed.contains(",") ? null : trimmed;
    }

    /** "country=de", "global", "country=?" - for explanations and review screens. */
    public String describe() {
        if (level == Level.GLOBAL) {
            return "global";
        }
        String name = level.name().toLowerCase(Locale.ROOT);
        return isPending() ? name + "=? (not yet chosen)" : name + "=" + value;
    }
}
