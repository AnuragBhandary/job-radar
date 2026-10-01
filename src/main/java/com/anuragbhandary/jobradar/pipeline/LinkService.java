package com.anuragbhandary.jobradar.pipeline;

import com.anuragbhandary.jobradar.domain.BoardToken;
import com.anuragbhandary.jobradar.domain.Employer;
import com.anuragbhandary.jobradar.domain.Posting;
import com.anuragbhandary.jobradar.domain.Source;
import com.anuragbhandary.jobradar.fetch.DirectLinkResolver;
import com.anuragbhandary.jobradar.fetch.LinkChecker;
import com.anuragbhandary.jobradar.repo.BoardTokenRepository;
import com.anuragbhandary.jobradar.repo.PostingRepository;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * The link a person should apply through, and whether it still works.
 *
 * <p>For an aggregator posting the employer's own page is found first
 * ({@link DirectLinkResolver}); when that page is on a platform job-radar can
 * fetch, the employer's board is added so its other roles arrive directly from
 * then on. Then the chosen link is checked ({@link LinkChecker}).
 */
@Service
public class LinkService {

    /** What to apply through, and its state. {@code url} is null when unknown. */
    public record Link(String url, boolean direct, LinkChecker.Result check, String note) {
    }

    private final DirectLinkResolver resolver;
    private final LinkChecker checker;
    private final PostingRepository postings;
    private final BoardTokenRepository boards;

    public LinkService(DirectLinkResolver resolver, LinkChecker checker,
            PostingRepository postings, BoardTokenRepository boards) {
        this.resolver = resolver;
        this.checker = checker;
        this.postings = postings;
        this.boards = boards;
    }

    public static boolean isAggregator(Source source) {
        return switch (source) {
            case ARBEITNOW, JOBICY, WE_WORK_REMOTELY, HACKER_NEWS -> true;
            default -> false;
        };
    }

    /**
     * Resolves (for aggregators) and checks the link for a posting. Saves a found
     * direct link on the posting, and adds the employer's board when it is new.
     */
    public Link forPosting(Posting posting) {
        String note = null;
        if (isAggregator(posting.getSource())) {
            if (posting.getDirectUrl() == null) {
                String[] companyRole = Employer.split(null, posting.getSource(), posting.getTitle());
                Optional<DirectLinkResolver.Found> found =
                        resolver.resolve(companyRole[0], companyRole[1]);
                if (found.isPresent()) {
                    posting.setDirectUrl(found.get().url());
                    postings.save(posting);
                    note = addBoard(found.get(), companyRole[0]);
                }
            }
            if (posting.getDirectUrl() == null) {
                return new Link(null, false,
                        new LinkChecker.Result(LinkChecker.State.UNKNOWN, "no employer page found"),
                        "employer page not found automatically");
            }
            return new Link(posting.getDirectUrl(), true, checker.check(posting.getDirectUrl()), note);
        }
        LinkChecker.Result result = checker.checkListing(posting.getSource(),
                posting.getBoardToken(), posting.getExternalId(), posting.getUrl());
        if (result.state() == LinkChecker.State.UNKNOWN) {
            result = absentFromBoard(posting).orElse(result);
        }
        // A link set by hand with mark --url is the one the person checked and
        // will apply on, so it is the one sent. The listing's link used to win
        // over it, and a pick whose board page 404ed was dropped even after a
        // working link was recorded (NTT DATA, 2026-10-01). When the listing is
        // known to be open that settles it; otherwise the hand-set link is checked.
        if (posting.getDirectUrl() != null && !posting.getDirectUrl().equals(posting.getUrl())) {
            LinkChecker.Result direct = result.state() == LinkChecker.State.LIVE
                    ? new LinkChecker.Result(LinkChecker.State.LIVE, result.reason() + "; link set by hand")
                    : checker.check(posting.getDirectUrl());
            return new Link(posting.getDirectUrl(), true, direct, null);
        }
        return new Link(posting.getUrl(), true, result, null);
    }

    /** Two fetches a day apart, both without the posting, before it counts as gone. */
    static final java.time.Duration ABSENT_FOR = java.time.Duration.ofHours(36);

    /**
     * What the fetch already knows, for when the page itself is inconclusive.
     *
     * <p>On 2026-09-28 Target's Workday page answered 403 and was reported as
     * "often weekend maintenance" on a Monday, while the database showed the
     * posting missing from four days of successful fetches of that board. A
     * board that answers but no longer lists the role is evidence the role is
     * gone. Only consulted after an UNKNOWN: a page that answers LIVE wins.
     */
    Optional<LinkChecker.Result> absentFromBoard(Posting posting) {
        if (posting.getLastSeen() == null) {
            return Optional.empty();
        }
        Optional<BoardToken> board =
                boards.findBySourceAndToken(posting.getSource(), posting.getBoardToken());
        if (board.isEmpty() || board.get().isBroken() || board.get().getLastFetchedAt() == null) {
            return Optional.empty();
        }
        java.time.Instant fetched = board.get().getLastFetchedAt();
        if (java.time.Duration.between(posting.getLastSeen(), fetched).compareTo(ABSENT_FOR) < 0) {
            return Optional.empty();
        }
        java.time.LocalDate lastSeen =
                posting.getLastSeen().atZone(java.time.ZoneId.systemDefault()).toLocalDate();
        return Optional.of(new LinkChecker.Result(LinkChecker.State.DEAD,
                "no longer on the employer's board (last seen " + lastSeen + ")"));
    }

    private String addBoard(DirectLinkResolver.Found found, String company) {
        if (found.source() == null) {
            return "found on " + found.platform() + " (not a fetched platform)";
        }
        if (boards.findBySourceAndToken(found.source(), found.token()).isPresent()) {
            return "employer board already fetched";
        }
        boards.save(new BoardToken(found.source(), found.token(), company));
        return "added " + found.source() + "/" + found.token() + " as a board";
    }
}
