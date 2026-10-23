package com.anuragbhandary.jobradar.fetch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.anuragbhandary.jobradar.domain.Source;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** IBM, Capgemini and Atlassian: big employers read through their own careers APIs. */
class CareersApiFetchersTest {

    private final ObjectMapper json = new ObjectMapper();

    @Nested
    class Ibm {

        private final IbmCareersFetcher fetcher = new IbmCareersFetcher(null, json);

        @Test
        void reportsSource() {
            assertThat(fetcher.source()).isEqualTo(Source.IBM);
        }

        @Test
        @DisplayName("the jobId in the URL is the id, and the full body is the description")
        void mapsAHit() throws Exception {
            List<RawPosting> postings = fetcher.parse(FixtureSupport.load("ibm-india.json"), "India");
            assertThat(postings).hasSize(2);
            RawPosting engineer = postings.get(1);
            assertThat(engineer.externalId()).isEqualTo("138148");
            assertThat(engineer.title()).isEqualTo("Data Engineer-Data Platforms-SnowFlake");
            assertThat(engineer.url()).isEqualTo("https://careers.ibm.com/careers/JobDetail?jobId=138148");
            assertThat(engineer.postedDate()).isEqualTo(LocalDate.of(2026, 10, 23));
            // The list's description is a 250-character teaser; body is the job.
            assertThat(engineer.description().length()).isGreaterThan(400);
        }

        @Test
        @DisplayName("IBM's level heads the description, since few jobs state years")
        void leadsWithTheLevel() throws Exception {
            RawPosting first = fetcher.parse(FixtureSupport.load("ibm-india.json"), "India").getFirst();
            assertThat(first.description()).startsWith("IBM level: Professional\n\n");
            assertThat(IbmCareersFetcher.withLevel("", "text")).isEqualTo("text");
        }

        @Test
        @DisplayName("the facet's country replaces the two-letter code after the city")
        void namesTheCountry() throws Exception {
            RawPosting first = fetcher.parse(FixtureSupport.load("ibm-india.json"), "India").getFirst();
            assertThat(first.location()).isEqualTo("CHENNAI, India");
            assertThat(IbmCareersFetcher.place("", "Ireland")).isEqualTo("Ireland");
        }

        @Test
        @DisplayName("the query filters on the Location facet and pages by from")
        void buildsTheQuery() throws Exception {
            var query = json.readTree(fetcher.query("United Kingdom", 200));
            assertThat(query.at("/query/bool/filter/0/term/field_keyword_05").asText())
                    .isEqualTo("United Kingdom");
            assertThat(query.path("from").asInt()).isEqualTo(200);
            assertThat(query.path("appId").asText()).isEqualTo("careers");
        }

        @Test
        void rejectsAResponseWithoutHits() {
            assertThatThrownBy(() -> fetcher.parse("{\"errors\":[]}", "India"))
                    .isInstanceOf(FetchException.class);
        }
    }

    @Nested
    class Capgemini {

        private final CapgeminiFetcher fetcher = new CapgeminiFetcher(null, json);

        @Test
        void reportsSource() {
            assertThat(fetcher.source()).isEqualTo(Source.CAPGEMINI);
        }

        @Test
        @DisplayName("the city gets the token's country, and the HTML description is stripped")
        void mapsAJob() throws Exception {
            List<RawPosting> postings = fetcher.parse(FixtureSupport.load("capgemini-en-in.json"), "en-in");
            assertThat(postings).hasSize(2);
            RawPosting first = postings.getFirst();
            assertThat(first.externalId()).isEqualTo("569382-en_GB");
            assertThat(first.title()).isEqualTo("Data Privacy");
            assertThat(first.location()).isEqualTo("Navi Mumbai, India");
            assertThat(first.url()).startsWith("https://careers.capgemini.com/job/");
            assertThat(first.description()).startsWith("Capgemini level: Experienced Professionals\n\n")
                    .doesNotContain("<div");
        }

        @Test
        @DisplayName("updated_at is the re-index time, so no posted date is claimed")
        void leavesThePostedDateEmpty() throws Exception {
            assertThat(fetcher.parse(FixtureSupport.load("capgemini-en-in.json"), "en-in"))
                    .allSatisfy(p -> assertThat(p.postedDate()).isNull());
        }

        @Test
        void readsEitherOrderOfCountryCode() {
            assertThat(CapgeminiFetcher.country("en-in")).isEqualTo("India");
            assertThat(CapgeminiFetcher.country("in-en")).isEqualTo("India");
            assertThat(CapgeminiFetcher.country("en-gb")).isEqualTo("United Kingdom");
            assertThat(CapgeminiFetcher.country("nl-nl")).isEqualTo("Netherlands");
        }
    }

    @Nested
    class Atlassian {

        private final AtlassianFetcher fetcher = new AtlassianFetcher(null, json);

        @Test
        void reportsSource() {
            assertThat(fetcher.source()).isEqualTo(Source.ATLASSIAN);
        }

        @Test
        @DisplayName("every place is kept, with the padding collapsed")
        void joinsThePlaces() throws Exception {
            RawPosting first = fetcher.parse(FixtureSupport.load("atlassian-listings.json")).getFirst();
            assertThat(first.externalId()).isEqualTo("25480");
            assertThat(first.location())
                    .isEqualTo("Bengaluru - India - Bengaluru, 560071 India; Remote - India - Remote; Remote - Remote");
            assertThat(first.url()).startsWith("https://globalcareers-atlassian.icims.com/jobs/25480/");
            assertThat(first.postedDate()).isEqualTo(LocalDate.of(2026, 10, 23));
        }

        @Test
        @DisplayName("the description joins overview, responsibilities and qualifications")
        void joinsTheText() throws Exception {
            RawPosting first = fetcher.parse(FixtureSupport.load("atlassian-listings.json")).getFirst();
            assertThat(first.description()).contains("Working at Atlassian").doesNotContain("<p>");
        }

        @Test
        void rejectsANonArray() {
            assertThatThrownBy(() -> fetcher.parse("{}")).isInstanceOf(FetchException.class);
        }
    }
}
