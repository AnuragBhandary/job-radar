package com.anuragbhandary.jobradar.strategy;

/**
 * How strongly to recommend an opportunity, given the user's current strategy.
 *
 * <p>The second of the two axes this phase separates. The first is eligibility -
 * {@link com.anuragbhandary.jobradar.domain.Verdict}, which answers "could this
 * job be pursued at all" and rejects on things no preference can fix: a senior
 * title, five years of required experience, a remote role locked to a country the
 * applicant cannot be in.
 *
 * <p>This one answers "should it be on today's list", and it is allowed to change
 * the moment the strategy does. Nothing is deleted for being {@link #EXCLUDED};
 * it simply stops appearing in the default feed. Before this phase the two axes
 * were the same field, which is why 5,336 postings were rejected as "outside
 * target geographies" and could only come back by editing a 150-entry list of
 * place names by hand.
 */
public enum StrategyOutcome {

    /** In the default feed. */
    RECOMMENDED,

    /**
     * Eligible and real, but not what the strategy is aimed at today.
     * Reachable through an explicit filter, never shown by default.
     */
    CONSIDER,

    /**
     * The strategy says no. Still stored, still searchable, still applicable to
     * by hand - see the note on {@link RelocationTier#EXCLUDED}.
     */
    EXCLUDED,

    /** Not screened yet, or not classifiable. */
    UNKNOWN
}
