package com.anuragbhandary.jobradar.domain;

/**
 * Normalised hiring geography.
 *
 * <p>REMOTE means genuinely global-remote, i.e. hireable into India. A posting
 * that says "Remote" but names another country - "Remote (Argentina)" - is not
 * REMOTE, it is OTHER, because the country in the title is a hiring constraint
 * rather than a perk. That distinction is the single most expensive mistake this
 * tool exists to prevent.
 */
public enum Country {
    INDIA,
    GERMANY,
    IRELAND,
    NETHERLANDS,
    REMOTE,
    OTHER
}
