package com.anuragbhandary.jobradar.web;

import com.anuragbhandary.jobradar.apply.AttemptStatus;
import com.anuragbhandary.jobradar.domain.Posting;
import com.anuragbhandary.jobradar.domain.WorkMode;
import com.anuragbhandary.jobradar.match.MatchScore;
import com.anuragbhandary.jobradar.money.AskingPrice;
import com.anuragbhandary.jobradar.web.Parts.Tone;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Optional;

/**
 * One posting, wherever it is shown.
 *
 * <p>Shared by Today and Jobs so a job looks the same in both, and so the four
 * questions it has to answer are answered the same way each time:
 *
 * <ul>
 *   <li><b>What is it?</b> company and role;</li>
 *   <li><b>Where?</b> country, city, work mode, and whether it may be worked
 *       from India - which is the one that decides whether it is even a
 *       possibility;</li>
 *   <li><b>Why should I care?</b> the score with its strongest reason, the
 *       strategic lane, and what the posting says about sponsorship and pay;</li>
 *   <li><b>What can I do?</b> one primary action whose wording reflects where
 *       this application already got to.</li>
 * </ul>
 *
 * <h2>Two different numbers called salary</h2>
 * A posting's stated pay and the figure to write in its salary box are not the
 * same thing and are labelled apart here. They were not: the card showed the
 * asking band in the position a reader takes for the employer's offer, so a US
 * posting that stated nothing appeared to pay twelve to eighteen lakh. Stated pay
 * is shown only when the posting actually states it.
 */
final class JobCard {

    private JobCard() {
    }

    /**
     * @param applied what has already happened to this posting, or null when
     *                nothing has. Decides the primary action's wording.
     */
    record Applied(long attemptId, AttemptStatus status) {
    }

    static String render(Posting posting, MatchScore score, String company,
            Optional<AskingPrice> ask, Applied applied) {

        StringBuilder facts = new StringBuilder("<div class=\"jc-facts\">");
        if (posting.getStrategicClass() != null) {
            facts.append(Parts.tag(HomeController.laneTone(posting.getStrategicClass()),
                    HomeController.laneName(posting.getStrategicClass())));
        }
        if (posting.getLocation() != null && !posting.getLocation().isBlank()) {
            facts.append(Parts.tag(posting.getLocation()));
        }
        if (posting.getWorkMode() != null && posting.getWorkMode() != WorkMode.UNKNOWN) {
            facts.append(Parts.tag(readable(posting.getWorkMode().name())));
        }
        // The single most decisive fact on the card, so it is said in words
        // rather than left to be inferred from a work mode.
        if (posting.allowsRemoteFromIndia()) {
            facts.append(Parts.tag(Tone.OK, "Remote from India"));
        }
        facts.append("</div>");

        StringBuilder money = new StringBuilder();
        if (posting.getSalaryText() != null && !posting.getSalaryText().isBlank()) {
            money.append("<span class=\"jc-pay\"><span class=\"jc-pay-k\">They state</span>")
                    .append(Ui.esc(posting.getSalaryText())).append("</span>");
        }
        ask.ifPresent(price -> money.append(
                "<span class=\"jc-pay\"><span class=\"jc-pay-k\">Your ask</span>")
                .append(Ui.esc(price.annualRange()))
                .append(price.convertible()
                        ? " <span class=\"jc-pay-inr\">" + Ui.esc(price.annualInRupees())
                                + "</span>"
                        : "")
                .append("</span>"));

        return """
                <article class="jobrow">
                  <div class="jc-score" title="%s">
                    <span class="jc-n">%d</span>
                    <span class="jc-band">%s</span>
                  </div>
                  <div class="jc-main">
                    <div class="jc-title">
                      <a href="/posting/%d">%s</a>
                      <span class="jc-role">%s</span>
                    </div>
                    %s
                    <p class="jc-why">%s</p>
                    <div class="jc-money">%s</div>
                  </div>
                  <div class="jc-side">%s</div>
                </article>
                """.formatted(
                        Ui.esc("Match " + score.score() + " of 100"),
                        score.score(),
                        Ui.esc(score.band().label()),
                        posting.getId(), Ui.esc(company), Ui.esc(posting.getTitle()),
                        facts,
                        Ui.esc(why(posting, score)),
                        money,
                        actions(posting, applied));
    }

    /**
     * The one line that says why this is on the list.
     *
     * <p>The scorer's own headline where there is one; otherwise a fact from the
     * posting rather than a compliment. "Posted yesterday" is worth reading and
     * "great match!" is not.
     */
    private static String why(Posting posting, MatchScore score) {
        String headline = score.headline();
        if (headline != null && !headline.isBlank()) {
            return headline;
        }
        if (posting.getSponsorshipSignal() != null
                && !posting.getSponsorshipSignal().isBlank()) {
            return "Sponsorship: " + posting.getSponsorshipSignal();
        }
        return age(posting.getPostedDate());
    }

    /**
     * One primary action, worded for where this application already is.
     *
     * <p>"Prepare application" on something already prepared is an offer to do
     * the work twice, and on something already sent it is worse than that. The
     * button says what pressing it will actually do.
     */
    private static String actions(Posting posting, Applied applied) {
        String save = Components.postButton("/save", "Save", "saving", "postingId",
                posting.getId(), "btn btn-sm");
        if (applied == null) {
            return save + Components.postButton("/prepare", "Prepare", "opening the form",
                    "postingId", posting.getId(), "btn btn-sm btn-primary");
        }
        String href = "/attempt/" + applied.attemptId();
        String label = switch (applied.status()) {
            case SUBMITTED -> "View application";
            case AWAITING_ANSWER, AWAITING_APPROVAL, NEEDS_HUMAN -> "Resolve";
            case MANUAL_REQUIRED -> "Finish by hand";
            case PREPARING -> "Preparing…";
            case FAILED -> "Try again";
            default -> "Review preparation";
        };
        return Parts.linkButton(href, label, "btn btn-sm btn-primary");
    }

    static String age(LocalDate posted) {
        if (posted == null) {
            return "No posting date recorded";
        }
        long days = ChronoUnit.DAYS.between(posted, LocalDate.now());
        if (days <= 0) {
            return "Posted today";
        }
        return days == 1 ? "Posted yesterday" : "Posted " + days + " days ago";
    }

    private static String readable(String name) {
        return name.toLowerCase(Locale.ROOT).replace('_', ' ');
    }
}
