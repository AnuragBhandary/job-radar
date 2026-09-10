package com.anuragbhandary.jobradar.filter;

import static org.assertj.core.api.Assertions.assertThat;

import com.anuragbhandary.jobradar.config.AppProperties;
import com.anuragbhandary.jobradar.domain.Country;
import com.anuragbhandary.jobradar.domain.Posting;
import com.anuragbhandary.jobradar.domain.Source;
import com.anuragbhandary.jobradar.domain.StrategicClass;
import com.anuragbhandary.jobradar.domain.Verdict;
import com.anuragbhandary.jobradar.domain.WorkMode;
import com.anuragbhandary.jobradar.strategy.StrategyOutcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Exercises the ordering of the filters and what ends up on the posting. */
class ScreeningServiceTest {

    private final AppProperties properties = RealConfig.withScreening();
    private final ScreeningService screening = new ScreeningService(
            null,
            null,
            RealConfig.locations(),
            new StrategicClassifier(RealConfig.countryStrategy()),
            RealConfig.countryStrategy(),
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

    // ------------------------------------------------------------------
    // Eligibility is not priority
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a US relocation role is kept, classified, and simply not recommended")
    void excludedStrategyDoesNotDeleteThePosting() {
        // The rule the whole phase turns on. Before it, this posting was
        // "REJECTED - outside target geographies" with no country recorded, and
        // the only way to get it back was to edit a list of place names.
        Posting p = posting("Software Engineer", "New York, NY",
                "0-2 years of experience.");

        assertThat(p.getVerdict()).isEqualTo(Verdict.CANDIDATE);
        assertThat(p.getRejectReason()).isNull();
        assertThat(p.getCountryCode()).isEqualTo("US");
        assertThat(p.getStrategicClass()).isEqualTo(StrategicClass.INTERNATIONAL_RELOCATION);
        assertThat(p.getStrategyOutcome()).isEqualTo(StrategyOutcome.EXCLUDED);
    }

    @Test
    @DisplayName("a newly admitted country is eligible and recommended")
    void secondaryTierIsRecommended() {
        // Sydney used to be rejected on the strength of appearing in
        // excluded-locations. It is now a secondary-tier relocation.
        Posting p = posting("Backend Engineer", "Sydney, Australia",
                "0-2 years of experience.");

        assertThat(p.getVerdict()).isEqualTo(Verdict.CANDIDATE);
        assertThat(p.getCountryCode()).isEqualTo("AU");
        assertThat(p.getStrategyOutcome()).isEqualTo(StrategyOutcome.RECOMMENDED);
    }

    @Test
    @DisplayName("an opportunistic country is eligible but stays out of the default feed")
    void opportunisticTierIsConsidered() {
        Posting p = posting("Backend Engineer", "Toronto, Canada",
                "0-2 years of experience.");

        assertThat(p.getVerdict()).isEqualTo(Verdict.CANDIDATE);
        assertThat(p.getStrategyOutcome()).isEqualTo(StrategyOutcome.CONSIDER);
    }

    @Test
    @DisplayName("remote locked to a country he cannot be in is still a rejection")
    void unreachableRemoteIsStillIneligible() {
        // The one geography rule that still rejects, and the expensive mistake
        // the original filter existed to prevent. This is eligibility, not
        // preference: no amount of strategy makes the job holdable.
        Posting p = posting("Software Engineer", "Remote - United States only",
                "0-2 years of experience.");

        assertThat(p.getVerdict()).isEqualTo(Verdict.REJECTED);
        assertThat(p.getRejectReason()).contains("country-locked remote");
        // Classified anyway: a rejection is a statement about eligibility, not a
        // reason to know nothing about the row.
        assertThat(p.getWorkMode()).isEqualTo(WorkMode.REMOTE_COUNTRY_LOCKED);
        assertThat(p.getRemoteEligibleFrom()).isEqualTo("US");
    }

    @Test
    @DisplayName("classification is written even when the title rejects the posting")
    void classificationSurvivesRejection() {
        Posting p = posting("Senior Software Engineer", "Berlin", "1-2 years of experience.");

        assertThat(p.getVerdict()).isEqualTo(Verdict.REJECTED);
        assertThat(p.getCountryCode()).isEqualTo("DE");
        assertThat(p.getWorkMode()).isEqualTo(WorkMode.ONSITE);
        assertThat(p.getStrategicClass()).isEqualTo(StrategicClass.INTERNATIONAL_RELOCATION);
    }

    @Test
    @DisplayName("the legacy country column still says what the answer path expects")
    void legacyCountryIsStillWritten() {
        // FieldMapper's sponsorship and authorisation derivations read this. If
        // it stops being populated correctly, the wrong answer reaches a form.
        assertThat(posting("Backend Engineer", "Mumbai", "1 year.").getCountry())
                .isEqualTo(Country.INDIA);
        assertThat(posting("Backend Engineer", "Berlin", "1 year.").getCountry())
                .isEqualTo(Country.GERMANY);
        assertThat(posting("Backend Engineer", "Remote", "1 year.").getCountry())
                .isEqualTo(Country.REMOTE);
        assertThat(posting("Backend Engineer", "Toronto", "1 year.").getCountry())
                .isEqualTo(Country.OTHER);
    }
}
