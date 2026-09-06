package com.anuragbhandary.jobradar.fetch;

import static org.assertj.core.api.Assertions.assertThat;

import com.anuragbhandary.jobradar.config.AppProperties;
import com.anuragbhandary.jobradar.domain.Source;
import com.anuragbhandary.jobradar.filter.GeoFilter;
import com.anuragbhandary.jobradar.filter.RealConfigAccess;
import com.anuragbhandary.jobradar.filter.TitleFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SmartRecruitersFetcherTest {

    private static final AppProperties CONFIG =
            new AppProperties(null, null, null, null, RealConfigAccess.screening(), null);

    /** Records which URLs were requested, so the test can prove what was skipped. */
    private static final class RecordingClient extends HttpFetchClient {
        private final List<String> requested = new ArrayList<>();

        RecordingClient() {
            super(null, new AppProperties(null, null,
                    new AppProperties.Http("test", 0, 5, 1), null, null, null), null);
        }

        @Override
        public String get(String url, String fixtureName) {
            requested.add(url);
            return url.matches(".*/postings/\\d+$")
                    ? FixtureSupport.load("smartrecruiters-swiggy-detail.json")
                    : FixtureSupport.load("smartrecruiters-swiggy-list.json");
        }
    }

    private final RecordingClient client = new RecordingClient();
    private final SmartRecruitersFetcher fetcher = new SmartRecruitersFetcher(
            client, new ObjectMapper(), new GeoFilter(CONFIG), new TitleFilter(CONFIG));

    @Test
    void reportsSource() {
        assertThat(fetcher.source()).isEqualTo(Source.SMARTRECRUITERS);
    }

    @Test
    @DisplayName("only postings passing geography and title get a detail request")
    void filtersBeforeFetchingDetails() throws Exception {
        // The list response has no descriptions, so each survivor costs a
        // request. The fixture's three postings are all Sales Manager titles in
        // India: geography passes, title does not, so none should be fetched.
        FetchBatch batch = fetcher.fetch("Swiggy");

        assertThat(batch.postings()).isEmpty();
        assertThat(client.requested).hasSize(1);
        assertThat(client.requested.getFirst()).contains("limit=100&offset=0");
    }

    @Test
    @DisplayName("board health reports the board's size, not the shortlist's")
    void reportsBoardTotalNotShortlistSize() throws Exception {
        // The fixture advertises 66 postings and none survive the filters. If
        // health recorded the shortlist, a busy board with no matches would look
        // exactly like a board that had gone empty.
        FetchBatch batch = fetcher.fetch("Swiggy");

        assertThat(batch.postings()).isEmpty();
        assertThat(batch.boardTotal()).isEqualTo(66);
    }

    @Test
    @DisplayName("the two-letter country code is expanded so the filter can read it")
    void expandsCountryCode() throws Exception {
        // "in" matches nothing in the geography word lists; "India" matches.
        // Equally important in the other direction: "us" becomes "United States"
        // and is then correctly excluded.
        var mapper = new ObjectMapper();
        var summary = mapper.readTree(FixtureSupport.load("smartrecruiters-swiggy-list.json"))
                .path("content").get(0);

        var method = SmartRecruitersFetcher.class
                .getDeclaredMethod("location", com.fasterxml.jackson.databind.JsonNode.class);
        method.setAccessible(true);
        String location = (String) method.invoke(null, summary.path("location"));

        assertThat(location).contains("Hyderabad").contains("India");
    }
}
