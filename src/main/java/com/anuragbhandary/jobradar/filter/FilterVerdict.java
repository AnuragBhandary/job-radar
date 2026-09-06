package com.anuragbhandary.jobradar.filter;

/**
 * The outcome of one filter.
 *
 * @param accepted whether the posting survives this filter
 * @param reason   why it was rejected, quoting the offending text where possible.
 *                 Null when accepted. A rejection without a reason is not a
 *                 useful rejection - it cannot be argued with, and it cannot be
 *                 used to spot a filter that has gone wrong.
 */
public record FilterVerdict(boolean accepted, String reason) {

    private static final FilterVerdict ACCEPTED = new FilterVerdict(true, null);

    public static FilterVerdict accept() {
        return ACCEPTED;
    }

    public static FilterVerdict reject(String reason) {
        return new FilterVerdict(false, reason);
    }
}
