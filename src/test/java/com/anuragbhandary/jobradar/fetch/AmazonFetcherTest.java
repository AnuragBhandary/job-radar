package com.anuragbhandary.jobradar.fetch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.anuragbhandary.jobradar.domain.Source;
import com.anuragbhandary.jobradar.filter.YearsExtractor;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AmazonFetcherTest {

    private final AmazonFetcher fetcher = new AmazonFetcher(null, new ObjectMapper());
    private final String fixture = FixtureSupport.load("amazon-ind.json");

    @Test
    void reportsSource() {
        assertThat(fetcher.source()).isEqualTo(Source.AMAZON);
    }

    @Test
    @DisplayName("the icims id is the external id, and the job path becomes a full URL")
    void mapsIdentityAndUrl() throws Exception {
        RawPosting first = fetcher.parse(fixture, "IND").getFirst();
        assertThat(first.externalId()).isEqualTo("10529736");
        assertThat(first.url()).startsWith("https://www.amazon.jobs/en/jobs/10529736");
    }

    @Test
    @DisplayName("the double space in Amazon's date does not break parsing")
    void parsesPaddedDate() throws Exception {
        // Amazon writes "September  4, 2026" - the day is padded rather than
        // trimmed, and a strict formatter rejects it outright.
        assertThat(fetcher.parse(fixture, "IND").getFirst().postedDate())
                .isEqualTo(LocalDate.of(2026, 9, 4));
    }

    @Test
    @DisplayName("normalized_location is preferred over the raw location code")
    void usesNormalizedLocation() throws Exception {
        // "IN, KA, Bengaluru" is not something the geography filter can read;
        // "Bengaluru, Karnataka, IND" is.
        assertThat(fetcher.parse(fixture, "IND").getFirst().location())
                .isEqualTo("Bengaluru, Karnataka, IND");
    }

    @Test
    @DisplayName("the description is the qualifications, not the role blurb")
    void usesQualificationsAsDescription() throws Exception {
        RawPosting first = fetcher.parse(fixture, "IND").getFirst();
        // The years extractor takes the smallest number it finds, and prose
        // about how long a team has existed is exactly the stray small number
        // that would turn a genuine rejection into an acceptance.
        assertThat(first.description()).contains("years of non-internship");
        assertThat(first.description()).doesNotContain("<br/>");
    }

    @Test
    @DisplayName("the non-internship rule fires on real Amazon text")
    void nonInternshipRuleFiresOnRealData() throws Exception {
        // The whole reason Amazon is worth wiring up: this phrasing is
        // disqualifying, and until now the rule had nothing to bite on.
        List<RawPosting> postings = fetcher.parse(fixture, "IND");
        YearsExtractor extractor = new YearsExtractor();

        assertThat(postings)
                .anyMatch(p -> extractor.extract(p.description()).hasNonInternshipRequirement());
    }

    @Test
    void rejectsMalformedResponse() {
        assertThatThrownBy(() -> fetcher.parse("{\"error\":\"nope\"}", "IND"))
                .isInstanceOf(FetchException.class).hasMessageContaining("IND");
    }
}
