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
 * The fixtures are trimmed copies of the live sites on 2026-10-11: three rows of
 * Siemens' India search and its "Next" link, two rows of Deloitte's US-India
 * list, Siemens' field list for one job, and Deloitte's JSON-LD block for one.
 */
class AvatureFetcherTest {

    private static final AppProperties CONFIG =
            new AppProperties(null, null, null, null, RealConfigAccess.screening(), null);

    /** Answers the first list page from a fixture and every later page as empty. */
    static final class SiteClient extends HttpFetchClient {
        private final String list;
        private final String job;
        final List<String> requested = new ArrayList<>();

        SiteClient(String list, String job) {
            super(null, new AppProperties(null, null,
                    new AppProperties.Http("test", 0, 5, 1, null, null), null, null, null), null);
            this.list = list;
            this.job = job;
        }

        @Override
        public String get(String url, String fixtureName) {
            requested.add(url);
            return requested.size() == 1 ? FixtureSupport.load(list) : "<html></html>";
        }

        @Override
        public HttpResult getRaw(String url, String fixtureName) {
            requested.add(url);
            return new HttpResult(200, FixtureSupport.load(job));
        }
    }

    private static AvatureFetcher fetcher(HttpFetchClient client) {
        return new AvatureFetcher(client, new ObjectMapper(), RealConfigAccess.targetPlaces(),
                new TitleFilter(CONFIG), StoredPostings.none());
    }

    @Test
    void reportsSource() {
        assertThat(fetcher(new SiteClient(null, null)).source()).isEqualTo(Source.AVATURE);
    }

    @Test
    @DisplayName("a row gives the id, the job link, the title and the place without the job id")
    void readsRows() {
        List<AvatureFetcher.Listed> rows =
                AvatureFetcher.rows(FixtureSupport.load("avature-siemens-list.html"));

        assertThat(rows).hasSize(3);
        AvatureFetcher.Listed thane = rows.get(2);
        assertThat(thane.id()).isEqualTo("524298");
        assertThat(thane.url()).isEqualTo("https://jobs.siemens.com/en_US/externaljobs/JobDetail/524298");
        assertThat(thane.title()).isEqualTo("Regional Sales Manager - Corporate Business- Mumbai");
        assertThat(thane.place()).isEqualTo("Thane, Maharashtra, India | Sales");
    }

    @Test
    @DisplayName("Deloitte's rows say 'India Offices' even when the place is 'Multiple Locations'")
    void deloittePlaceNamesIndia() {
        List<AvatureFetcher.Listed> rows =
                AvatureFetcher.rows(FixtureSupport.load("avature-deloitte-list.html"));

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).id()).isEqualTo("370617");
        assertThat(rows.get(0).place()).contains("India Offices").contains("Multiple Locations");
    }

    @Test
    @DisplayName("the Next link is found on the link itself (Deloitte) and on the item around it (Siemens)")
    void findsNextPage() {
        assertThat(AvatureFetcher.nextPage(FixtureSupport.load("avature-deloitte-list.html")))
                .isEqualTo("https://usijobs.deloitte.com/en_US/careersUSI/SearchJobs/?jobRecordsPerPage=10&jobOffset=10");
        assertThat(AvatureFetcher.nextPage(FixtureSupport.load("avature-siemens-list.html")))
                .isEqualTo("https://jobs.siemens.com/en_US/externaljobs/SearchJobs/India/?folderRecordsPerPage=6&folderOffset=6");
        assertThat(AvatureFetcher.nextPage("<html><body>no pages</body></html>")).isNull();
    }

    @Test
    @DisplayName("a Siemens job page is read from its labelled fields")
    void readsSiemensFields() {
        AvatureFetcher.Listed row = new AvatureFetcher.Listed("523849",
                "https://jobs.siemens.com/en_US/externaljobs/JobDetail/523849", "Personal Assistant", "Pune");

        RawPosting posting = fetcher(new SiteClient(null, null))
                .parseDetail(FixtureSupport.load("avature-siemens-job.html"), row);

        assertThat(posting.location()).isEqualTo("Pune, Maharashtra, India");
        assertThat(posting.postedDate()).isEqualTo(LocalDate.of(2026, 10, 6));
        assertThat(posting.description())
                .startsWith("Experience level: Experienced Professional")
                .contains("Dear Aspirant")
                .doesNotContain("<p>")
                // The description is the last field; nothing after its article.
                .doesNotContain("Siemens footer");
    }

    @Test
    @DisplayName("a Deloitte job page is read from JSON-LD, with the list's place since the block has none")
    void readsDeloitteJsonLd() {
        AvatureFetcher.Listed row = new AvatureFetcher.Listed("368017",
                "https://usijobs.deloitte.com/en_US/careersUSI/JobDetail/x/368017", "Cyber Security Architect",
                "Deloitte I Deloitte US – India Offices | Multiple Locations");

        RawPosting posting = fetcher(new SiteClient(null, null))
                .parseDetail(FixtureSupport.load("avature-deloitte-job.html"), row);

        assertThat(posting.location()).isEqualTo(row.place());
        assertThat(posting.postedDate()).isEqualTo(LocalDate.of(2026, 9, 23));
        assertThat(posting.description()).isNotBlank().doesNotContain("<div>");
    }

    @Test
    @DisplayName("the crawl follows Next until a page has no rows")
    void followsPages() throws Exception {
        SiteClient client = new SiteClient("avature-deloitte-list.html", "avature-deloitte-job.html");

        FetchBatch batch = fetcher(client).fetch("deloitte/usijobs.deloitte.com/en_US/careersUSI");

        assertThat(client.requested.get(0)).isEqualTo("https://usijobs.deloitte.com/en_US/careersUSI/SearchJobs/");
        assertThat(client.requested.get(1)).endsWith("jobOffset=10");
        assertThat(batch.boardTotal()).isEqualTo(2);
    }

    @Test
    @DisplayName("a token without lang and portal fails before any request")
    void rejectsMalformedToken() {
        SiteClient client = new SiteClient(null, null);
        assertThatThrownBy(() -> fetcher(client).fetch("siemens/jobs.siemens.com"))
                .isInstanceOf(FetchException.class)
                .hasMessageContaining("name/host/lang/portal");
        assertThat(client.requested).isEmpty();
    }
}
