package com.anuragbhandary.jobradar.fetch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.anuragbhandary.jobradar.config.AppProperties;
import com.anuragbhandary.jobradar.domain.Source;
import com.anuragbhandary.jobradar.filter.YearsExtractor;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The fixture is the results block of Google's early-career search for India
 * on 2026-10-01, cut to three of its twenty jobs; the page reported 24 in all.
 */
class GoogleCareersFetcherTest {

    private final String fixture = FixtureSupport.load("google-india.html");

    /** Serves the saved page for page 1 and an empty search after it. */
    private static final class PagingClient extends HttpFetchClient {
        final List<String> urls = new ArrayList<>();

        PagingClient() {
            super(null, new AppProperties(null, null,
                    new AppProperties.Http("test", 0, 5, 1, null), null, null, null), null);
        }

        @Override
        public String get(String url, String fixtureName) {
            urls.add(url);
            return url.endsWith("&page=1")
                    ? FixtureSupport.load("google-india.html")
                    : "<script>AF_initDataCallback({key: 'ds:1', hash: '2', data:[null,0,20], sideChannel: {}});</script>";
        }
    }

    private final GoogleCareersFetcher fetcher = new GoogleCareersFetcher(null, new ObjectMapper());

    @Test
    void reportsSource() {
        assertThat(fetcher.source()).isEqualTo(Source.GOOGLE);
    }

    @Test
    @DisplayName("every job on the page is read, with the search's total")
    void readsPage() throws Exception {
        GoogleCareersFetcher.Page page = fetcher.parse(fixture, "India");

        assertThat(page.postings()).hasSize(3);
        assertThat(page.total()).isEqualTo(24);
    }

    @Test
    @DisplayName("id, title, locations, link and date come from the positional fields")
    void mapsFields() throws Exception {
        RawPosting search = fetcher.parse(fixture, "India").postings().getFirst();

        assertThat(search.externalId()).isEqualTo("122710813273137862");
        assertThat(search.title()).isEqualTo("Software Engineer, Search");
        assertThat(search.location()).contains("Bengaluru, Karnataka, India");
        assertThat(search.url()).isEqualTo(
                "https://www.google.com/about/careers/applications/jobs/results/122710813273137862");
        assertThat(search.postedDate()).isAfter(LocalDate.of(2026, 1, 1));
    }

    @Test
    @DisplayName("the stated year is in the description, and it is not non-internship")
    void qualificationsCarryTheYears() throws Exception {
        RawPosting search = fetcher.parse(fixture, "India").postings().getFirst();

        assertThat(search.description())
                .contains("1 year of experience with software development")
                .contains("Responsibilities:")
                .doesNotContain("<li>");
        var years = new YearsExtractor().extract(search.description());
        assertThat(years.hasNonInternshipRequirement()).isFalse();
    }

    @Test
    @DisplayName("paging stops on an empty page rather than running to the limit")
    void stopsOnEmptyPage() throws Exception {
        PagingClient client = new PagingClient();
        FetchBatch batch = new GoogleCareersFetcher(client, new ObjectMapper()).fetch("India");

        assertThat(batch.postings()).hasSize(3);
        assertThat(batch.boardTotal()).isEqualTo(24);
        assertThat(client.urls).hasSize(2);
        assertThat(client.urls.getFirst()).contains("location=India").contains("target_level=EARLY");
    }

    @Test
    @DisplayName("a page without the results block is a format change, not an empty search")
    void missingBlockFails() {
        assertThatThrownBy(() -> fetcher.parse("<html><body>Sorry</body></html>", "India"))
                .isInstanceOf(FetchException.class)
                .hasMessageContaining("ds:1");
    }
}
