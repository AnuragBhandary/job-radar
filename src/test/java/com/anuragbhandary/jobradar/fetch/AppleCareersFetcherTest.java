package com.anuragbhandary.jobradar.fetch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.anuragbhandary.jobradar.config.AppProperties;
import com.anuragbhandary.jobradar.domain.Source;
import com.anuragbhandary.jobradar.filter.RealConfigAccess;
import com.anuragbhandary.jobradar.filter.TitleFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The fixtures are Apple's Ireland search on 2026-10-07, cut to four of its 37
 * jobs, and the page of one Hyderabad requisition, both cut to the data block.
 */
class AppleCareersFetcherTest {

    private static final AppProperties CONFIG =
            new AppProperties(null, null, null, null, RealConfigAccess.screening(), null);

    private static final String TOKEN = "en-ie/ireland-IRL";

    private static final class RecordingClient extends HttpFetchClient {
        final List<String> searched = new ArrayList<>();
        final List<String> detailed = new ArrayList<>();

        RecordingClient() {
            super(null, new AppProperties(null, null,
                    new AppProperties.Http("test", 0, 5, 1, null, null), null, null, null), null);
        }

        @Override
        public String get(String url, String fixtureName) {
            if (url.contains("/details/")) {
                detailed.add(url);
                return FixtureSupport.load("apple-details.html");
            }
            searched.add(url);
            return FixtureSupport.load("apple-search.html");
        }
    }

    private final RecordingClient client = new RecordingClient();
    private final AppleCareersFetcher fetcher = new AppleCareersFetcher(
            client, new ObjectMapper(), RealConfigAccess.targetPlaces(), new TitleFilter(CONFIG),
            StoredPostings.none());

    @Test
    void reportsSource() {
        assertThat(fetcher.source()).isEqualTo(Source.APPLE);
    }

    @Test
    @DisplayName("the search maps id, title, location and date out of the hydration data")
    void parsesSearch() throws Exception {
        AppleCareersFetcher.Page page = fetcher.parseSearch(
                FixtureSupport.load("apple-search.html"), TOKEN);

        assertThat(page.total()).isEqualTo(37);
        assertThat(page.jobs()).hasSize(4);
        AppleCareersFetcher.Listed first = page.jobs().getFirst();
        assertThat(first.id()).isEqualTo("200668786-1418");
        assertThat(first.title()).isEqualTo("Full Stack Engineer, Employee Engagement Engineering");
        assertThat(first.location()).isNotBlank();
        assertThat(first.posted()).isNotNull();
    }

    @Test
    @DisplayName("a job's page gives its qualifications under headings the years extractor knows")
    void parsesDetails() throws Exception {
        AppleCareersFetcher.Listed job = new AppleCareersFetcher.Listed(
                "200686075-1052", "Software Engineer - Digital Asset Management",
                "Hyderabad, India", LocalDate.of(2026, 10, 6));
        RawPosting posting = fetcher.parseDetails(FixtureSupport.load("apple-details.html"), job,
                AppleCareersFetcher.Board.parse("en-in/india-INDC"));

        assertThat(posting.description())
                .startsWith("Minimum Qualifications: 3+ years of software engineering experience")
                .contains("Preferred Qualifications:");
        assertThat(posting.url()).isEqualTo("https://jobs.apple.com/en-in/details/200686075-1052");
    }

    @Test
    @DisplayName("titles are screened on the search, before any job page is read")
    void screensBeforeDetails() throws Exception {
        FetchBatch batch = fetcher.fetch(TOKEN);

        // Two full-stack titles, an analog design role and a senior one: all four
        // fail on the title and no job page is fetched.
        assertThat(batch.boardTotal()).isEqualTo(37);
        assertThat(client.detailed).hasSizeLessThanOrEqualTo(batch.postings().size());
        assertThat(client.searched.getFirst())
                .isEqualTo("https://jobs.apple.com/en-ie/search?location=ireland-IRL&page=1");
    }

    @Test
    void rejectsMalformedToken() {
        assertThatThrownBy(() -> AppleCareersFetcher.Board.parse("india-INDC"))
                .isInstanceOf(FetchException.class);
    }
}
