package com.anuragbhandary.jobradar.fetch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.anuragbhandary.jobradar.domain.Source;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RecruiteeFetcherTest {

    private final RecruiteeFetcher fetcher = new RecruiteeFetcher(null, new ObjectMapper());
    private final String fixture = FixtureSupport.load("recruitee-channable.json");

    @Test
    void reportsSource() {
        assertThat(fetcher.source()).isEqualTo(Source.RECRUITEE);
    }

    @Test
    void parsesTheBoard() throws Exception {
        List<RawPosting> postings = fetcher.parse(fixture, "channable");

        assertThat(postings).hasSize(4);
        assertThat(postings.getFirst().externalId()).isEqualTo("2728481");
        assertThat(postings.getFirst().title())
                .isEqualTo("AP/AR Specialist - Join our finance team!");
    }

    @Test
    @DisplayName("requirements are included, not just the description")
    void includesRequirements() throws Exception {
        // `description` is the prose about the company; `requirements` is the
        // list stating how many years they want. Reading only the first screens
        // every Recruitee posting on its marketing copy - the same bug the Lever
        // fetcher was written to avoid.
        RawPosting first = fetcher.parse(fixture, "channable").getFirst();

        assertThat(first.description()).contains("Requirements");
        assertThat(first.description()).doesNotContain("<p>").doesNotContain("</span>");
    }

    @Test
    @DisplayName("the apply URL goes to the form, not the description page")
    void prefersTheApplyUrl() throws Exception {
        assertThat(fetcher.parse(fixture, "channable").getFirst().url())
                .endsWith("/c/new");
    }

    @Test
    void readsThePublishedDate() throws Exception {
        assertThat(fetcher.parse(fixture, "channable").getFirst().postedDate())
                .isEqualTo(LocalDate.of(2026, 9, 2));
    }

    @Test
    @DisplayName("every office is listed, not only the first")
    void listsAllLocations() throws Exception {
        assertThat(fetcher.parse(fixture, "channable").getFirst().location())
                .contains("Utrecht")
                .contains("Netherlands");
    }

    @Test
    @DisplayName("'remote' is appended to the office, never substituted for it")
    void remoteDoesNotReplaceTheCountry() throws Exception {
        // Recruitee models remote as an attribute of a job that still belongs to
        // an office, so a remote posting can still be country-locked. Flattening
        // it to "Remote" hides exactly the restriction the geo filter exists for.
        String body = """
                {"offers":[{"id":1,"title":"Backend Engineer","status":"published",
                 "location":"Buenos Aires, Argentina","remote":true,
                 "locations":[{"name":"Buenos Aires","country":"Argentina"}],
                 "description":"<p>Role</p>","requirements":"<p>3 years</p>",
                 "careers_apply_url":"https://x/c/new","published_at":"2026-09-01 10:00:00 UTC"}]}
                """;

        RawPosting posting = fetcher.parse(body, "x").getFirst();

        assertThat(posting.location()).contains("Argentina").contains("Remote");
    }

    @Test
    void skipsUnpublishedOffers() throws Exception {
        String body = """
                {"offers":[
                 {"id":1,"title":"Draft Role","status":"draft"},
                 {"id":2,"title":"Live Role","status":"published"}]}
                """;

        assertThat(fetcher.parse(body, "x"))
                .extracting(RawPosting::title)
                .containsExactly("Live Role");
    }

    @Test
    @DisplayName("an unknown token is an error, not an empty board")
    void unknownTokenFailsLoudly() {
        // Unlike SmartRecruiters, Recruitee tells the two apart - so an empty
        // board here really is a board with no openings, and that is worth
        // preserving rather than flattening.
        assertThatThrownBy(() -> fetcher.parse("{\"error\":\"Not Found\"}", "nope"))
                .isInstanceOf(FetchException.class)
                .hasMessageContaining("Not Found");

        assertThatThrownBy(() -> fetcher.parse("{\"something\":1}", "nope"))
                .isInstanceOf(FetchException.class);
    }

    @Test
    void anEmptyBoardIsNotAFailure() throws Exception {
        assertThat(fetcher.parse("{\"offers\":[]}", "x")).isEmpty();
    }
}
