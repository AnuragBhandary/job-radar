package com.anuragbhandary.jobradar.fetch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.anuragbhandary.jobradar.domain.Source;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LeverFetcherTest {

    private final LeverFetcher fetcher = new LeverFetcher(null, new ObjectMapper());
    private final String fixture = FixtureSupport.load("lever-cred.json");

    @Test
    void reportsSource() {
        assertThat(fetcher.source()).isEqualTo(Source.LEVER);
    }

    @Test
    @DisplayName("a bare array is parsed, not an object with a jobs key")
    void parsesBareArray() throws Exception {
        assertThat(fetcher.parse(fixture, "cred")).hasSize(2);
    }

    @Test
    @DisplayName("the requirements lists are included, not just the opening prose")
    void includesLists() throws Exception {
        // descriptionPlain holds the blurb; the requirements live in lists[] as
        // HTML. Reading only descriptionPlain would screen every Lever posting
        // on its marketing copy.
        RawPosting first = fetcher.parse(fixture, "cred").getFirst();
        String plainOnly = new ObjectMapper().readTree(fixture).get(0)
                .path("descriptionPlain").asText();

        assertThat(first.description()).hasSizeGreaterThan(plainOnly.length());
        assertThat(first.description()).doesNotContain("<div").doesNotContain("</li>");
    }

    @Test
    @DisplayName("the title comes from 'text', not 'title'")
    void readsTitleFromText() throws Exception {
        assertThat(fetcher.parse(fixture, "cred").getFirst().title())
                .isEqualTo("area collections manager bangalore -flows");
    }

    @Test
    void readsLocationFromCategories() throws Exception {
        assertThat(fetcher.parse(fixture, "cred").getFirst().location()).contains("bengaluru");
    }

    @Test
    @DisplayName("createdAt is epoch milliseconds, not an ISO string")
    void parsesEpochMillisDate() throws Exception {
        assertThat(fetcher.parse(fixture, "cred").getFirst().postedDate()).isNotNull();
    }

    @Test
    @DisplayName("a non-array response is a failure")
    void rejectsNonArray() {
        assertThatThrownBy(() -> fetcher.parse("{\"jobs\":[]}", "bogus"))
                .isInstanceOf(FetchException.class).hasMessageContaining("bogus");
    }

    @Test
    void emptyBoardIsNotAnError() throws Exception {
        assertThat(fetcher.parse("[]", "quiet")).isEmpty();
    }
}
