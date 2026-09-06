package com.anuragbhandary.jobradar.fetch;

import com.anuragbhandary.jobradar.domain.Source;
import java.util.List;

/** Reads one board from one applicant tracking system. */
public interface AtsFetcher {

    Source source();

    /**
     * Fetches every posting on the board.
     *
     * @throws FetchException if the board could not be read at all. An empty list
     *                        means the board is reachable and has no postings,
     *                        which is a different thing and must not be conflated.
     */
    List<RawPosting> fetch(String boardToken) throws FetchException;
}
