package com.anuragbhandary.jobradar.filter;

import java.util.Map;

/**
 * What a screening pass did.
 *
 * <p>The distribution maps are what make a rules change reviewable. Screening
 * rewrites nine thousand verdicts on every run, so the only honest way to change
 * a filter is to compare the shape of the corpus before and after and be able to
 * say why it moved. They cover candidates only: the interesting question is what
 * survived, and a breakdown of eight thousand rejections by country is noise.
 *
 * @param candidates         eligible - the job could be pursued at all
 * @param recommended        of those, the ones the current strategy puts in the
 *                           default feed. Deliberately a separate number: a job
 *                           in Zurich is eligible and not recommended, and before
 *                           this phase there was no way to say that.
 * @param rejectionsByReason rejection counts grouped by the leading part of the
 *                           reason, so the summary reads as "why are postings
 *                           being dropped" rather than as five thousand strings
 * @param byStrategicClass   candidates per lane: India home/other, international
 *                           relocation, international remote
 * @param byWorkMode         candidates per work mode, which is where a remote
 *                           classification regression would show up first
 * @param byCountry          candidates per ISO code; {@code ??} means the
 *                           vocabulary recognised no country and the posting
 *                           needs classifying
 * @param byOutcome          candidates per strategy outcome
 */
public record ScreenSummary(
        int screened,
        int candidates,
        int rejected,
        int needsHumanReview,
        int graduateSignals,
        Map<String, Long> rejectionsByReason,
        int recommended,
        Map<String, Long> byStrategicClass,
        Map<String, Long> byWorkMode,
        Map<String, Long> byCountry,
        Map<String, Long> byOutcome) {
}
