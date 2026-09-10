package com.anuragbhandary.jobradar.web;

import com.anuragbhandary.jobradar.domain.CountryCodes;
import com.anuragbhandary.jobradar.domain.Posting;
import com.anuragbhandary.jobradar.domain.StrategicClass;
import com.anuragbhandary.jobradar.repo.PostingRepository;
import com.anuragbhandary.jobradar.strategy.CountryPolicy;
import com.anuragbhandary.jobradar.strategy.CountryStrategy;
import com.anuragbhandary.jobradar.strategy.RelocationTier;
import com.anuragbhandary.jobradar.web.Parts.Tone;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * The strategy, written down where it can be argued with.
 *
 * <p>Every number on this page is already deciding things. The country tiers
 * order the feed, the salary floors are what the digest compares a posting
 * against, and the relocation and remote flags are what stop a role in an
 * unlisted country being read as reachable. Until now all of it lived in
 * {@code application.yml} and the only way to see what the tool believed was to
 * read the file.
 *
 * <p>So this is a view of configuration rather than a place to edit it - editing
 * stays in the YAML, where a change is a line in a diff with a date on it.
 *
 * <h2>Unknown is shown as unknown</h2>
 * A country with no verified salary floor says so. It is not zero, it is not the
 * Indian figure, and it is not quietly omitted: four of the fourteen listed
 * countries have no floor recorded, and a page that only showed the ten with
 * numbers would imply the strategy is more settled than it is.
 */
@Controller
public class StrategyController {

    /**
     * Thousands separated with commas, and no decimals.
     *
     * <p>The digest formats the same figures with a German locale, which renders
     * 45934.2 as "45.934,2" - correct in Berlin and unreadable in an English
     * interface, where it scans as forty-five point nine. A salary floor is a
     * whole number of euros either way; the fraction is an artefact of how the
     * threshold was written into the configuration.
     */
    private static final NumberFormat GROUPED = NumberFormat.getIntegerInstance(Locale.UK);

    private final CountryStrategy strategy;
    private final PostingRepository postings;

    public StrategyController(CountryStrategy strategy, PostingRepository postings) {
        this.strategy = strategy;
        this.postings = postings;
    }

    @GetMapping(value = "/strategy", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public String strategy() {
        LocalDate today = LocalDate.now();
        List<Posting> open = postings.findRecommended();
        Map<StrategicClass, Long> byLane = new EnumMap<>(StrategicClass.class);
        Map<String, Long> byCountry = new java.util.HashMap<>();
        for (Posting posting : open) {
            byLane.merge(posting.getStrategicClass() == null
                    ? StrategicClass.UNCLASSIFIED : posting.getStrategicClass(), 1L, Long::sum);
            if (posting.getCountryCode() != null) {
                byCountry.merge(posting.getCountryCode(), 1L, Long::sum);
            }
        }

        StringBuilder body = new StringBuilder();
        body.append(Parts.pageHead("Strategy",
                "What Job Radar believes about where you want to work, and what it does "
                        + "with that. These figures rank the feed and set the floors the "
                        + "digest compares against.", null));
        body.append(objective(byLane));
        body.append(countries(today, byCountry));
        body.append(staleness(today));
        return Ui.page("Strategy", "<span class=\"note-muted\">read-only · edit in "
                + "application.yml</span>", body.toString(), Ui.Tab.STRATEGY);
    }

    // ------------------------------------------------------------------

    /**
     * The four lanes, what each is for, and how many are open in it now.
     *
     * <p>The counts are what make this more than a mission statement. "Primary
     * objective" beside "68 open" and "financial safety net" beside "2" is a
     * sentence about the actual state of the search.
     */
    private String objective(Map<StrategicClass, Long> byLane) {
        StringBuilder rows = new StringBuilder("<div class=\"objectives\">");
        for (StrategicClass lane : List.of(
                StrategicClass.INTERNATIONAL_RELOCATION, StrategicClass.INTERNATIONAL_REMOTE,
                StrategicClass.INDIA_HOME, StrategicClass.INDIA_OTHER)) {
            rows.append("""
                    <div class="objective">
                      <div class="obj-head">%s<span class="obj-n">%d open</span></div>
                      <p class="obj-role">%s</p>
                      <p class="obj-why">%s</p>
                    </div>
                    """.formatted(
                            Parts.tag(HomeController.laneTone(lane),
                                    HomeController.laneName(lane)),
                            byLane.getOrDefault(lane, 0L),
                            Ui.esc(role(lane)),
                            Ui.esc(why(lane))));
        }
        rows.append("</div>");
        return Parts.block("The objective", null, rows.toString());
    }

    private static String role(StrategicClass lane) {
        return switch (lane) {
            case INTERNATIONAL_RELOCATION -> "Primary objective";
            case INTERNATIONAL_REMOTE -> "Highest value, no move";
            case INDIA_HOME -> "Financial safety net";
            case INDIA_OTHER -> "Only above the relocation floor";
            case UNCLASSIFIED -> "Screened before the lanes existed";
        };
    }

    /**
     * Why each lane is worth what it is worth.
     *
     * <p>Strategic reasoning rather than financial advice, and deliberately
     * without the personal arithmetic behind it - that lives outside the
     * repository, in a file this one is public enough never to hold.
     */
    private String why(StrategicClass lane) {
        String home = CountryCodes.displayName(strategy.homeCountry());
        return switch (lane) {
            case INTERNATIONAL_RELOCATION -> "Ranked first among the primary and secondary "
                    + "tiers below. A move only makes sense above that country's floor.";
            case INTERNATIONAL_REMOTE -> "An employer abroad paying into " + home
                    + " with no relocation and no permit. Nothing else clears as much.";
            case INDIA_HOME -> "Home city. No rent and no relocation, so it clears its "
                    + "costs at a lower number than anywhere else.";
            case INDIA_OTHER -> "Elsewhere in " + home + " means paying rent, so the floor "
                    + "is higher than at home for the same work.";
            case UNCLASSIFIED -> "Screened before the country columns existed. Re-screening "
                    + "places these.";
        };
    }

    /**
     * Every country with a written policy, grouped by tier.
     *
     * <p>Two flags per country and they are not the same flag. The United States
     * is the case worth reading: excluded for relocation, enabled for remote,
     * because an American company hiring into India is the highest-value lane in
     * the whole strategy and the version before this one could not express that.
     */
    private String countries(LocalDate today, Map<String, Long> open) {
        StringBuilder groups = new StringBuilder();
        for (RelocationTier tier : RelocationTier.values()) {
            List<CountryPolicy> inTier = strategy.policies().stream()
                    .filter(policy -> policy.relocationTier() == tier)
                    .sorted((a, b) -> a.displayName().compareToIgnoreCase(b.displayName()))
                    .toList();
            if (inTier.isEmpty()) {
                continue;
            }
            StringBuilder rows = new StringBuilder();
            inTier.forEach(policy -> rows.append(countryRow(policy, today,
                    open.getOrDefault(policy.countryCode(), 0L))));

            groups.append("""
                    <div class="tier">
                      <div class="tier-head">%s<span class="tier-why">%s</span></div>
                      <div class="tier-rows">%s</div>
                    </div>
                    """.formatted(Parts.tag(tierTone(tier), tierName(tier)),
                            Ui.esc(tierMeaning(tier)), rows));
        }
        return Parts.block("Countries", null,
                "<span class=\"note-muted\">relocation tier · remote is separate</span>",
                groups.toString());
    }

    private String countryRow(CountryPolicy policy, LocalDate today, long open) {
        StringBuilder flags = new StringBuilder();
        flags.append(policy.allowsRelocation()
                ? Parts.state(Tone.OK, "Will relocate")
                : Parts.state(Tone.QUIET, "Not relocating"));
        flags.append(policy.allowsRemote()
                ? Parts.state(Tone.OK, "Remote OK")
                : Parts.state(Tone.QUIET, "No remote"));

        String floor = policy.hasSalaryFloor()
                ? amount(policy) + "<span class=\"floor-basis\">"
                        + Ui.esc(policy.salaryFloorBasis() == null
                                ? "" : policy.salaryFloorBasis()) + "</span>"
                // Said out loud. A country with no verified threshold is not a
                // country with a threshold of zero.
                : "<span class=\"floor-none\">No floor established</span>";

        String verify = policy.verifyBy() == null ? ""
                : policy.isStale(today)
                        ? Parts.state(Tone.WARN, "Re-check: due " + policy.verifyBy())
                        : "<span class=\"note-muted\">checked to " + policy.verifyBy()
                                + "</span>";

        return """
                <div class="country">
                  <span class="cy-name">%s</span>
                  <span class="cy-open">%s</span>
                  <span class="cy-flags">%s</span>
                  <span class="cy-floor">%s</span>
                  <span class="cy-verify">%s</span>
                </div>
                """.formatted(Ui.esc(policy.displayName()),
                        open == 0 ? "" : open + " open", flags, floor, verify);
    }

    /**
     * Figures past their own check-by date.
     *
     * <p>Immigration thresholds move, and a stale one is worse than a missing one
     * because it looks settled. Listed at the bottom rather than as a banner: it
     * is a thing to do, not an emergency.
     */
    private String staleness(LocalDate today) {
        List<CountryPolicy> stale = strategy.stale(today);
        if (stale.isEmpty()) {
            return Parts.block("Verification", null, Parts.blank(
                    "Every figure is inside its check-by date.",
                    "Salary floors and immigration thresholds carry a date they should be "
                            + "re-checked by, so a number that has quietly gone out of date "
                            + "cannot keep deciding things.",
                    null));
        }
        StringBuilder rows = new StringBuilder("<div class=\"surface surface-pad\">");
        rows.append("<p class=\"block-note\">These figures are past the date they were "
                + "meant to be re-checked. They are still being used - nothing falls back "
                + "to a guess - but they are worth an hour.</p><ul class=\"why-list\">");
        stale.forEach(policy -> rows.append("<li>").append(Ui.esc(policy.displayName()))
                .append(" — due ").append(policy.verifyBy())
                .append(policy.salaryFloorBasis() == null ? ""
                        : " (" + Ui.esc(policy.salaryFloorBasis()) + ")")
                .append("</li>"));
        rows.append("</ul></div>");
        return Parts.block("Needs re-checking", String.valueOf(stale.size()),
                rows.toString());
    }

    // ------------------------------------------------------------------

    private static String tierName(RelocationTier tier) {
        return switch (tier) {
            case PRIMARY -> "Primary";
            case SECONDARY -> "Secondary";
            case OPPORTUNISTIC -> "Opportunistic";
            case LOW -> "Low priority";
            case EXCLUDED -> "Not relocating";
            case UNKNOWN -> "No policy written";
        };
    }

    private static String tierMeaning(RelocationTier tier) {
        return switch (tier) {
            case PRIMARY -> "Move here.";
            case SECONDARY -> "Worth a real application.";
            case OPPORTUNISTIC -> "Worth it for the right role.";
            case LOW -> "Only if something unusual is on offer.";
            case EXCLUDED -> "Not part of the relocation plan. Remote from these employers "
                    + "may still be.";
            case UNKNOWN -> "Nothing decided yet.";
        };
    }

    private static Tone tierTone(RelocationTier tier) {
        return switch (tier) {
            case PRIMARY -> Tone.OK;
            case SECONDARY -> Tone.INFO;
            case OPPORTUNISTIC, LOW -> Tone.QUIET;
            case EXCLUDED, UNKNOWN -> Tone.QUIET;
        };
    }

    /**
     * Rupees in lakhs, everything else grouped normally.
     *
     * <p>"Rs 700,000" invites a misread as seven million, which is the whole
     * reason the lakh grouping exists elsewhere in this application.
     */
    private static String amount(CountryPolicy policy) {
        BigDecimal floor = policy.salaryFloor();
        if ("INR".equalsIgnoreCase(policy.currency())) {
            return "Rs " + com.anuragbhandary.jobradar.digest.SalaryFloorAdvisor
                    .indianGrouping(floor);
        }
        return policy.currency() + " " + GROUPED.format(floor);
    }
}
