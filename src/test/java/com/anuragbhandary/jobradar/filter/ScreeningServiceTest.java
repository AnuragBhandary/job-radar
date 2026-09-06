package com.anuragbhandary.jobradar.filter;

import static org.assertj.core.api.Assertions.assertThat;

import com.anuragbhandary.jobradar.config.AppProperties;
import com.anuragbhandary.jobradar.domain.Country;
import com.anuragbhandary.jobradar.domain.Posting;
import com.anuragbhandary.jobradar.domain.Source;
import com.anuragbhandary.jobradar.domain.Verdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Exercises the ordering of the filters and what ends up on the posting. */
class ScreeningServiceTest {

    private final AppProperties properties = RealConfig.withScreening();
    private final ScreeningService screening = new ScreeningService(
            null,
            new GeoFilter(properties),
            new TitleFilter(properties),
            new YearsExtractor(),
            new SignalExtractor(),
            properties);

    private Posting posting(String title, String location, String description) {
        Posting p = new Posting(Source.GREENHOUSE, "test", "1", title);
        p.setLocation(location);
        p.setDescriptionText(description);
        screening.screen(p);
        return p;
    }

    @Test
    @DisplayName("an eligible graduate role in Dublin is a candidate")
    void acceptsEligibleRole() {
        Posting p = posting("Software Engineer, New Grad", "Dublin, Ireland",
                "You have 0-2 years of experience and graduated within the last 24 months.");

        assertThat(p.getVerdict()).isEqualTo(Verdict.CANDIDATE);
        assertThat(p.getCountry()).isEqualTo(Country.IRELAND);
        assertThat(p.getMinYears()).isZero();
        assertThat(p.isGraduateSignal()).isTrue();
        assertThat(p.getRejectReason()).isNull();
    }

    @Test
    @DisplayName("geography is checked before title and years")
    void geographyRejectsFirst() {
        // A senior role in Argentina could be rejected three ways. The geography
        // is the useful reason, because no amount of experience would help.
        Posting p = posting("Senior Software Engineer", "Remote (Argentina)",
                "8+ years of experience required.");

        assertThat(p.getVerdict()).isEqualTo(Verdict.REJECTED);
        assertThat(p.getRejectReason()).contains("country-locked remote");
        // Years are never extracted for a posting rejected earlier.
        assertThat(p.getMinYears()).isNull();
    }

    @Test
    @DisplayName("title is checked before years")
    void titleRejectsBeforeYears() {
        Posting p = posting("Senior Software Engineer", "Bengaluru, India",
                "1-2 years of experience.");

        assertThat(p.getRejectReason()).contains("title excluded on 'senior'");
        assertThat(p.getMinYears()).isNull();
    }

    @Test
    @DisplayName("too many years is rejected with the phrase that disqualified it")
    void rejectsOnYearsWithEvidence() {
        Posting p = posting("Software Engineer", "Berlin",
                "Requirements: 5+ years of experience building distributed systems.");

        assertThat(p.getVerdict()).isEqualTo(Verdict.REJECTED);
        assertThat(p.getMinYears()).isEqualTo(5);
        assertThat(p.getRejectReason()).contains("5 years required").contains("5+ years");
    }

    @Test
    @DisplayName("Amazon's non-internship wording disqualifies on its own")
    void rejectsNonInternshipWording() {
        Posting p = posting("Software Development Engineer", "Dublin, Ireland",
                "1+ years of non-internship professional software development experience.");

        assertThat(p.getVerdict()).isEqualTo(Verdict.REJECTED);
        assertThat(p.getRejectReason()).contains("non-internship");
    }

    @Test
    @DisplayName("no stated years is a candidate, but flagged for a human")
    void noYearsStatedNeedsReview() {
        // Absence of a number is not evidence of an entry-level role. Several
        // no-years postings read as plainly mid-level from the prose alone.
        Posting p = posting("Backend Engineer", "Amsterdam",
                "You will build resilient services with deep production experience.");

        assertThat(p.getVerdict()).isEqualTo(Verdict.CANDIDATE);
        assertThat(p.getMinYears()).isEqualTo(YearsExtraction.NONE_STATED);
        assertThat(ScreeningService.needsHumanReview(p)).isTrue();
    }

    @Test
    @DisplayName("a candidate with stated years does not need review")
    void statedYearsDoesNotNeedReview() {
        Posting p = posting("Backend Engineer", "Mumbai",
                "1-3 years of hands-on software engineering experience.");

        assertThat(p.getVerdict()).isEqualTo(Verdict.CANDIDATE);
        assertThat(ScreeningService.needsHumanReview(p)).isFalse();
    }

    @Test
    @DisplayName("re-screening clears the previous verdict rather than layering on it")
    void rescreeningResets() {
        // The rules live in application.yml so they can be edited. A posting
        // rejected under the old rules must be able to become a candidate.
        Posting p = posting("Senior Software Engineer", "Berlin", "5+ years.");
        assertThat(p.getVerdict()).isEqualTo(Verdict.REJECTED);

        p.setTitle("Graduate Software Engineer");
        p.setDescriptionText("0-1 years of experience.");
        screening.screen(p);

        assertThat(p.getVerdict()).isEqualTo(Verdict.CANDIDATE);
        assertThat(p.getRejectReason()).isNull();
        assertThat(p.getMinYears()).isZero();
    }
}
