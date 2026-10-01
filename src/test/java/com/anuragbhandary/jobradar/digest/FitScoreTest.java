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
            new FitScore.Properties(List.of("python", "kafka", "fastapi", "sql", "go", "postgresql"), 60),
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

    private int laneScore(StrategicClass lane, String country) {
        return fit.fit(posting("Engineer", "", null, lane, country, null), TODAY).score();
    }
}
