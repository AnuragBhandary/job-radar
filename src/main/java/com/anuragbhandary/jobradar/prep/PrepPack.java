package com.anuragbhandary.jobradar.prep;

import java.util.List;

/**
 * What to read before an interview for one posting.
 *
 * @param covered  technologies the posting names that his own work already covers.
 *                 The answer to "why are you a fit?", in specifics.
 * @param gaps     technologies the posting names that appear nowhere in his resume.
 *                 The useful half: these are what will be asked about and what he
 *                 currently has no story for.
 * @param talkingPoints his own bullets that matched, verbatim - so the story told
 *                 in the interview is the one on the paper they are holding
 */
public record PrepPack(
        String company,
        String role,
        String location,
        String url,
        String yearsWording,
        String salaryText,
        String sponsorshipSignal,
        List<String> covered,
        List<String> gaps,
        List<String> talkingPoints,
        List<String> questionsToExpect,
        List<String> questionsToAsk,
        String notes) {
}
