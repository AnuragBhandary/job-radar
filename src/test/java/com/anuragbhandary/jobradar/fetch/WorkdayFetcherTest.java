package com.anuragbhandary.jobradar.fetch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.anuragbhandary.jobradar.config.AppProperties;
import com.anuragbhandary.jobradar.domain.Source;
import com.anuragbhandary.jobradar.filter.GeoFilter;
import com.anuragbhandary.jobradar.filter.RealConfigAccess;
import com.anuragbhandary.jobradar.filter.TitleFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The fixtures are trimmed copies of a live NXP fetch: four of the twenty
 * postings on page one, and the detail response for the only one that survives
 * screening.
 */
class WorkdayFetcherTest {

    private static final AppProperties CONFIG =
            new AppProperties(null, null, null, null, RealConfigAccess.screening(), null);

    /** Records what was asked for, so the test can prove what was skipped. */
    private static final class RecordingClient extends HttpFetchClient {
        private final List<String> listed = new ArrayList<>();
        private final List<String> detailed = new ArrayList<>();

        RecordingClient() {
            super(null, new AppProperties(null, null,
                    new AppProperties.Http("test", 0, 5, 1), null, null, null), null);
        }

        @Override
        public String post(String url, String jsonBody, String fixtureName) {
            listed.add(jsonBody);
            return FixtureSupport.load("workday-nxp-list.json");
        }

        @Override
        public String get(String url, String fixtureName) {
            detailed.add(url);
            return FixtureSupport.load("workday-nxp-detail.json");
        }
    }

    private final RecordingClient client = new RecordingClient();
    private final WorkdayFetcher fetcher = new WorkdayFetcher(
            client, new ObjectMapper(), new GeoFilter(CONFIG), new TitleFilter(CONFIG));

    @Test
    void reportsSource() {
        assertThat(fetcher.source()).isEqualTo(Source.WORKDAY);
    }

    @Test
    @DisplayName("only postings passing geography and title get a detail request")
    void filtersBeforeFetchingDetails() throws Exception {
        // Four postings in, one survivor: a US location, a "Senior" title and an
        // "Internship" title are all rejected before costing a request.
        FetchBatch batch = fetcher.fetch("nxp/wd3/careers");

        assertThat(batch.postings()).hasSize(1);
        assertThat(client.detailed).hasSize(1);
    }

    @Test
    @DisplayName("the requisition id and real start date come from the detail response")
    void mapsDetailFields() throws Exception {
        RawPosting posting = fetcher.fetch("nxp/wd3/careers").postings().getFirst();

        // The list only offers "Posted 30+ Days Ago", which stops being a date at
        // thirty. The detail carries the actual one.
        assertThat(posting.postedDate()).isEqualTo(LocalDate.of(2026, 9, 4));
        assertThat(posting.externalId()).isEqualTo("R-10064331");
        assertThat(posting.location()).isEqualTo("Eindhoven");
        assertThat(posting.description())
                .doesNotContain("<p>", "<span>")
                .contains("Junior Systems Engineer");
    }

    @Test
    @DisplayName("board health reports the board's own size, not what survived our filters")
    void reportsBoardTotalNotShortlistSize() throws Exception {
        // 760 is what NXP says it has. Recording 1 would make a large board that
        // happens to suit nobody look dead.
        assertThat(fetcher.fetch("nxp/wd3/careers").boardTotal()).isEqualTo(760);
    }

    @Test
    @DisplayName("a token that is not tenant/wdN/site fails before any request is made")
    void rejectsMalformedToken() {
        assertThatThrownBy(() -> fetcher.fetch("nxp"))
                .isInstanceOf(FetchException.class)
                .hasMessageContaining("tenant/wdN/site");
        assertThat(client.listed).isEmpty();
    }
}
