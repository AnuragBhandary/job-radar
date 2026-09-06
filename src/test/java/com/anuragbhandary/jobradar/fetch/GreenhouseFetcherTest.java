package com.anuragbhandary.jobradar.fetch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.anuragbhandary.jobradar.domain.Source;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Parses a fixture captured from the live Tines board, not hand-written JSON.
 *
 * <p>No test in this project touches a live endpoint. The fixture is a trimmed
 * copy of a real response, which is why it exercises awkward details a
 * hand-written one would have tidied away - a trailing space in a title, an
 * entity-escaped description, a parenthesised location.
 */
class GreenhouseFetcherTest {

    private GreenhouseFetcher fetcher;
    private String fixture;

    @BeforeEach
    void setUp() throws IOException {
        fetcher = new GreenhouseFetcher(null, new ObjectMapper());
        try (InputStream in = getClass().getResourceAsStream("/fixtures/greenhouse-tines.json")) {
            fixture = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    @DisplayName("reports its own source")
    void reportsSource() {
        assertThat(fetcher.source()).isEqualTo(Source.GREENHOUSE);
    }

    @Test
    @DisplayName("maps every posting in the board response")
    void parsesAllPostings() throws Exception {
        List<RawPosting> postings = fetcher.parse(fixture, "tines");
        assertThat(postings).hasSize(3);
        assertThat(postings).extracting(RawPosting::externalId)
                .containsExactly("6100569004", "6127970004", "6103937004");
    }

    @Test
    @DisplayName("uses the public job id, not the internal one, as external id")
    void usesPublicJobId() throws Exception {
        RawPosting first = fetcher.parse(fixture, "tines").getFirst();
        // internal_job_id in the fixture is 5178297004 - a different number that
        // does not appear in the apply URL and is not stable across boards.
        assertThat(first.externalId()).isEqualTo("6100569004");
        assertThat(first.url()).contains("6100569004");
    }

    @Test
    @DisplayName("trims whitespace that boards leave in titles")
    void trimsTitles() throws Exception {
        List<RawPosting> postings = fetcher.parse(fixture, "tines");
        // The real response has a trailing space on this title.
        assertThat(postings.get(2).title())
                .isEqualTo("Executive Assistant to CEO and COO ( Fixed term contract )");
    }

    @Test
    @DisplayName("unescapes and strips the HTML description down to plain text")
    void producesPlainTextDescriptions() throws Exception {
        RawPosting first = fetcher.parse(fixture, "tines").getFirst();
        assertThat(first.description())
                .doesNotContain("&lt;")
                .doesNotContain("&amp;")
                .doesNotContain("<div")
                .doesNotContain("<p>")
                .contains("Tines");
    }

    @Test
    @DisplayName("keeps the raw location string unparsed")
    void keepsRawLocation() throws Exception {
        List<RawPosting> postings = fetcher.parse(fixture, "tines");
        assertThat(postings.get(2).location()).isEqualTo("Dublin, Ireland (Hybrid)");
    }

    @Test
    @DisplayName("reads first_published as the posted date")
    void parsesPostedDate() throws Exception {
        RawPosting first = fetcher.parse(fixture, "tines").getFirst();
        assertThat(first.postedDate()).isEqualTo(LocalDate.of(2026, 6, 24));
    }

    @Test
    @DisplayName("a response with no jobs array is a failure, not an empty board")
    void rejectsResponseWithoutJobsArray() {
        assertThatThrownBy(() -> fetcher.parse("{\"error\":\"not found\"}", "nosuchtoken"))
                .isInstanceOf(FetchException.class)
                .hasMessageContaining("nosuchtoken");
    }

    @Test
    @DisplayName("an empty board parses to an empty list")
    void emptyBoardIsNotAnError() throws Exception {
        assertThat(fetcher.parse("{\"jobs\":[],\"meta\":{\"total\":0}}", "quiet")).isEmpty();
    }

    @Test
    @DisplayName("a posting with no id is skipped rather than tracked")
    void skipsPostingsWithoutAnId() throws Exception {
        // Without an id it cannot be matched across runs, so it would appear as
        // new in every single digest.
        String body = "{\"jobs\":[{\"title\":\"Engineer\"},{\"id\":7,\"title\":\"Real\"}]}";
        assertThat(fetcher.parse(body, "partial"))
                .extracting(RawPosting::title)
                .containsExactly("Real");
    }
}
