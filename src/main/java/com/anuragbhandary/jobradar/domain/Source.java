package com.anuragbhandary.jobradar.domain;

/**
 * The applicant tracking systems job-radar knows how to read.
 *
 * <p>Adding a value here is a schema migration - Hibernate writes a SQLite CHECK
 * constraint listing these names and SQLite cannot alter one. {@link
 * com.anuragbhandary.jobradar.config.SchemaMigrator} now widens it on startup, so
 * this is no longer a "delete the database and re-fetch" change.
 *
 * <p>Three platforms were investigated and are deliberately absent:
 * <ul>
 *   <li><b>Workable</b> - its public widget endpoint still returns account
 *       metadata but an empty {@code jobs} array for every board tried, and the
 *       {@code spi/v3} endpoint requires an OAuth token.</li>
 *   <li><b>Personio</b> - its XML feed publishes titles and offices but an empty
 *       {@code jobDescriptions} element, and the job pages are client-rendered.
 *       Screening depends on the description, so the feed alone is not enough and
 *       the alternative is a browser per posting.</li>
 *   <li><b>Teamtailor</b> - no public JSON feed; its API needs a per-company key.</li>
 * </ul>
 */
public enum Source {
    GREENHOUSE,
    ASHBY,
    LEVER,
    SMARTRECRUITERS,
    AMAZON,
    WORKDAY,
    RECRUITEE
}
