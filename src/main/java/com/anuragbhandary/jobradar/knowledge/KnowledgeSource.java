package com.anuragbhandary.jobradar.knowledge;

/**
 * Where an answer came from, and how much authority that gives it.
 *
 * <p>The old system had five origins on {@code Answer} and lost them the moment
 * anything was stored: an AI draft the applicant approved was appended to
 * {@code applicant.yml} as a plain {@code {match, answer}} pair and came back on
 * the next form as {@code PROFILE} - indistinguishable from a fact he had typed
 * himself. That is the specific bug this enum exists to make impossible.
 *
 * <h2>Authority</h2>
 * Ordered here, and asserted in {@code KnowledgeResolverTest}. Authority is
 * <em>one</em> of the inputs to resolution, never the whole of it: an assertion
 * only competes at all once its scope applies, and a broad high-authority fact
 * still loses to a narrow one from the same source. See
 * {@link KnowledgeResolver} for how the two are combined.
 */
public enum KnowledgeSource {

    /**
     * Typed during this preparation, for this application.
     *
     * <p>Beats everything stored. If he is looking at the form and says "no", a
     * rule written three weeks ago does not get to overrule him.
     */
    SESSION,

    /** Typed by him and stored, for one application. */
    USER_INPUT,

    /** A rule he approved, valid for a scope he chose. */
    USER_RULE,

    /** A stable fact from {@code applicant.yml}. Name, address, phone. */
    PROFILE,

    /** Something the resume says, and therefore something he can defend. */
    RESUME,

    /**
     * Worked out from the profile and the application context by Java, with no
     * stored value: sponsorship, work authorisation, the salary band.
     *
     * <p>Below the profile deliberately. A derivation is only as good as its
     * inputs, and an explicit fact about this country beats a rule that computed
     * one.
     */
    DERIVED,

    /**
     * Used on a previous application. Evidence, not truth.
     *
     * <p>Never auto-filled on its own for a context-sensitive concept: that a
     * German form was told "yes" says nothing about an Indian one, and the whole
     * point of recording history is to suggest rather than to assume.
     */
    HISTORICAL,

    /**
     * Written by a model. Always starts unapproved, and <strong>stays</strong>
     * {@code AI_PROPOSED} after approval - approving it records that he read it,
     * not that he wrote it.
     */
    AI_PROPOSED;

    /** Lower is more authoritative. The ordinal is the ranking. */
    public int authority() {
        return ordinal();
    }

    public boolean isMoreAuthoritativeThan(KnowledgeSource other) {
        return other != null && authority() < other.authority();
    }

    /** True for the sources that are the applicant's own words about himself. */
    public boolean isUserStated() {
        return this == SESSION || this == USER_INPUT || this == USER_RULE
                || this == PROFILE;
    }
}
