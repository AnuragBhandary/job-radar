package com.anuragbhandary.jobradar.digest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.anuragbhandary.jobradar.domain.Posting;
import com.anuragbhandary.jobradar.domain.StrategicClass;
import com.anuragbhandary.jobradar.filter.RealConfigAccess;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FitScoreTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 1);

    private final FitScore fit = new FitScore(
            new FitScore.Properties(List.of("python", "kafka", "fastapi", "sql", "go", "postgresql"), 60, 40),
            RealConfigAccess.countryStrategy());

    private static Posting posting(String title, String description, Integer years,
            StrategicClass lane, String country, LocalDate posted) {
        Posting p = mock(Posting.class);
        when(p.getTitle()).thenReturn(title);
        when(p.getDescriptionText()).thenReturn(description);
        when(p.getMinYears()).thenReturn(years);
        when(p.getStrategicClass()).thenReturn(lane);
        when(p.getCountryCode()).thenReturn(country);
        when(p.getPostedDate()).thenReturn(posted);
        return p;
    }

    @Test
    @DisplayName("a fresh entry-level backend role on his stack in India outranks a stale relocation role off it")
    void ordersByFit() {
        int good = fit.fit(posting("Backend Engineer", "Python, FastAPI, Kafka and PostgreSQL.", 0,
                StrategicClass.INDIA_HOME, "IN", TODAY.minusDays(1)), TODAY).score();
        int poor = fit.fit(posting("Software Engineer", "Rust and C++ on embedded Linux.", 2,
                StrategicClass.INTERNATIONAL_RELOCATION, "DE", TODAY.minusDays(30)), TODAY).score();

        assertThat(good).isGreaterThan(poor);
        assertThat(good).isBetween(70, 100);
    }

    @Test
    @DisplayName("skills match whole words: 'go' is not found in 'Google'")
    void wholeWords() {
        FitScore.Fit f = fit.fit(posting("Engineer at Google", "We use Python.", null,
                StrategicClass.INDIA_OTHER, "IN", null), TODAY);
        assertThat(f.matched()).containsExactly("python");
        assertThat(f.summary()).contains("1 skill (python)").contains("years not stated").contains("India");
    }

    @Test
    @DisplayName("remote for a foreign employer is the best lane, then Mumbai, then India, then abroad")
    void laneOrder() {
        int remote = laneScore(StrategicClass.INTERNATIONAL_REMOTE, "US");
        int mumbai = laneScore(StrategicClass.INDIA_HOME, "IN");
        int india = laneScore(StrategicClass.INDIA_OTHER, "IN");
        int ireland = laneScore(StrategicClass.INTERNATIONAL_RELOCATION, "IE");
        int uk = laneScore(StrategicClass.INTERNATIONAL_RELOCATION, "GB");
        assertThat(List.of(remote, mumbai, india, ireland, uk)).isSortedAccordingTo((a, b) -> b - a);
        assertThat(ireland).isGreaterThan(uk);
    }

    @Test
    @DisplayName("an entry-level title outranks a senior one with more skill words (2026-10-01)")
    void levelBeatsSkillWords() {
        // Real titles from the 2026-10-01 window. The senior one scored 74 and the
        // fresher 52 when only skill words counted.
        String longSeniorText = "Python, Kafka, FastAPI, SQL, PostgreSQL, Go in production.";
        int senior = fit.fit(posting("Associate Distinguished Engineer (Cloud Architecture, Data Modeling)",
                longSeniorText, null, StrategicClass.INDIA_OTHER, "IN", TODAY), TODAY).score();
        int fresher = fit.fit(posting("Java Developer - Fresher", "Banking projects in Java.", null,
                StrategicClass.INDIA_OTHER, "IN", TODAY), TODAY).score();
        assertThat(fresher).isGreaterThan(senior + 15);
    }

    @Test
    @DisplayName("titles read as entry level, internship, second level or senior")
    void levels() {
        assertThat(level("Software Engineer, New Grad")).isEqualTo(FitScore.Level.ENTRY);
        assertThat(level("Engineering Graduate Programme (Backend)")).isEqualTo(FitScore.Level.ENTRY);
        assertThat(level("Software Engineer I")).isEqualTo(FitScore.Level.ENTRY);
        assertThat(level("Data Analyst I")).isEqualTo(FitScore.Level.ENTRY);
        assertThat(level("Associate Software Engineer")).isEqualTo(FitScore.Level.ENTRY);
        assertThat(level("Developer L1")).isEqualTo(FitScore.Level.ENTRY);
        assertThat(level("Junior Backend Software Engineer (Kotlin / Java)")).isEqualTo(FitScore.Level.ENTRY);
        assertThat(level("Software Development Engineer Intern")).isEqualTo(FitScore.Level.INTERN);
        assertThat(level("Software Engineer II")).isEqualTo(FitScore.Level.SECOND);
        assertThat(level("Lead Data Engineer")).isEqualTo(FitScore.Level.SENIOR);
        assertThat(level("Software Engineer III")).isEqualTo(FitScore.Level.SENIOR);
        // Not a Roman numeral.
        assertThat(level("Software Engineer in Test")).isEqualTo(FitScore.Level.NEUTRAL);
        assertThat(level("Data Engineer")).isEqualTo(FitScore.Level.NEUTRAL);
    }

    @Test
    @DisplayName("Google's II is its entry level, so it costs nothing there")
    void googleSecondLevel() {
        Posting p = posting("Software Engineer II, YouTube", "", null, StrategicClass.INDIA_OTHER, "IN", null);
        when(p.getSource()).thenReturn(com.anuragbhandary.jobradar.domain.Source.GOOGLE);
        assertThat(FitScore.level(p)).isEqualTo(FitScore.Level.NEUTRAL);
    }

    private static FitScore.Level level(String title) {
        return FitScore.level(posting(title, "", null, StrategicClass.INDIA_OTHER, "IN", null));
    }

    private int laneScore(StrategicClass lane, String country) {
        return fit.fit(posting("Engineer", "", null, lane, country, null), TODAY).score();
    }

    @Test
    @DisplayName("senior wording in the description costs points when the title hides it")
    void seniorWording() {
        FitScore.Fit plain = fit.fit(posting("Platform Engineer", "Python and Kafka on AWS.", null,
                StrategicClass.INDIA_OTHER, "IN", TODAY), TODAY);
        FitScore.Fit senior = fit.fit(posting("Platform Engineer", "Python and Kafka on AWS."
                + " Experience managing and mentoring engineering teams.", null,
                StrategicClass.INDIA_OTHER, "IN", TODAY), TODAY);

        assertThat(plain.score() - senior.score()).isEqualTo(FitScore.SENIOR_WORDING_PENALTY);
        assertThat(senior.summary()).contains("senior wording");
        assertThat(FitScore.level(posting("Software Engineering PMTS", "", null,
                StrategicClass.INDIA_OTHER, "IN", TODAY))).isEqualTo(FitScore.Level.SENIOR);
    }
}
