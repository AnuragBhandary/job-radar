package com.anuragbhandary.jobradar.digest;

import com.anuragbhandary.jobradar.domain.BoardToken;
import com.anuragbhandary.jobradar.domain.Posting;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Everything one handoff file needs, already selected and sorted.
 *
 * <p>The handoff file is read in a Claude session, where the judging happens:
 * fit, order, what to lead with. So nothing here is ranked or scored. It is the
 * postings that survived the fact-based filters, with enough of each description
 * to judge them, and counts for everything set aside, so that a short file can be
 * told apart from a broken one.
 *
 * @param date             the day the file is for
 * @param since            null for the daily file; for an export, the first day
 *                         covered. An export lists every open candidate first seen
 *                         on or after it, not only today's new and updated ones
 * @param candidates       eligible, recommended, not yet decided on, one per role
 * @param closed           candidates that disappeared from their board
 * @param rejections       counts by reason, for the postings seen this run
 * @param alreadyDecided   withheld because the posting was marked applied,
 *                         skipped or shortlisted
 * @param duplicatesCollapsed repeat listings of a role already listed, folded away
 * @param staleSetAside    open long enough to be stale, taken out and counted
 */
public record Digest(
        LocalDate date,
        LocalDate since,
        List<Entry> candidates,
        List<Posting> closed,
        Map<String, Long> rejections,
        List<BoardToken> boards,
        boolean salaryFloorsNeedReverification,
        int alreadyDecided,
        int duplicatesCollapsed,
        int staleSetAside) {

    /**
     * One candidate.
     *
     * @param updated        true when the posting was seen before and its
     *                       description has since changed
     * @param companyApplied true when the tracker already records an application to
     *                       this company. Said, not acted on: an application to one
     *                       Amazon team is no reason to hide every other Amazon role
     */
    public record Entry(Posting posting, boolean updated, boolean companyApplied) {
    }

    /** True for an export over a window, false for the daily file. */
    public boolean isExport() {
        return since != null;
    }

    /** True when there is nothing to read. */
    public boolean isQuiet() {
        return candidates.isEmpty();
    }
}
