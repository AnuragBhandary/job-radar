package com.anuragbhandary.jobradar.fetch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.anuragbhandary.jobradar.domain.Source;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AshbyFetcherTest {

    private final AshbyFetcher fetcher = new AshbyFetcher(null, new ObjectMapper());
    private final String fixture = FixtureSupport.load("ashby-notion.json");

    @Test
    void reportsSource() {
        assertThat(fetcher.source()).isEqualTo(Source.ASHBY);
    }

    @Test
    @DisplayName("unlisted postings are skipped")
    void skipsUnlisted() throws Exception {
        // Ashby publishes drafts and internal roles through the same feed.
        List<RawPosting> postings = fetcher.parse(fixture, "notion");
        assertThat(postings).hasSize(2);
        assertThat(postings).extracting(RawPosting::externalId).doesNotContain("unlisted-0001");
    }

    @Test
    @DisplayName("descriptionPlain is used as-is, with no tags to strip")
    void usesPlainDescription() throws Exception {
        RawPosting first = fetcher.parse(fixture, "notion").getFirst();
        assertThat(first.description()).doesNotContain("<p>").doesNotContain("&lt;");
        assertThat(first.description()).isNotBlank();
    }

    @Test
    @DisplayName("secondary locations are kept, not dropped")
    void joinsSecondaryLocations() throws Exception {
        // A role open in San Francisco and Dublin lists only San Francisco in
        // "location". Dropping secondaryLocations loses every multi-site posting
        // listed against another city first.
        RawPosting first = fetcher.parse(fixture, "notion").getFirst();
        assertThat(first.location()).contains("San Francisco").contains("New York");
    }

    @Test
    void parsesPublishedDate() throws Exception {
        assertThat(fetcher.parse(fixture, "notion").getFirst().postedDate())
                .isEqualTo(LocalDate.of(2026, 8, 24));
    }

    @Test
    @DisplayName("a response with no jobs array is a failure, not an empty board")
    void rejectsMalformedResponse() {
        assertThatThrownBy(() -> fetcher.parse("{\"error\":\"nope\"}", "bogus"))
                .isInstanceOf(FetchException.class).hasMessageContaining("bogus");
    }

    @Test
    void emptyBoardIsNotAnError() throws Exception {
        assertThat(fetcher.parse("{\"jobs\":[]}", "quiet")).isEmpty();
    }
}
