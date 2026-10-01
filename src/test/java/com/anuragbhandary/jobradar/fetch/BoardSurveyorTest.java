package com.anuragbhandary.jobradar.fetch;

import static org.assertj.core.api.Assertions.assertThat;

import com.anuragbhandary.jobradar.config.AppProperties;
import com.anuragbhandary.jobradar.domain.Source;
import com.anuragbhandary.jobradar.filter.RealConfigAccess;
import com.anuragbhandary.jobradar.filter.TitleFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The Workday fixtures are trimmed first pages captured on 2026-10-01: Walmart,
 * whose site reports jobs per country, and a US health system whose site only
 * reports places - including two in Indiana, written "IN".
 */
class BoardSurveyorTest {

    private static final AppProperties CONFIG =
            new AppProperties(null, null, null, null, RealConfigAccess.screening(), null);

    /** Serves one saved first page for every Workday request. */
    private static final class OnePage extends HttpFetchClient {
        private final String fixture;
        int posts;

        OnePage(String fixture) {
            super(null, new AppProperties(null, null,
                    new AppProperties.Http("test", 0, 5, 1, null), null, null, null), null);
            this.fixture = fixture;
        }

        @Override
        public String post(String url, String jsonBody, String fixtureName) {
            posts++;
            return FixtureSupport.load(fixture);
        }
    }

    private static BoardSurveyor surveyor(HttpFetchClient http) {
        return new BoardSurveyor(List.of(), null, http, new ObjectMapper(),
                RealConfigAccess.locations(), new TitleFilter(CONFIG));
    }

    private static RawPosting posting(String title, String location) {
        return new RawPosting("1", title, location, "", "https://example.com/1", LocalDate.of(2026, 10, 1));
    }

    @Test
    @DisplayName("Workday's country facet answers in one request")
    void workdayCountryFacet() throws Exception {
        OnePage http = new OnePage("workday-walmart-facets.json");
        BoardSurveyor.Survey s = surveyor(http).survey(Source.WORKDAY, "walmart/wd504/WalmartExternal");

        assertThat(s.india()).isEqualTo(228);
        assertThat(s.relocation()).isZero();
        assertThat(s.hasTargetRoles()).isTrue();
        assertThat(http.posts).isEqualTo(1);
    }

    @Test
    @DisplayName("a site with only place names is classified, and Indiana is not India")
    void workdayPlacesAreClassified() throws Exception {
        // The text search this replaced found "india" in Indialantic, FL and
        // took "IN" for India, and added a US health system to the daily run.
        OnePage http = new OnePage("workday-alcority-facets.json");
        BoardSurveyor.Survey s = surveyor(http).survey(Source.WORKDAY, "alcority/wd1/TraditionsHealth");

        assertThat(s.india()).isZero();
        assertThat(s.hasTargetRoles()).isFalse();
    }

    @Test
    @DisplayName("only roles with a passing title in a target place count")
    void countsTargetRoles() {
        BoardSurveyor.Survey s = surveyor(null).count(List.of(
                posting("Software Engineer", "Bengaluru, India"),
                posting("Senior Software Engineer", "Bengaluru, India"),
                posting("Backend Engineer", "Dublin, Ireland"),
                posting("Data Engineer", "Remote - Worldwide"),
                posting("Software Engineer", "Remote"),
                posting("Software Engineer", "San Francisco, CA"),
                // Workday's country-state-city form of Dublin, Ohio.
                posting("Software Engineer", "US-OH-DUBLIN (Crosby)")), 6);

        assertThat(s.india()).isEqualTo(1);
        assertThat(s.relocation()).isEqualTo(1);
        // Worldwide counts; bare "Remote", usually American, does not.
        assertThat(s.remote()).isEqualTo(1);
        assertThat(s.total()).isEqualTo(6);
    }

    @Test
    @DisplayName("Workday country names are read from either facet layout")
    void readsCountryFacet() throws Exception {
        Map<String, Integer> countries = BoardSurveyor.workdayCountries(
                new ObjectMapper().readTree(FixtureSupport.load("workday-walmart-facets.json")));
        assertThat(countries).containsEntry("india", 228).containsKey("canada");
        assertThat(BoardSurveyor.workdayCountries(
                new ObjectMapper().readTree(FixtureSupport.load("workday-alcority-facets.json")))).isNull();
    }
}
