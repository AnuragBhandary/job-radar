package com.anuragbhandary.jobradar.fetch;

import java.time.LocalDate;

/**
 * A posting as the board gave it, before any interpretation.
 *
 * <p>Deliberately flat and dumb. Every fetcher's job is to reduce its board's
 * particular JSON to this shape and stop; deciding what the fields mean is
 * {@link PostingMapper}'s job, in one place, so that a change to hashing or
 * normalisation does not have to be made five times.
 *
 * @param externalId the ATS's own id for the posting
 * @param title      as published
 * @param location   raw location string, unparsed
 * @param description plain text, tags already stripped by the fetcher
 * @param url        official apply URL
 * @param postedDate nullable - many boards do not publish one
 */
public record RawPosting(
        String externalId,
        String title,
        String location,
        String description,
        String url,
        LocalDate postedDate) {
}
