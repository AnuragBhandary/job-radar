package com.anuragbhandary.jobradar.fetch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.anuragbhandary.jobradar.config.AppProperties;
import com.anuragbhandary.jobradar.domain.Source;
import com.anuragbhandary.jobradar.filter.RealConfigAccess;
import com.anuragbhandary.jobradar.filter.TitleFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The fixtures are JPMorgan Chase's India search on 2026-10-01, cut to four of
 * its 323 requisitions, and the detail of one of them.
 */
class OracleHcmFetcherTest {

    private static final AppProperties CONFIG =
            new AppProperties(null, null, null, null, RealConfigAccess.screening(), null);

    private static final String TOKEN = "jpmc/jpmc.fa.oraclecloud.com/CX_1001/300000000289360";

    private static final class RecordingClient extends HttpFetchClient {
        final List<String> listed = new ArrayList<>();
        final List<String> detailed = new ArrayList<>();

        RecordingClient() {
            super(null, new AppProperties(null, null,
                    new AppProperties.Http("test", 0, 5, 1, null, null), null, null, null), null);
        }

        @Override
        public String get(String url, String fixtureName) {
            if (url.contains("recruitingCEJobRequisitionDetails")) {
                detailed.add(url);
                return FixtureSupport.load("oracle-jpmc-detail.json");
            }
            listed.add(url);
            return FixtureSupport.load("oracle-jpmc-list.json");
        }
    }

    private final RecordingClient client = new RecordingClient();
    private final OracleHcmFetcher fetcher = new OracleHcmFetcher(
            client, new ObjectMapper(), RealConfigAccess.targetPlaces(), new TitleFilter(CONFIG), StoredPostings.none());

    @Test
    void reportsSource() {
        assertThat(fetcher.source()).isEqualTo(Source.ORACLE_HCM);
    }

    @Test
    @DisplayName("the list maps id, title, location and posting date")
    void parsesList() throws Exception {
        OracleHcmFetcher.ListPage page = fetcher.parseList(
                FixtureSupport.load("oracle-jpmc-list.json"), TOKEN);

        assertThat(page.total()).isEqualTo(323);
        OracleHcmFetcher.Listed first = page.jobs().getFirst();
        assertThat(first.id()).isEqualTo("210774158");
        assertThat(first.title()).isEqualTo("Lead Software Engineer – Java");
        assertThat(first.location()).isEqualTo("Bengaluru, Karnataka, India");
        assertThat(first.posted()).isEqualTo(LocalDate.of(2026, 10, 1));
    }

    @Test
    @DisplayName("titles are screened on the list, before any detail request")
    void screensBeforeDetail() throws Exception {
        FetchBatch batch = fetcher.fetch(TOKEN);

        // Four requisitions: two "Lead" software roles and a "Senior Associate"
        // fail on title, and since 2026-10-01 so does the financial planning
        // analyst (a finance role with no software or data word). No detail is read.
        assertThat(client.detailed).isEmpty();
        assertThat(batch.postings()).isEmpty();
        assertThat(batch.boardTotal()).isEqualTo(323);
        // The list is shorter than a page, so it is read once.
        assertThat(client.listed).hasSize(1);
    }

    @Test
    @DisplayName("the detail's description becomes plain text, and the link is the candidate page")
    void mapsDetail() throws Exception {
        OracleHcmFetcher.Board board = OracleHcmFetcher.Board.parse(TOKEN);
        OracleHcmFetcher.Listed job = new OracleHcmFetcher.Listed(
                "210774158", "Lead Software Engineer – Java", "Bengaluru, Karnataka, India",
                LocalDate.of(2026, 10, 1));
        RawPosting posting = fetcher.parseDetail(
                FixtureSupport.load("oracle-jpmc-detail.json"), job, board);

        assertThat(posting.description()).isNotBlank().doesNotContain("<p>");
        assertThat(posting.url()).isEqualTo(
                "https://jpmc.fa.oraclecloud.com/hcmUI/CandidateExperience/en/sites/CX_1001/job/210774158");
    }

    @Test
    @DisplayName("an empty description falls back to the program and division text (Goldman Sachs)")
    void fallsBackToProgramText() throws Exception {
        OracleHcmFetcher.Board board = OracleHcmFetcher.Board.parse(TOKEN);
        OracleHcmFetcher.Listed job = new OracleHcmFetcher.Listed(
                "170173", "Summer Analyst", "London", LocalDate.of(2026, 10, 1));
        String body = """
                {"items":[{"Title":"Summer Analyst","ExternalDescriptionStr":"",
                 "CorporateDescriptionStr":"<p>Our Summer Analyst Program is a summer internship \
                for students pursuing a bachelors degree.</p>",
                 "OrganizationDescriptionStr":"<p>About the division</p>"}]}""";

        RawPosting posting = fetcher.parseDetail(body, job, board);

        assertThat(posting.description()).contains("students pursuing a bachelors degree")
                .contains("About the division").doesNotContain("<p>");
    }

    @Test
    @DisplayName("the detail URL is a valid URI: the quotes around the id are encoded")
    void detailUrlIsValid() throws Exception {
        String url = OracleHcmFetcher.Board.parse(TOKEN).detail("210774158");
        assertThat(URI.create(url).getHost()).isEqualTo("jpmc.fa.oraclecloud.com");
        assertThat(url).contains("Id=%22210774158%22");
    }

    @Test
    void rejectsMalformedToken() {
        assertThatThrownBy(() -> OracleHcmFetcher.Board.parse("jpmc/jpmc.fa.oraclecloud.com/CX_1001"))
                .isInstanceOf(FetchException.class);
    }
}
