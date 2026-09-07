package com.anuragbhandary.jobradar.match;

import static org.assertj.core.api.Assertions.assertThat;

import com.anuragbhandary.jobradar.apply.resume.ResumeModel;
import com.anuragbhandary.jobradar.domain.Country;
import com.anuragbhandary.jobradar.domain.Posting;
import com.anuragbhandary.jobradar.domain.Source;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MatchScorerTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 7);

    private static final ResumeModel RESUME = new ResumeModel(
            "Backend Engineer",
            List.of(new ResumeModel.Summary("default", List.of(), "Summary.")),
            List.of(new ResumeModel.SkillGroup("Backend",
                    List.of("Java", "Spring Boot", "Kafka", "PostgreSQL", "Docker", "Python"))),
            List.of(), List.of(), List.of(), List.of(), 2, 4, 3);

    private static final MatchProperties CONFIG = new MatchProperties(
            Map.of(Country.REMOTE, 100, Country.INDIA, 70, Country.GERMANY, 55), 2, 7, 45);

    private final MatchScorer scorer = new MatchScorer(RESUME, CONFIG);

    private static Posting posting(String title, String description) {
        Posting posting = new Posting(Source.GREENHOUSE, "acme", "1", title);
        posting.setDescriptionText(description);
        posting.setCountry(Country.REMOTE);
        posting.setPostedDate(TODAY.minusDays(2));
        return posting;
    }

    @Test
    @DisplayName("a posting made of his own stack scores strongly")
    void perfectStackScoresHigh() {
        Posting p = posting("Backend Engineer",
                "Java, Spring Boot, Kafka and PostgreSQL on Docker.");
        p.setMinYears(2);

        MatchScore score = scorer.score(p, TODAY);

        assertThat(score.score()).isGreaterThanOrEqualTo(75);
        assertThat(score.band()).isEqualTo(MatchScore.Band.STRONG);
    }

    @Test
    @DisplayName("skills score on the fraction covered, not the count matched")
    void longAdvertsDoNotWinByBeingLong() {
        // Counting matches rewards the advert for being verbose, which is a
        // property of the advert and not of the fit.
        MatchScore narrow = scorer.score(posting("Engineer", "Java and Kafka."), TODAY);
        MatchScore wide = scorer.score(posting("Engineer",
                "Java, Kafka, Rust, Elixir, Terraform, Kubernetes, GraphQL, Cassandra."), TODAY);

        assertThat(factor(narrow, "Skills").points())
                .isGreaterThan(factor(wide, "Skills").points());
    }

    @Test
    @DisplayName("years over what he can evidence fall away fast")
    void experienceFallsAwayWithYears() {
        assertThat(experiencePoints(2)).isEqualTo(25);
        assertThat(experiencePoints(4)).isLessThan(experiencePoints(2));
        assertThat(experiencePoints(8)).isZero();
    }

    @Test
    @DisplayName("a posting stating no years is unknown, not entry level")
    void silenceOnYearsIsNotAnInvitation() {
        // Treating silence as "entry level welcome" is the exact mistake the
        // years extractor exists to prevent.
        Posting p = posting("Engineer", "Java.");
        p.setMinYears(-1);

        assertThat(factor(scorer.score(p, TODAY), "Experience").points())
                .isLessThan(25)
                .isGreaterThan(0);
    }

    @Test
    @DisplayName("geography follows the configured weights")
    void geographyIsWeighted() {
        Posting remote = posting("Engineer", "Java.");
        Posting germany = posting("Engineer", "Java.");
        germany.setCountry(Country.GERMANY);

        assertThat(factor(scorer.score(remote, TODAY), "Location").points())
                .isGreaterThan(factor(scorer.score(germany, TODAY), "Location").points());
    }

    @Test
    @DisplayName("an old requisition loses its freshness points")
    void ageCosts() {
        Posting fresh = posting("Engineer", "Java.");
        Posting old = posting("Engineer", "Java.");
        old.setPostedDate(TODAY.minusDays(120));

        assertThat(factor(scorer.score(fresh, TODAY), "Freshness").points()).isEqualTo(10);
        assertThat(factor(scorer.score(old, TODAY), "Freshness").points()).isZero();
    }

    @Test
    @DisplayName("no date is middling, so silent boards cannot sweep the top")
    void missingDateDoesNotScoreAsFresh() {
        Posting p = posting("Engineer", "Java.");
        p.setPostedDate(null);

        assertThat(factor(scorer.score(p, TODAY), "Freshness").points()).isEqualTo(5);
    }

    @Test
    @DisplayName("every factor explains itself")
    void factorsCarryTheirEvidence() {
        Posting p = posting("Engineer", "Java, Kafka and Rust.");
        p.setMinYears(6);

        MatchScore score = scorer.score(p, TODAY);

        assertThat(score.factors()).hasSize(5)
                .allSatisfy(f -> assertThat(f.detail()).isNotBlank());
        assertThat(factor(score, "Skills").detail()).contains("rust");
        assertThat(factor(score, "Experience").detail()).contains("6+ years");
        // The headline is the factor losing the most points: what to fix first.
        assertThat(score.headline()).isNotBlank();
    }

    @Test
    void bandsSplitTheRange() {
        assertThat(new MatchScore(80, List.of()).band()).isEqualTo(MatchScore.Band.STRONG);
        assertThat(new MatchScore(60, List.of()).band()).isEqualTo(MatchScore.Band.GOOD);
        assertThat(new MatchScore(40, List.of()).band()).isEqualTo(MatchScore.Band.FAIR);
        assertThat(new MatchScore(10, List.of()).band()).isEqualTo(MatchScore.Band.WEAK);
    }

    private int experiencePoints(int years) {
        Posting p = posting("Engineer", "Java.");
        p.setMinYears(years);
        return factor(scorer.score(p, TODAY), "Experience").points();
    }

    private static MatchScore.Factor factor(MatchScore score, String label) {
        return score.factors().stream()
                .filter(f -> f.label().equals(label)).findFirst().orElseThrow();
    }
}
