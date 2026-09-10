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
            new BigDecimal("700000"), new BigDecimal("1400000"),
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

    private static Digest digest(List<Posting> newCandidates, List<Posting> review,
            Map<String, Long> rejections, boolean reverify) {
        BoardToken board = new BoardToken(Source.GREENHOUSE, "stripe", "Stripe");
        board.recordSuccess(615, Instant.now());
        return new Digest(TODAY, newCandidates, review, List.of(), List.of(),
                rejections, List.of(board), reverify, 0, 0);
    }

    @Test
    @DisplayName("a quiet day says so instead of padding")
    void quietDayIsExplicit() {
        String out = writer.render(digest(List.of(), List.of(), Map.of(), false));

        assertThat(out).contains("# job-radar — 2026-09-06");
        assertThat(out).contains("Nothing new today.");
        // A digest that pads a quiet day with near-misses trains you to stop
        // reading it.
        assertThat(out).doesNotContain("## New candidates");
    }

    @Test
    @DisplayName("a candidate is rendered with company, years, signal, floor and link")
    void rendersCandidate() {
        String out = writer.render(digest(
                List.of(candidate("stripe", "Software Engineer, New Grad", "Dublin",
                        Country.IRELAND, 0, true)),
                List.of(), Map.of(), false));

        assertThat(out).contains("## New candidates (1)");
        // The board token is "stripe"; the digest should show the label.
        assertThat(out).contains("**Stripe** — Software Engineer, New Grad — Dublin");
        assertThat(out).contains("Years: 0 (entry level)");
        assertThat(out).contains("Graduate signal: yes");
        assertThat(out).contains("EUR 40.904").contains("CSEP");
        assertThat(out).contains("https://example.com/1");
    }

    @Test
    @DisplayName("rupee amounts use lakh grouping")
    void formatsIndianNumbers() {
        // "Rs 700,000" invites a misread as seven million. Neither the en-IN
        // locale nor a DecimalFormat pattern produces this grouping on the JDK,
        // so it is written by hand and pinned here.
        assertThat(SalaryFloorAdvisor.indianGrouping(new BigDecimal("700000")))
                .isEqualTo("7,00,000");
        assertThat(SalaryFloorAdvisor.indianGrouping(new BigDecimal("1400000")))
                .isEqualTo("14,00,000");
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
                List.of(), Map.of(), false));
        String bangalore = writer.render(digest(List.of(
                candidate("slice", "Backend Engineer", "Bengaluru", Country.INDIA, 1, false)),
                List.of(), Map.of(), false));

        assertThat(mumbai).contains("Rs 7,00,000").contains("no rent");
        assertThat(bangalore).contains("Rs 14,00,000").contains("relocation");
    }

    @Test
    @DisplayName("no-years candidates go to review, not into the candidate list")
    void reviewSectionIsSeparate() {
        String out = writer.render(digest(List.of(),
                List.of(candidate("adyen", "Java Software Engineer", "Amsterdam",
                        Country.NETHERLANDS, -1, false)),
                Map.of(), false));

        assertThat(out).contains("## Needs human review — no years stated (1)");
        assertThat(out).contains("Years: none stated");
        assertThat(out).doesNotContain("## New candidates");
    }

    @Test
    @DisplayName("rejections are collapsed to counts by reason")
    void collapsesRejections() {
        String out = writer.render(digest(List.of(), List.of(),
                new java.util.LinkedHashMap<>(Map.of("country-locked remote", 585L)), false));

        assertThat(out).contains("## Rejected (585)");
        assertThat(out).contains("585 — country-locked remote");
    }

    @Test
    @DisplayName("healthy boards are summarised, not listed one by one")
    void summarisesHealthyBoards() {
        String out = writer.render(digest(List.of(), List.of(), Map.of(), false));
        assertThat(out).contains("All 1 boards healthy — 615 postings.");
    }

    @Test
    @DisplayName("a failing board is named, with its last known good count")
    void namesFailingBoards() {
        BoardToken broken = new BoardToken(Source.GREENHOUSE, "celonis", "Celonis");
        broken.recordSuccess(274, Instant.now());
        broken.recordFailure("HTTP 404", Instant.now());

        String out = writer.render(new Digest(TODAY, List.of(), List.of(), List.of(), List.of(),
                Map.of(), List.of(broken), false, 0, 0));

        // "was 274 postings, now failing" is the useful statement. "0 postings"
        // would read as a company that stopped hiring.
        assertThat(out).contains("**celonis**").contains("was 274 postings")
                .contains("HTTP 404");
    }

    @Test
    @DisplayName("a board returning nothing is named, because it may simply be dead")
    void namesEmptyBoards() {
        // SmartRecruiters answers HTTP 200 with totalFound 0 for a company it has
        // never heard of, so a dead token looks exactly like a company with no
        // openings. Eight of the fourteen seeded tokens are in this state.
        BoardToken empty = new BoardToken(Source.SMARTRECRUITERS, "Personio", "Personio");
        empty.recordSuccess(0, Instant.now());

        String out = writer.render(new Digest(TODAY, List.of(), List.of(), List.of(), List.of(),
                Map.of(), List.of(empty), false, 0, 0));

        assertThat(out).contains("returned nothing").contains("SMARTRECRUITERS/Personio");
        assertThat(out).doesNotContain("boards healthy");
    }

    @Test
    @DisplayName("suppressed candidates are counted, not silently dropped")
    void countsSuppressedCandidates() {
        // A digest that quietly shrinks is one you stop trusting.
        String out = writer.render(new Digest(TODAY, List.of(), List.of(), List.of(), List.of(),
                Map.of(), List.of(), false, 4, 0));

        assertThat(out).contains("4 candidate(s) hidden — already applied");
    }

    @Test
    @DisplayName("stale salary floors raise a warning in the digest")
    void warnsWhenFloorsNeedReverification() {
        String out = writer.render(digest(List.of(), List.of(), Map.of(), true));
        assertThat(out).contains("Salary floors need re-verification");
    }

    @Test
    @DisplayName("folded repeat listings are reported, not silently dropped")
    void reportsCollapsedDuplicates() {
        String out = writer.render(new Digest(TODAY, List.of(), List.of(), List.of(), List.of(),
                Map.of(), List.of(), false, 0, 6));

        assertThat(out).contains("6 repeat listing(s) folded");
    }
}
