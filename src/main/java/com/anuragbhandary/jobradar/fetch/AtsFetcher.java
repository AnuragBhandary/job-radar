package com.anuragbhandary.jobradar.fetch;

import com.anuragbhandary.jobradar.domain.Source;

/** Reads one board from one applicant tracking system. */
public interface AtsFetcher {

    Source source();

    /**
     * Fetches the board.
     *
     * @throws FetchException if the board could not be read at all. An empty
     *                        batch means the board is reachable and has nothing
     *                        for us, which is a different thing and must not be
     *                        conflated with a failure.
     */
    FetchBatch fetch(String boardToken) throws FetchException;
}
