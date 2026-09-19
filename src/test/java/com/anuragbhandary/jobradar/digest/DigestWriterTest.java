package com.anuragbhandary.jobradar.digest;

import static org.assertj.core.api.Assertions.assertThat;

import com.anuragbhandary.jobradar.config.AppProperties;
import com.anuragbhandary.jobradar.domain.BoardToken;
import com.anuragbhandary.jobradar.domain.Country;
import com.anuragbhandary.jobradar.domain.Posting;
import com.anuragbhandary.jobradar.domain.Source;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DigestWriterTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 6);

    private static final AppProperties.SalaryFloors FLOORS = new AppProperties.SalaryFloors(
            new BigDecimal("700000"), new BigDecimal("800000"),
            new BigDecimal("45934.20"), new BigDecimal("40904"),
            new BigDecimal("52284"), new BigDecimal("48000"),
            LocalDate.of(2027, 3, 1));

    private final DigestWriter writer = new DigestWriter(
            new AppProperties(null, "./target/test-digests", null, null, null, FLOORS),
            new SalaryFloorAdvisor(
                    new AppProperties(null, null, null, null,
                            com.anuragbhandary.jobradar.filter.RealConfigAccess.screening(),
                            FLOORS),
                    com.anuragbhandary.jobradar.filter.RealConfigAccess.countryStrategy(),
                    new com.anuragbhandary.jobradar.filter.GeoFilter(
                            new AppProperties(null, null, null, null,
                                    com.anuragbhandary.jobradar.filter.RealConfigAccess.screening(),
                                    null))));

    private static Posting candidate(String token, String title, String location,
            Country country, Integer minYears, boolean graduate) {
        Posting p = new Posting(Source.GREENHOUSE, token, "1", title);
        p.setLocation(location);
        p.setCountry(country);
        p.setMinYears(minYears);
        p.setGraduateSignal(graduate);
        p.setUrl("https://example.com/1");
        p.setFirstSeen(Instant.now());
        p.setLastSeen(Instant.now());
        return p;
    }

    private static Digest digest(List<Posting> candidates,
            Map<String, Long> rejections, boolean reverify) {
        BoardToken board = new BoardToken(Source.GREENHOUSE, "stripe", "Stripe");
        board.recordSuccess(615, Instant.now());
        return new Digest(TODAY, null,
                candidates.stream().map(p -> new Digest.Entry(p, false, false)).toList(),
                List.of(), rejections, List.of(board), reverify, 0, 0, 0);
    }

    private static Digest withCounts(List<BoardToken> boards, int decided, int folded,
            int stale) {
        return new Digest(TODAY, null, List.of(), List.of(), Map.of(), boards, false,
                decided, folded, stale);
    }

    @Test
    @DisplayName("a quiet day says so instead of padding")
    void quietDayIsExplicit() {
        String out = writer.render(digest(List.of(), Map.of(), false));

        assertThat(out).contains("# job-radar handoff — 2026-09-06");
        assertThat(out).contains("Nothing new to review.");
        assertThat(out).doesNotContain("## Candidates");
    }

    @Test
    @DisplayName("a candidate carries its facts, floor, link and description")
    void rendersCandidate() {
        Posting p = candidate("stripe", "Software Engineer, New Grad", "Dublin",
                Country.IRELAND, 0, true);
        p.setCountryCode("IE");
        p.setDescriptionText("Build payments APIs in Java.");
        String out = writer.render(digest(List.of(p), Map.of(), false));

        assertThat(out).contains("## Candidates (1)");
        // The board token is "stripe"; the file shows the label.
        assertThat(out).contains("· Stripe · Software Engineer, New Grad");
        assertThat(out).contains("Dublin · IE");
        assertThat(out).contains("Years: 0 (entry level)");
        assertThat(out).contains("graduate signal: yes");
        assertThat(out).contains("EUR 40.904").contains("CSEP");
        assertThat(out).contains("https://example.com/1");
        assertThat(out).contains("Build payments APIs in Java.");
    }

    @Test
    @DisplayName("nothing is ranked or scored")
    void noRanking() {
        String out = writer.render(digest(List.of(candidate("stripe", "Backend Engineer",
                "Dublin", Country.IRELAND, 1, false)), Map.of(), false));
        assertThat(out).doesNotContain("Start here").doesNotContain("/100");
    }

    @Test
    @DisplayName("a changed description is flagged on the entry")
    void flagsUpdated() {
        Posting p = candidate("stripe", "Backend Engineer", "Dublin", Country.IRELAND, 1, false);
        String out = writer.render(new Digest(TODAY, null, List.of(new Digest.Entry(p, true, false)),
                List.of(), Map.of(), List.of(), false, 0, 0, 0));
        assertThat(out).contains("_(description changed)_");
    }

    @Test
    @DisplayName("an export names its window")
    void exportNamesWindow() {
        String out = writer.render(new Digest(TODAY, LocalDate.of(2026, 9, 1), List.of(),
                List.of(), Map.of(), List.of(), false, 0, 0, 0));
        assertThat(out).contains("(everything open since 2026-09-01)");
    }

    @Test
    @DisplayName("rupee amounts use lakh grouping")
    void formatsIndianNumbers() {
        // "Rs 700,000" invites a misread as seven million. Neither the en-IN
        // locale nor a DecimalFormat pattern produces this grouping on the JDK,
        // so it is written by hand and pinned here.
        assertThat(SalaryFloorAdvisor.indianGrouping(new BigDecimal("700000")))
                .isEqualTo("7,00,000");
        assertThat(SalaryFloorAdvisor.indianGrouping(new BigDecimal("800000")))
                .isEqualTo("8,00,000");
        assertThat(SalaryFloorAdvisor.indianGrouping(new BigDecimal("45000")))
                .isEqualTo("45,000");
        assertThat(SalaryFloorAdvisor.indianGrouping(new BigDecimal("5000")))
                .isEqualTo("5,000");
        assertThat(SalaryFloorAdvisor.indianGrouping(new BigDecimal("999")))
                .isEqualTo("999");
        assertThat(SalaryFloorAdvisor.indianGrouping(new BigDecimal("12500000")))
                .isEqualTo("1,25,00,000");
    }

    @Test
    @DisplayName("the Mumbai floor is lower than the rest-of-India floor, and says why")
    void explainsIndianFloors() {
        String mumbai = writer.render(digest(List.of(
                candidate("slice", "Backend Engineer", "Mumbai", Country.INDIA, 1, false)),
                Map.of(), false));
        String bangalore = writer.render(digest(List.of(
                candidate("slice", "Backend Engineer", "Bengaluru", Country.INDIA, 1, false)),
                Map.of(), false));

        assertThat(mumbai).contains("Rs 7,00,000").contains("no rent");
        assertThat(bangalore).contains("Rs 8,00,000").contains("relocation");
    }

    @Test
    @DisplayName("no years stated is said on the entry, not hidden in another section")
    void noYearsStated() {
        String out = writer.render(digest(List.of(candidate("adyen", "Java Software Engineer",
                "Amsterdam", Country.NETHERLANDS, -1, false)), Map.of(), false));
        assertThat(out).contains("## Candidates (1)").contains("Years: none stated");
    }

    @Test
    @DisplayName("rejections are collapsed to counts by reason")
    void collapsesRejections() {
        String out = writer.render(digest(List.of(),
                new java.util.LinkedHashMap<>(Map.of("country-locked remote", 585L)), false));

        assertThat(out).contains("## Rejected on facts (585)");
        assertThat(out).contains("585 — country-locked remote");
    }

    @Test
    @DisplayName("the header says when the data was fetched")
    void saysWhenFetched() {
        String out = writer.render(digest(List.of(), Map.of(), false));
        assertThat(out).contains("- Last fetch: ").contains("- Boards: 1 active, 615 postings");
    }

    @Test
    @DisplayName("a failing board is named, with its error")
    void namesFailingBoards() {
        BoardToken broken = new BoardToken(Source.GREENHOUSE, "celonis", "Celonis");
        broken.recordSuccess(274, Instant.now());
        broken.recordFailure("HTTP 404", Instant.now());

        String out = writer.render(withCounts(List.of(broken), 0, 0, 0));
        assertThat(out).contains("failing: GREENHOUSE/celonis (HTTP 404)");
    }

    @Test
    @DisplayName("a board returning nothing is named, because it may simply be dead")
    void namesEmptyBoards() {
        // SmartRecruiters answers HTTP 200 with totalFound 0 for a company it has
        // never heard of, so a dead token looks exactly like a company with no
        // openings.
        BoardToken empty = new BoardToken(Source.SMARTRECRUITERS, "Personio", "Personio");
        empty.recordSuccess(0, Instant.now());

        String out = writer.render(withCounts(List.of(empty), 0, 0, 0));
        assertThat(out).contains("returned nothing").contains("SMARTRECRUITERS/Personio");
    }

    @Test
    @DisplayName("everything withheld is counted, not silently dropped")
    void countsWithheld() {
        // A file that quietly shrinks is one you stop trusting.
        String out = writer.render(withCounts(List.of(), 3, 6, 7));
        assertThat(out).contains("- Withheld: 3 already marked, 6 repeat listings folded, 7 stale");
    }

    @Test
    @DisplayName("a company already applied to is flagged, not hidden")
    void flagsCompanyApplied() {
        Posting p = candidate("amazon", "SDE, S3", "Berlin", Country.GERMANY, 1, false);
        String out = writer.render(new Digest(TODAY, null, List.of(new Digest.Entry(p, false, true)),
                List.of(), Map.of(), List.of(), false, 0, 0, 0));
        assertThat(out).contains("SDE, S3").contains("**Already applied to this company**");
    }

    @Test
    @DisplayName("stale salary floors raise a warning")
    void warnsWhenFloorsNeedReverification() {
        String out = writer.render(digest(List.of(), Map.of(), true));
        assertThat(out).contains("Salary floors need re-verification");
    }
}
