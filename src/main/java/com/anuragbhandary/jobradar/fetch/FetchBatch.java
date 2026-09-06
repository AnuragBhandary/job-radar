package com.anuragbhandary.jobradar.fetch;

import java.util.List;

/**
 * What one board returned.
 *
 * <p>{@code boardTotal} exists because it is not always {@code postings.size()}.
 * SmartRecruiters has no descriptions in its list response, so that fetcher
 * filters on geography and title before spending a request per posting - and if
 * the shortlisted count were recorded as board health, a board of four hundred
 * postings with no matches would look identical to a board that had gone empty.
 * The two numbers answer different questions and are kept apart.
 *
 * @param postings   the postings worth storing
 * @param boardTotal how many postings the board actually advertises
 */
public record FetchBatch(List<RawPosting> postings, int boardTotal) {

    /** For boards where every posting is returned, the two counts are the same. */
    public static FetchBatch of(List<RawPosting> postings) {
        return new FetchBatch(postings, postings.size());
    }
}
