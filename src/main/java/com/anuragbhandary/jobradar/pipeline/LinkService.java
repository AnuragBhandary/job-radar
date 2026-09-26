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
        return new Link(posting.getUrl(), true, result, null);
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
