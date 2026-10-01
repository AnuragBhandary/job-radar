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
 * The fixtures are trimmed copies of Microsoft's live sitemap and one detail
 * response, captured 2026-10-01: two junior-looking India software roles, a
 * senior and a principal one in India, and two in the United States.
 */
class EightfoldFetcherTest {

    private static final AppProperties CONFIG =
            new AppProperties(null, null, null, null, RealConfigAccess.screening(), null);

    private static final String TOKEN = "microsoft/apply.careers.microsoft.com/microsoft.com";

    /** Serves the sitemap, then the one saved detail for every job asked about. */
    private static class RecordingClient extends HttpFetchClient {
        final List<String> detailed = new ArrayList<>();

        RecordingClient() {
            super(null, new AppProperties(null, null,
                    new AppProperties.Http("test", 0, 5, 1, null, null), null, null, null), null);
        }

        @Override
        public String get(String url, String fixtureName) throws FetchException {
            if (url.contains("sitemap.xml")) {
                return FixtureSupport.load("eightfold-microsoft-sitemap.xml");
            }
            detailed.add(url);
            return FixtureSupport.load("eightfold-microsoft-detail.json");
        }
    }

    private final RecordingClient client = new RecordingClient();
    private final EightfoldFetcher fetcher = new EightfoldFetcher(
            client, new ObjectMapper(), RealConfigAccess.targetPlaces(), new TitleFilter(CONFIG), StoredPostings.none());

    @Test
    void reportsSource() {
        assertThat(fetcher.source()).isEqualTo(Source.EIGHTFOLD);
    }

    @Test
    @DisplayName("the sitemap's job URLs become id, link and slug text")
    void parsesSitemap() {
        List<EightfoldFetcher.Listed> jobs = EightfoldFetcher.parseSitemap(
                FixtureSupport.load("eightfold-microsoft-sitemap.xml"));

        // The careers home page is in the sitemap too, and is not a job.
        assertThat(jobs).hasSize(8);
        EightfoldFetcher.Listed first = jobs.getFirst();
        assertThat(first.id()).isEqualTo("1970393557007166");
        assertThat(first.slugText()).isEqualTo("software engineer ii india telangana hyderabad");
        assertThat(first.url()).endsWith("?domain=microsoft.com");
    }

    @Test
    @DisplayName("only India-or-target roles with an acceptable title cost a detail request")
    void screensSlugBeforeDetail() throws Exception {
        FetchBatch batch = fetcher.fetch(TOKEN);

        // Eight jobs listed. Three in the United States fail on geography, one of
        // them in Indiana, which must not read as India. The senior, principal and
        // "Software Engineer II/2" ones fail on title (Microsoft's II is a mid
        // level, unlike Google's). The India software engineering intern is left.
        assertThat(batch.boardTotal()).isEqualTo(8);
        assertThat(client.detailed).hasSize(1)
                .allMatch(url -> url.contains("position_id=1970393556911730"));
        assertThat(batch.postings()).hasSize(1);
    }

    @Test
    @DisplayName("a detail response maps to title, locations, plain text and the posted date")
    void mapsDetail() throws Exception {
        EightfoldFetcher.Listed job = new EightfoldFetcher.Listed("1970393556911730",
                "https://apply.careers.microsoft.com/careers/job/1970393556911730?domain=microsoft.com",
                "software engineering intern india");
        RawPosting posting = fetcher.parseDetail(
                FixtureSupport.load("eightfold-microsoft-detail.json"), job);

        assertThat(posting.externalId()).isEqualTo("1970393556911730");
        assertThat(posting.title()).isEqualTo("Software Engineering INTERN");
        assertThat(posting.location()).contains("India");
        assertThat(posting.description()).contains("Software Engineering Intern")
                .doesNotContain("<p");
        assertThat(posting.postedDate()).isAfter(LocalDate.of(2026, 1, 1));
        assertThat(posting.url()).isEqualTo(job.url());
    }

    @Test
    @DisplayName("an empty sitemap fails the board instead of closing every posting")
    void emptySitemapFails() {
        RecordingClient empty = new RecordingClient() {
            @Override
            public String get(String url, String fixtureName) {
                return "<?xml version='1.0'?><urlset></urlset>";
            }
        };
        EightfoldFetcher f = new EightfoldFetcher(
                empty, new ObjectMapper(), RealConfigAccess.targetPlaces(), new TitleFilter(CONFIG), StoredPostings.none());

        assertThatThrownBy(() -> f.fetch(TOKEN)).isInstanceOf(FetchException.class);
    }

    @Test
    @DisplayName("a run where most detail requests fail is a failed fetch")
    void mostlyFailedDetailsFail() {
        RecordingClient refusing = new RecordingClient() {
            @Override
            public String get(String url, String fixtureName) throws FetchException {
                if (url.contains("sitemap.xml")) {
                    // One junior India role, so exactly one detail request.
                    return """
                            <urlset><url><loc>https://apply.careers.microsoft.com/careers/job/1-software-engineer-india-karnataka-bangalore?domain=microsoft.com</loc></url></urlset>""";
                }
                throw new FetchException("HTTP 429 from " + url);
            }
        };
        EightfoldFetcher f = new EightfoldFetcher(
                refusing, new ObjectMapper(), RealConfigAccess.targetPlaces(), new TitleFilter(CONFIG), StoredPostings.none());

        assertThatThrownBy(() -> f.fetch(TOKEN))
                .isInstanceOf(FetchException.class)
                .hasMessageContaining("1 of 1 detail requests failed");
    }

    @Test
    void rejectsMalformedToken() {
        assertThatThrownBy(() -> fetcher.fetch("microsoft"))
                .isInstanceOf(FetchException.class);
    }

    /** NTT DATA's slug page answered 404 for a listed job on 2026-10-01. */
    @Test
    void linksByPositionId() {
        assertThat(new EightfoldFetcher.Board("nttdata", "nttdata.eightfold.ai", "nttdata.com")
                .publicUrl("563327934537258"))
                .isEqualTo("https://nttdata.eightfold.ai/careers?pid=563327934537258&domain=nttdata.com");
    }
}
