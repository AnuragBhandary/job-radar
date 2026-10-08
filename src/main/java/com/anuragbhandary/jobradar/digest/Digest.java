package com.anuragbhandary.jobradar.digest;

import com.anuragbhandary.jobradar.domain.BoardToken;
import com.anuragbhandary.jobradar.domain.Posting;
import com.anuragbhandary.jobradar.pipeline.JobInterest;
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
 * @param shortlisted      postings marked shortlist and not yet applied to, for
 *                         {@code openings}: a shortlist nobody acts on is a list
 *                         of jobs that close
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
        int staleSetAside,
        List<JobInterest> shortlisted,
        int belowFitFloor) {

    public Digest(LocalDate date, LocalDate since, List<Entry> candidates, List<Posting> closed,
            Map<String, Long> rejections, List<BoardToken> boards,
            boolean salaryFloorsNeedReverification, int alreadyDecided,
            int duplicatesCollapsed, int staleSetAside, List<JobInterest> shortlisted) {
        this(date, since, candidates, closed, rejections, boards, salaryFloorsNeedReverification,
                alreadyDecided, duplicatesCollapsed, staleSetAside, shortlisted, 0);
    }

    /** Without a shortlist section: the daily file and the export. */
    public Digest(LocalDate date, LocalDate since, List<Entry> candidates, List<Posting> closed,
            Map<String, Long> rejections, List<BoardToken> boards,
            boolean salaryFloorsNeedReverification, int alreadyDecided,
            int duplicatesCollapsed, int staleSetAside) {
        this(date, since, candidates, closed, rejections, boards, salaryFloorsNeedReverification,
                alreadyDecided, duplicatesCollapsed, staleSetAside, List.of(), 0);
    }

    /**
     * One candidate.
     *
     * @param updated        true when the posting was seen before and its
     *                       description has since changed
     * @param companyApplied true when the tracker already records an application to
     *                       this company. Said, not acted on: an application to one
     *                       Amazon team is no reason to hide every other Amazon role
     */
    /**
     * @param fit     the ranking score, or null outside the openings file
     * @param compact one line instead of a full block: below the fit cut
     * @param similar other openings at the same employer whose titles differ only
     *                in brackets (Visa's "Data Engineer" and "Data Engineer (1 - 2
     *                years ... Kafka)"): listed under this one, each still decided
     * @param featured one of the best few of its category, shown in full ahead of
     *                 the rest whatever its score
     */
    public record Entry(Posting posting, boolean updated, boolean companyApplied,
            FitScore.Fit fit, boolean compact, List<Entry> similar, boolean featured) {

        public Entry(Posting posting, boolean updated, boolean companyApplied,
                FitScore.Fit fit, boolean compact, List<Entry> similar) {
            this(posting, updated, companyApplied, fit, compact, similar, false);
        }

        public Entry(Posting posting, boolean updated, boolean companyApplied,
                FitScore.Fit fit, boolean compact) {
            this(posting, updated, companyApplied, fit, compact, List.of());
        }

        /** The kind of role, from the title. */
        public com.anuragbhandary.jobradar.domain.RoleCategory category() {
            return com.anuragbhandary.jobradar.domain.RoleCategory.of(posting.getTitle());
        }

        public Entry(Posting posting, boolean updated, boolean companyApplied) {
            this(posting, updated, companyApplied, null, false);
        }

        /** This entry's posting id and every similar one's, for marking. */
        public List<Long> ids() {
            List<Long> ids = new java.util.ArrayList<>();
            ids.add(posting.getId());
            similar.forEach(s -> ids.add(s.posting().getId()));
            return ids;
        }
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
