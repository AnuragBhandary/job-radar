package com.anuragbhandary.jobradar.filter;

import java.util.Map;

/**
 * What a screening pass did.
 *
 * @param rejectionsByReason rejection counts grouped by the leading part of the
 *                           reason, so the summary reads as "why are postings
 *                           being dropped" rather than as five thousand strings
 */
public record ScreenSummary(
        int screened,
        int candidates,
        int rejected,
        int needsHumanReview,
        int graduateSignals,
        Map<String, Long> rejectionsByReason) {
}
