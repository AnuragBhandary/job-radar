package com.anuragbhandary.jobradar.web;

import com.anuragbhandary.jobradar.apply.ApplicationAttempt;
import com.anuragbhandary.jobradar.apply.ApplicationAttemptRepository;
import com.anuragbhandary.jobradar.domain.CountryCodes;
import com.anuragbhandary.jobradar.domain.Posting;
import com.anuragbhandary.jobradar.domain.StrategicClass;
import com.anuragbhandary.jobradar.domain.WorkMode;
import com.anuragbhandary.jobradar.match.MatchScore;
import com.anuragbhandary.jobradar.match.MatchScorer;
import com.anuragbhandary.jobradar.money.SalaryGuide;
import com.anuragbhandary.jobradar.repo.PostingRepository;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * Jobs: discovery and the decision to apply.
 *
 * <p>Not the queue. The page it replaces opened with three sections of
 * <em>attempts</em> - blocked, prepared, submitted - above the postings, so the
 * one page meant for finding new work led with work already started. Those belong
 * on Today, where the rest of the queue lives, and this page is now only about
 * what to apply to next.
 *
 * <h2>Filters that match the data</h2>
 * The old filter row was the five-value legacy {@code Country} enum, so every
 * British, Australian and Canadian role filtered as "Other" - a bucket of
 * thirty-two things with nothing in common. The filters are the structured
 * columns the country phase added: the strategic lane, the ISO country, the work
 * mode, whether it may be worked from India, and how fresh it is.
 *
 * <h2>Why it pages</h2>
 * The corpus is nine thousand postings and grows daily. Rendering even the
 * eighty-eight recommended ones as full cards is a page nobody reads to the
 * bottom; the list shows a screenful and says how many are behind it.
 */
@Controller
public class JobsController {

    /** Cards per page. Enough to compare, few enough to finish. */
    private static final int PAGE = 25;

    /** A posting stops being news after a week - the same window Today uses. */
    private static final int FRESH_DAYS = 7;

    private final PostingRepository postings;
    private final ApplicationAttemptRepository attempts;
    private final MatchScorer scorer;
    private final SalaryGuide salary;
    private final CompanyNames companies;

    public JobsController(PostingRepository postings, ApplicationAttemptRepository attempts,
            MatchScorer scorer, SalaryGuide salary, CompanyNames companies) {
        this.postings = postings;
        this.attempts = attempts;
        this.scorer = scorer;
        this.salary = salary;
        this.companies = companies;
    }

    /** One posting with everything the card needs, worked out once. */
    private record Row(Posting posting, MatchScore score, JobCard.Applied applied) {
    }

    @GetMapping(value = "/jobs", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public String jobs(@RequestParam(required = false) String lane,
            @RequestParam(required = false) String country,
            @RequestParam(required = false) String mode,
            @RequestParam(required = false) String fresh,
            @RequestParam(required = false) String status,
            @RequestParam(required = false, defaultValue = "0") int page) {

        // Recommended, not merely eligible. The corpus holds every US and
        // unlisted-country role it can classify instead of throwing them away,
        // and a feed that showed all of them would be the widening making the
        // tool worse. They are one query away, not one re-screen away.
        List<Posting> candidates = postings.findRecommended();
        Map<Long, ApplicationAttempt> byPosting = newestAttempts();
        LocalDate today = LocalDate.now();

        List<Row> all = candidates.stream()
                .map(posting -> new Row(posting, scorer.score(posting),
                        applied(byPosting.get(posting.getId()))))
                // Scored, then ranked. Sorting by date was close to random: a
                // posting is not more relevant for being newer, and "newest
                // first" over eighty candidates meant reading them in the order
                // the boards happened to publish.
                .sorted(Comparator.comparingInt((Row row) -> row.score().score()).reversed()
                        .thenComparing(row -> row.posting().getPostedDate(),
                                Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();

        List<Row> shown = all.stream()
                .filter(laneFilter(lane))
                .filter(countryFilter(country))
                .filter(modeFilter(mode))
                .filter(freshFilter(fresh, today))
                .filter(statusFilter(status))
                .toList();

        StringBuilder body = new StringBuilder();
        body.append(Parts.pageHead("Jobs",
                candidates.size() + " opportunities are open and recommended by your "
                        + "strategy. They are ranked by fit, not by date.", null));
        body.append(filters(all, lane, country, mode, fresh, status));
        body.append(list(shown, page, lane, country, mode, fresh, status));

        String stat = "<strong>" + shown.size() + "</strong> shown"
                + (shown.size() == all.size() ? ""
                        : "<span class=\"sep\">·</span>" + all.size() + " open");
        return Ui.page("Jobs", stat, body.toString(), Ui.Tab.JOBS);
    }

    // ------------------------------------------------------------------
    // Filters
    // ------------------------------------------------------------------

    /**
     * Five rows of chips, each counted against the unfiltered list.
     *
     * <p>The counts are the useful half: "International — Remote 11" says whether
     * the filter is worth pressing before it is pressed. They are deliberately
     * <em>not</em> recomputed against the current selection, because a chip that
     * changes its own number when you select another chip is a chip nobody trusts.
     */
    private String filters(List<Row> all, String lane, String country, String mode,
            String fresh, String status) {

        StringBuilder out = new StringBuilder("<nav class=\"filterbar\" "
                + "aria-label=\"Filter the job list\">");

        Map<String, Long> lanes = count(all, row -> row.posting().getStrategicClass() == null
                ? null : row.posting().getStrategicClass().name());
        group(out, "Strategic lane", "lane", lane, lanes, all.size(),
                key -> HomeController.laneName(StrategicClass.valueOf(key)),
                Map.of("lane", "", "country", nz(country), "mode", nz(mode),
                        "fresh", nz(fresh), "status", nz(status)));

        Map<String, Long> countries = count(all, row -> row.posting().getCountryCode());
        group(out, "Country", "country", country, top(countries, 10), all.size(),
                CountryCodes::displayName,
                Map.of("lane", nz(lane), "country", "", "mode", nz(mode),
                        "fresh", nz(fresh), "status", nz(status)));

        Map<String, Long> modes = count(all, row -> row.posting().getWorkMode() == null
                || row.posting().getWorkMode() == WorkMode.UNKNOWN
                        ? null : row.posting().getWorkMode().name());
        group(out, "Work mode", "mode", mode, modes, all.size(),
                key -> readable(key), Map.of("lane", nz(lane), "country", nz(country),
                        "mode", "", "fresh", nz(fresh), "status", nz(status)));

        LocalDate today = LocalDate.now();
        Map<String, Long> freshness = new LinkedHashMap<>();
        freshness.put("week", all.stream()
                .filter(row -> isFresh(row.posting(), today, FRESH_DAYS)).count());
        freshness.put("month", all.stream()
                .filter(row -> isFresh(row.posting(), today, 30)).count());
        group(out, "Freshness", "fresh", fresh, freshness, all.size(),
                key -> "week".equals(key) ? "This week" : "This month",
                Map.of("lane", nz(lane), "country", nz(country), "mode", nz(mode),
                        "fresh", "", "status", nz(status)));

        Map<String, Long> states = new LinkedHashMap<>();
        states.put("new", all.stream().filter(row -> row.applied() == null).count());
        states.put("started", all.stream().filter(row -> row.applied() != null).count());
        // "Remote from India" is a status of the opportunity rather than of the
        // application, and it is the single most decisive filter on the page -
        // the whole international-remote lane turns on it.
        states.put("remote-india",
                all.stream().filter(row -> row.posting().allowsRemoteFromIndia()).count());
        group(out, "Show", "status", status, states, all.size(),
                key -> switch (key) {
                    case "new" -> "Not started";
                    case "started" -> "Already started";
                    default -> "Remote from India";
                },
                Map.of("lane", nz(lane), "country", nz(country), "mode", nz(mode),
                        "fresh", nz(fresh), "status", ""));

        return out.append("</nav>").toString();
    }

    private void group(StringBuilder out, String title, String param, String active,
            Map<String, Long> options, int total, java.util.function.UnaryOperator<String> label,
            Map<String, String> others) {

        if (options.isEmpty()) {
            return;
        }
        out.append("<div class=\"filter-group\"><span>").append(Ui.esc(title))
                .append("</span><div class=\"filter-opts\">");
        out.append(chip("All", total, link(param, null, others), active == null));
        options.forEach((key, count) -> out.append(chip(label.apply(key), count.intValue(),
                link(param, key, others), key.equalsIgnoreCase(nz(active)))));
        out.append("</div></div>");
    }

    private static String chip(String label, int count, String href, boolean on) {
        return "<a class=\"filter%s\" href=\"%s\">%s<span class=\"filter-n\">%d</span></a>"
                .formatted(on ? " is-on" : "", href, Ui.esc(label), count);
    }

    /** Every filter keeps the others, so narrowing is cumulative rather than a reset. */
    private static String link(String param, String value, Map<String, String> others) {
        StringBuilder url = new StringBuilder("/jobs");
        char join = '?';
        for (Map.Entry<String, String> other : new java.util.TreeMap<>(others).entrySet()) {
            String kept = other.getKey().equals(param) ? value : other.getValue();
            if (kept != null && !kept.isBlank()) {
                url.append(join).append(other.getKey()).append('=')
                        .append(java.net.URLEncoder.encode(kept,
                                java.nio.charset.StandardCharsets.UTF_8));
                join = '&';
            }
        }
        return url.toString();
    }

    private static Predicate<Row> laneFilter(String lane) {
        return lane == null || lane.isBlank() ? row -> true
                : row -> row.posting().getStrategicClass() != null
                        && row.posting().getStrategicClass().name().equalsIgnoreCase(lane);
    }

    private static Predicate<Row> countryFilter(String country) {
        return country == null || country.isBlank() ? row -> true
                : row -> country.equalsIgnoreCase(row.posting().getCountryCode());
    }

    private static Predicate<Row> modeFilter(String mode) {
        return mode == null || mode.isBlank() ? row -> true
                : row -> row.posting().getWorkMode() != null
                        && row.posting().getWorkMode().name().equalsIgnoreCase(mode);
    }

    private static Predicate<Row> freshFilter(String fresh, LocalDate today) {
        if (fresh == null || fresh.isBlank()) {
            return row -> true;
        }
        int days = "month".equalsIgnoreCase(fresh) ? 30 : FRESH_DAYS;
        return row -> isFresh(row.posting(), today, days);
    }

    private static Predicate<Row> statusFilter(String status) {
        if (status == null || status.isBlank()) {
            return row -> true;
        }
        return switch (status.toLowerCase(Locale.ROOT)) {
            case "new" -> row -> row.applied() == null;
            case "started" -> row -> row.applied() != null;
            case "remote-india" -> row -> row.posting().allowsRemoteFromIndia();
            default -> row -> true;
        };
    }

    // ------------------------------------------------------------------
    // The list
    // ------------------------------------------------------------------

    private String list(List<Row> rows, int page, String lane, String country, String mode,
            String fresh, String status) {

        if (rows.isEmpty()) {
            return Parts.blank("Nothing matches those filters.",
                    "Every recommended posting was excluded by the chips above. Clearing "
                            + "one of them will bring some back.",
                    Parts.linkButton("/jobs", "Clear all filters", "btn btn-primary"));
        }
        int from = Math.max(0, page) * PAGE;
        if (from >= rows.size()) {
            from = 0;
        }
        int to = Math.min(rows.size(), from + PAGE);

        StringBuilder cards = new StringBuilder("<div class=\"joblist\">");
        rows.subList(from, to).forEach(row -> cards.append(JobCard.render(
                row.posting(), row.score(), companies.of(row.posting()),
                salary.forPosting(row.posting()), row.applied())));
        cards.append("</div>");

        if (rows.size() > PAGE) {
            Map<String, String> params = Map.of("lane", nz(lane), "country", nz(country),
                    "mode", nz(mode), "fresh", nz(fresh), "status", nz(status));
            cards.append("<div class=\"pager\"><span>Showing ").append(from + 1)
                    .append("–").append(to).append(" of ").append(rows.size())
                    .append("</span><span class=\"pager-links\">");
            if (from > 0) {
                cards.append(Parts.linkButton(page(params, page - 1), "Previous", "btn btn-sm"));
            }
            if (to < rows.size()) {
                cards.append(Parts.linkButton(page(params, page + 1), "Next", "btn btn-sm"));
            }
            cards.append("</span></div>");
        }
        return cards.toString();
    }

    private static String page(Map<String, String> params, int page) {
        String base = link("__none__", null, params);
        return base + (base.contains("?") ? "&" : "?") + "page=" + page;
    }

    // ------------------------------------------------------------------

    /**
     * The newest attempt per posting, so a card can say what already happened.
     *
     * <p>Newest wins: an earlier attempt is history. Showing "Prepare" on
     * something already prepared is an offer to do the work twice, and on
     * something already sent it is considerably worse.
     */
    private Map<Long, ApplicationAttempt> newestAttempts() {
        Map<Long, ApplicationAttempt> newest = new LinkedHashMap<>();
        for (ApplicationAttempt attempt : attempts.findTop30ByOrderByStartedAtDesc()) {
            newest.putIfAbsent(attempt.getPostingId(), attempt);
        }
        return newest;
    }

    private static JobCard.Applied applied(ApplicationAttempt attempt) {
        return attempt == null ? null
                : new JobCard.Applied(attempt.getId(), attempt.getStatus());
    }

    private static Map<String, Long> count(List<Row> rows,
            java.util.function.Function<Row, String> key) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Row row : rows) {
            String value = key.apply(row);
            if (value != null && !value.isBlank()) {
                counts.merge(value, 1L, Long::sum);
            }
        }
        List<Map.Entry<String, Long>> sorted = new ArrayList<>(counts.entrySet());
        sorted.sort(Map.Entry.<String, Long>comparingByValue().reversed());
        Map<String, Long> ordered = new LinkedHashMap<>();
        sorted.forEach(entry -> ordered.put(entry.getKey(), entry.getValue()));
        return ordered;
    }

    /** Countries have a long tail; the chips show the ones with enough in them. */
    private static Map<String, Long> top(Map<String, Long> counts, int limit) {
        Map<String, Long> kept = new LinkedHashMap<>();
        counts.entrySet().stream().limit(limit)
                .forEach(entry -> kept.put(entry.getKey(), entry.getValue()));
        return kept;
    }

    /**
     * An undated posting is never fresh.
     *
     * <p>Roughly a third of the corpus carries no posted date, and treating
     * absence as "today" would fill the one filter that is supposed to be short.
     */
    private static boolean isFresh(Posting posting, LocalDate today, int days) {
        return posting.getPostedDate() != null
                && ChronoUnit.DAYS.between(posting.getPostedDate(), today) <= days;
    }

    private static String readable(String name) {
        return name.toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    private static String nz(String value) {
        return value == null ? "" : value;
    }
}
