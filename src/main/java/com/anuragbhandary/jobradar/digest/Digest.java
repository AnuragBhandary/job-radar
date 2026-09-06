package com.anuragbhandary.jobradar.digest;

import com.anuragbhandary.jobradar.domain.BoardToken;
import com.anuragbhandary.jobradar.domain.Posting;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Everything today's digest needs, already selected and sorted.
 *
 * <p>Separated from the writer so that what goes in the digest is decided in one
 * place and how it is formatted in another - and so the selection can be tested
 * without parsing markdown.
 *
 * @param newCandidates    new, eligible, and stating a years requirement we meet
 * @param needsHumanReview new or updated, eligible, but stating no years at all.
 *                         Listed separately rather than mixed into the
 *                         candidates, because the absence of a number is not
 *                         evidence of an entry-level role - it is a question.
 * @param updated          previously seen, but the description has since changed
 * @param closed           postings that disappeared from their board
 * @param rejections       counts by reason, for the postings seen this run
 * @param suppressedAlreadyApplied how many candidates were withheld because the
 *                         tracker already records an application to that company.
 *                         Counted rather than silently dropped - a digest that
 *                         quietly shrinks is one you stop trusting.
 */
public record Digest(
        LocalDate date,
        List<Posting> newCandidates,
        List<Posting> needsHumanReview,
        List<Posting> updated,
        List<Posting> closed,
        Map<String, Long> rejections,
        List<BoardToken> boards,
        boolean salaryFloorsNeedReverification,
        int suppressedAlreadyApplied) {

    /** True when there is nothing to report but board health. */
    public boolean isQuiet() {
        return newCandidates.isEmpty() && needsHumanReview.isEmpty() && updated.isEmpty();
    }
}
