package com.anuragbhandary.jobradar.fetch;

import com.anuragbhandary.jobradar.domain.Source;

/**
 * What one board's fetch did.
 *
 * @param error null on success. A board that failed keeps its previous counts,
 *              so the digest can say "was 412, now erroring" instead of "0".
 */
public record FetchResult(
        Source source,
        String boardToken,
        int fetched,
        int created,
        int updated,
        int unchanged,
        String error) {

    public static FetchResult failure(Source source, String boardToken, String error) {
        return new FetchResult(source, boardToken, 0, 0, 0, 0, error);
    }

    public boolean failed() {
        return error != null;
    }

    @Override
    public String toString() {
        return failed()
                ? "%s/%s FAILED: %s".formatted(source, boardToken, error)
                : "%s/%s %d postings (%d new, %d updated, %d unchanged)"
                        .formatted(source, boardToken, fetched, created, updated, unchanged);
    }
}
