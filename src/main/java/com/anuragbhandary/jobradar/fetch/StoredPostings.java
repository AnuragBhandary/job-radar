package com.anuragbhandary.jobradar.fetch;

import com.anuragbhandary.jobradar.domain.Posting;
import com.anuragbhandary.jobradar.domain.PostingStatus;
import com.anuragbhandary.jobradar.domain.Source;
import com.anuragbhandary.jobradar.repo.PostingRepository;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Postings a list-then-detail fetcher has already read, so it need not read them again.
 *
 * <p>Workday, Eightfold, Oracle and SmartRecruiters give a list without
 * descriptions and one request per job for the rest. Re-reading every open job's
 * details on every run is what made a run take 44 minutes once discovery had
 * added five hundred Workday sites, all behind one throttle. A job already
 * stored is now returned from the database instead.
 *
 * <p>So that an edited description is still noticed, each stored job is read
 * again one day in seven, on a day that depends on its id; the re-reads are
 * spread evenly over the week instead of falling on one run.
 *
 * <p>This is the one database read these fetchers make, once per board, at the
 * start; they still write nothing.
 */
@Component
public class StoredPostings {

    static final int REFRESH_EVERY_DAYS = 7;

    private final PostingRepository postings;

    public StoredPostings(PostingRepository postings) {
        this.postings = postings;
    }

    /** No stored postings at all; for tests and one-off fetches. */
    public static StoredPostings none() {
        return new StoredPostings(null);
    }

    /** One board's open postings, keyed by external id. */
    public Known open(Source source, String boardToken) {
        Map<String, RawPosting> byId = new HashMap<>();
        if (postings != null) {
            for (Posting p : postings.findBySourceAndBoardToken(source, boardToken)) {
                if (p.getStatus() != PostingStatus.CLOSED && p.getDescriptionText() != null) {
                    byId.put(p.getExternalId(), new RawPosting(p.getExternalId(), p.getTitle(),
                            p.getLocation(), p.getDescriptionText(), p.getUrl(), p.getPostedDate()));
                }
            }
        }
        return new Known(byId, LocalDate.now().toEpochDay());
    }

    /** The stored postings of one board, as of one day. */
    public static final class Known {
        private final Map<String, RawPosting> byId;
        private final long today;
        private int reused;

        Known(Map<String, RawPosting> byId, long today) {
            this.byId = byId;
            this.today = today;
        }

        /** The stored posting, unless there is none or today is its day to be read again. */
        public RawPosting reuse(String externalId) {
            if (externalId == null) {
                return null;
            }
            RawPosting known = byId.get(externalId);
            if (known == null || Math.floorMod(externalId.hashCode() + today, REFRESH_EVERY_DAYS) == 0) {
                return null;
            }
            reused++;
            return known;
        }

        public int reused() {
            return reused;
        }
    }
}
