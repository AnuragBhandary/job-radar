package com.anuragbhandary.jobradar.fetch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.anuragbhandary.jobradar.config.AppProperties;
import com.anuragbhandary.jobradar.domain.Posting;
import com.anuragbhandary.jobradar.domain.PostingStatus;
import com.anuragbhandary.jobradar.domain.Source;
import com.anuragbhandary.jobradar.filter.RealConfigAccess;
import com.anuragbhandary.jobradar.filter.TitleFilter;
import com.anuragbhandary.jobradar.repo.PostingRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Fixtures captured 2026-10-01: an Intuit job page (JSON-LD), an EY page (full
 * microdata), a Standard Chartered page (title and description only), and seven
 * entries of EY's sitemap. Pages are trimmed of scripts and styles.
 */
class SitemapFetcherTest {

    private static final AppProperties CONFIG =
            new AppProperties(null, null, null, null, RealConfigAccess.screening(), null);

    private static final String TOKEN = "ey/careers.ey.com/sitemap.xml";

    /** Serves the EY sitemap, and pages through a function of the URL. */
    private static final class Client extends HttpFetchClient {
        final List<String> pages = new ArrayList<>();
        private final Function<String, HttpResult> page;

        Client(Function<String, HttpResult> page) {
            super(null, new AppProperties(null, null,
                    new AppProperties.Http("test", 0, 5, 1, null, null), null, null, null), null);
            this.page = page;
        }

        @Override
        public String get(String url, String fixtureName) {
            return FixtureSupport.load("sitemap-ey.xml");
        }

        @Override
        public HttpResult getRaw(String url, String fixtureName) {
            pages.add(url);
            return page.apply(url);
        }
    }

    private static SitemapFetcher fetcher(HttpFetchClient http, PostingRepository repo) {
        return new SitemapFetcher(http, new ObjectMapper(), RealConfigAccess.targetPlaces(),
                new TitleFilter(CONFIG), repo);
    }

    private static PostingRepository emptyRepo() {
        PostingRepository repo = mock(PostingRepository.class);
        when(repo.findBySourceAndBoardToken(any(), anyString())).thenReturn(List.of());
        return repo;
    }

    private static SitemapFetcher.Listed job(String url, String slug) {
        return new SitemapFetcher.Listed("1440987933", url, slug, null);
    }

    @Test
    void reportsSource() {
        assertThat(fetcher(null, emptyRepo()).source()).isEqualTo(Source.SITEMAP);
    }

    @Test
    @DisplayName("sitemap entries become job pages with an id and the slug as words")
    void readsSitemap() {
        List<SitemapFetcher.Listed> jobs = SitemapFetcher.entries(FixtureSupport.load("sitemap-ey.xml"))
                .stream().map(SitemapFetcher::listed).filter(java.util.Objects::nonNull).toList();

        assertThat(jobs).isNotEmpty();
        SitemapFetcher.Listed first = jobs.getFirst();
        assertThat(first.id()).matches("\\d{6,}");
        assertThat(first.slugText()).doesNotContain("-").doesNotContain("/");
        assertThat(first.url()).startsWith("https://careers.ey.com/");
    }

    @Test
    @DisplayName("a listing page is not a job page, and a long path is hashed to fit")
    void listingPagesAndIds() {
        assertThat(SitemapFetcher.listed(new String[] {"https://jobs.sap.com/en/jobs/", null})).isNull();
        assertThat(SitemapFetcher.listed(new String[] {"https://x.com/jobs/search", null})).isNull();
        assertThat(SitemapFetcher.idOf("/job/bengaluru/some-title/27595/101380250832"))
                .isEqualTo("101380250832");
        assertThat(SitemapFetcher.idOf("/careers/job/no-number-here")).hasSize(40);
    }

    @Test
    @DisplayName("JSON-LD gives the title, the place and an unpadded date")
    void readsJsonLd() {
        RawPosting p = fetcher(null, emptyRepo()).parsePage(
                FixtureSupport.load("sitemap-intuit-job.html"),
                job("https://jobs.intuit.com/job/bengaluru/x/27595/101380250832", "bengaluru x"));

        assertThat(p.title()).startsWith("Come join Intuit as a Senior Staff Software Engineer");
        assertThat(p.location()).contains("India");
        // Intuit writes "2026-10-1".
        assertThat(p.postedDate()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(p.description()).isNotBlank().doesNotContain("<p>");
    }

    @Test
    @DisplayName("full microdata gives the title, the address and the description")
    void readsMicrodata() {
        RawPosting p = fetcher(null, emptyRepo()).parsePage(
                FixtureSupport.load("sitemap-ey-job.html"),
                job("https://careers.ey.com/ey/job/Mumbai-Manager/1440987933/", "Mumbai Manager"));

        assertThat(p).isNotNull();
        assertThat(p.title()).contains("Manager");
        assertThat(p.location()).contains("Mumbai");
        assertThat(p.description()).isNotBlank().doesNotContain("<");
    }

    @Test
    @DisplayName("with only title and description marked, the place comes from the URL")
    void fallsBackToSlug() {
        String slug = "Mumbai Apprentice Global Support Functions India 2026";
        RawPosting p = fetcher(null, emptyRepo()).parsePage(
                FixtureSupport.load("sitemap-stanchart-job.html"),
                job("https://jobs.standardchartered.com/job/x/1372812457/", slug));

        assertThat(p.title()).containsIgnoringCase("Apprentice");
        assertThat(p.location()).isEqualTo(slug);
        assertThat(p.description()).isNotBlank();
    }

    @Test
    @DisplayName("a closed page (404) is skipped, not counted as a failure")
    void notFoundIsClosed() throws Exception {
        Client client = new Client(url -> new HttpFetchClient.HttpResult(404, ""));
        FetchBatch batch = fetcher(client, emptyRepo()).fetch(TOKEN);

        assertThat(client.pages).isNotEmpty();
        assertThat(batch.postings()).isEmpty();
    }

    @Test
    @DisplayName("pages that fail for other reasons fail the board")
    void failuresFailTheBoard() {
        Client client = new Client(url -> new HttpFetchClient.HttpResult(500, ""));
        assertThatThrownBy(() -> fetcher(client, emptyRepo()).fetch(TOKEN))
                .isInstanceOf(FetchException.class);
    }

    @Test
    @DisplayName("a page already stored is returned from the database, not read again")
    void reusesStoredPages() throws Exception {
        Client first = new Client(url -> new HttpFetchClient.HttpResult(200,
                FixtureSupport.load("sitemap-ey-job.html")));
        FetchBatch batch = fetcher(first, emptyRepo()).fetch(TOKEN);
        assertThat(batch.postings()).isNotEmpty();

        PostingRepository repo = mock(PostingRepository.class);
        List<Posting> stored = new ArrayList<>();
        for (RawPosting raw : batch.postings()) {
            Posting p = mock(Posting.class);
            when(p.getExternalId()).thenReturn(raw.externalId());
            when(p.getTitle()).thenReturn(raw.title());
            when(p.getLocation()).thenReturn(raw.location());
            when(p.getDescriptionText()).thenReturn(raw.description());
            when(p.getUrl()).thenReturn(raw.url());
            when(p.getStatus()).thenReturn(PostingStatus.SEEN);
            stored.add(p);
        }
        when(repo.findBySourceAndBoardToken(Source.SITEMAP, TOKEN)).thenReturn(stored);

        Client second = new Client(url -> new HttpFetchClient.HttpResult(200,
                FixtureSupport.load("sitemap-ey-job.html")));
        FetchBatch again = fetcher(second, repo).fetch(TOKEN);

        assertThat(again.postings()).hasSameSizeAs(batch.postings());
        assertThat(second.pages).isEmpty();
    }

    @Test
    void rejectsMalformedToken() {
        assertThatThrownBy(() -> SitemapFetcher.Board.parse("ey/careers.ey.com"))
                .isInstanceOf(FetchException.class);
    }
}
