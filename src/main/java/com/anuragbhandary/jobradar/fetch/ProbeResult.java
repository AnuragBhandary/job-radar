package com.anuragbhandary.jobradar.fetch;

import com.anuragbhandary.jobradar.domain.Source;

/**
 * What one platform said about one candidate token.
 *
 * @param postings how many the board advertises; -1 when unknown
 * @param detail   free text for the report, such as an HTTP status
 */
public record ProbeResult(Source source, String token, Outcome outcome, int postings, String detail) {

    public enum Outcome {
        /** The board exists and has postings. */
        FOUND,
        /**
         * The board answered, with nothing on it.
         *
         * <p>On Greenhouse, Ashby and Lever this genuinely means a real board with
         * no openings, because all three 404 an unknown token. On SmartRecruiters
         * it means nothing at all: that API answers 200 with {@code totalFound: 0}
         * for any company name whatsoever, so an empty result there cannot be
         * told apart from a token that never existed.
         */
        EMPTY,
        /** The platform returned 404. The company is not on it. */
        ABSENT,
        /** Something else went wrong; the token is unproven either way. */
        ERROR,
        /** Not tried, because it is already a seeded board. */
        ALREADY_KNOWN,
        /** Not tried, because it is on the confirmed-absent list. */
        SKIPPED
    }

    public boolean isInteresting() {
        return outcome == Outcome.FOUND;
    }

    public static ProbeResult of(Source source, String token, Outcome outcome, String detail) {
        return new ProbeResult(source, token, outcome, -1, detail);
    }
}
