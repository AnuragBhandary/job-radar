package com.anuragbhandary.jobradar.fetch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.anuragbhandary.jobradar.config.AppProperties;
import com.anuragbhandary.jobradar.domain.Source;
import com.anuragbhandary.jobradar.filter.RealConfigAccess;
import com.anuragbhandary.jobradar.filter.TitleFilter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The fixtures are trimmed copies of a live NXP fetch: four of the twenty
 * postings on page one, and the detail response for the only one that survives
 * screening.
 */
class WorkdayFetcherTest {

    private static final AppProperties CONFIG =
            new AppProperties(null, null, null, null, RealConfigAccess.screening(), null);

    /** Records what was asked for, so the test can prove what was skipped. */
    static final class RecordingClient extends HttpFetchClient {
        private final List<String> listed = new ArrayList<>();
        final List<String> detailed = new ArrayList<>();

        RecordingClient() {
            super(null, new AppProperties(null, null,
                    new AppProperties.Http("test", 0, 5, 1, null, null), null, null, null), null);
        }

        @Override
        public String post(String url, String jsonBody, String fixtureName) {
            listed.add(jsonBody);
            return FixtureSupport.load("workday-nxp-list.json");
        }

        @Override
        public String get(String url, String fixtureName) {
            detailed.add(url);
            return FixtureSupport.load("workday-nxp-detail.json");
        }
    }

    private final RecordingClient client = new RecordingClient();
    private final WorkdayFetcher fetcher = new WorkdayFetcher(
            client, new ObjectMapper(), RealConfigAccess.targetPlaces(), new TitleFilter(CONFIG), StoredPostings.none());

    @Test
    void reportsSource() {
        assertThat(fetcher.source()).isEqualTo(Source.WORKDAY);
    }

    @Test
    @DisplayName("only postings passing geography and title get a detail request")
    void filtersBeforeFetchingDetails() throws Exception {
        // Four postings in, one survivor: a US location, a "Senior" title and an
        // "Internship" title are all rejected before costing a request.
        //
        // The survivor is a retitled capture. NXP's real posting was "Junior
        // System Engineer for Automotive Innovations", which the title filter now
        // rejects as hardware - NXP is a semiconductor company. The fixture keeps
        // the response shape, which is what this test is about.
        FetchBatch batch = fetcher.fetch("nxp/wd3/careers");

        assertThat(batch.postings()).hasSize(1);
        assertThat(client.detailed).hasSize(1);
    }

    @Test
    @DisplayName("the requisition id and real start date come from the detail response")
    void mapsDetailFields() throws Exception {
        RawPosting posting = fetcher.fetch("nxp/wd3/careers").postings().getFirst();

        // The list only offers "Posted 30+ Days Ago", which stops being a date at
        // thirty. The detail carries the actual one.
        assertThat(posting.postedDate()).isEqualTo(LocalDate.of(2026, 9, 4));
        assertThat(posting.externalId()).isEqualTo("R-10064331");
        assertThat(posting.location()).isEqualTo("Eindhoven");
        assertThat(posting.description())
                .doesNotContain("<p>", "<span>")
                .contains("Junior Systems Engineer");
    }

    @Test
    @DisplayName("board health reports the board's own size, not what survived our filters")
    void reportsBoardTotalNotShortlistSize() throws Exception {
        // 760 is what NXP says it has. Recording 1 would make a large board that
        // happens to suit nobody look dead.
        assertThat(fetcher.fetch("nxp/wd3/careers").boardTotal()).isEqualTo(760);
    }

    @Test
    @DisplayName("a token that is not tenant/wdN/site fails before any request is made")
    void rejectsMalformedToken() {
        assertThatThrownBy(() -> fetcher.fetch("nxp"))
                .isInstanceOf(FetchException.class)
                .hasMessageContaining("tenant/wdN/site");
        assertThat(client.listed).isEmpty();
    }

    @Test
    @DisplayName("a fourth token part becomes Workday's search, so a global site can be read for India")
    void searchPartIsSent() throws Exception {
        fetcher.fetch("mastercard/wd1/CorporateCareers/india");
        assertThat(client.listed.get(0)).contains("\"searchText\":\"india\"");
    }

    @Test
    @DisplayName("a three-part token still sends an empty search")
    void noSearchPartSendsEmpty() throws Exception {
        fetcher.fetch("nxp/wd3/careers");
        assertThat(client.listed.get(0)).contains("\"searchText\":\"\"");
    }

    @Test
    @DisplayName("a search that would need JSON escaping is refused")
    void unsafeSearchIsRefused() {
        assertThatThrownBy(() -> fetcher.fetch("mastercard/wd1/CorporateCareers/in\"dia"))
                .isInstanceOf(FetchException.class);
        assertThat(client.listed).isEmpty();
    }

    /** Accenture (2026-10-11): the unfiltered first page carries the facets, a filtered one the jobs. */
    static final class AccentureClient extends HttpFetchClient {
        final List<String> listed = new ArrayList<>();
        final List<String> detailed = new ArrayList<>();

        AccentureClient() {
            super(null, new AppProperties(null, null,
                    new AppProperties.Http("test", 0, 5, 1, null, null), null, null, null), null);
        }

        @Override
        public String post(String url, String jsonBody, String fixtureName) {
            listed.add(jsonBody);
            return jsonBody.contains("\"appliedFacets\":{}")
                    ? FixtureSupport.load("workday-accenture-facets.json")
                    : FixtureSupport.load("workday-accenture-list.json");
        }

        @Override
        public String get(String url, String fixtureName) {
            detailed.add(url);
            return FixtureSupport.load("workday-nxp-detail.json");
        }
    }

    @Test
    @DisplayName("a list with no locationsText takes the place from the bullets after the requisition id")
    void locationFromBullets() throws Exception {
        // Accenture's list leaves locationsText out, which failed every one of its
        // postings on geography until 2026-10-11.
        JsonNode list = new ObjectMapper().readTree(FixtureSupport.load("workday-accenture-list.json"));
        JsonNode first = list.path("jobPostings").get(0);
        assertThat(first.has("locationsText")).isFalse();
        assertThat(WorkdayFetcher.location(first)).isEqualTo("Bengaluru");
    }

    @Test
    @DisplayName("an @ token is read as facet labels, looked up in the site's own facets")
    void facetLabelsBecomeIds() throws Exception {
        AccentureClient accenture = new AccentureClient();
        WorkdayFetcher reader = new WorkdayFetcher(accenture, new ObjectMapper(),
                RealConfigAccess.targetPlaces(), new TitleFilter(CONFIG), StoredPostings.none());

        FetchBatch batch = reader.fetch("accenture/wd103/AccentureCareers/@India+Software Engineering");

        String filtered = accenture.listed.get(1);
        assertThat(filtered)
                .contains("\"locationCountry\":[\"c4f78be1a8f14da0ab49ce1162348a5e\"]")
                .contains("\"jobFamilyGroup\":[\"bb69a804fb120130e52200ed1301d275\"]")
                .contains("\"searchText\":\"\"");
        // The Bengaluru and Chennai software roles now reach a detail request.
        assertThat(accenture.detailed).isNotEmpty();
        assertThat(batch.boardTotal()).isEqualTo(WorkdayFetcher.RESULT_CAP);
    }

    @Test
    @DisplayName("a facet label the site does not have fails the board instead of reading all of it")
    void unknownFacetLabelFails() {
        AccentureClient accenture = new AccentureClient();
        WorkdayFetcher reader = new WorkdayFetcher(accenture, new ObjectMapper(),
                RealConfigAccess.targetPlaces(), new TitleFilter(CONFIG), StoredPostings.none());

        assertThatThrownBy(() -> reader.fetch("accenture/wd103/AccentureCareers/@Atlantis"))
                .isInstanceOf(FetchException.class)
                .hasMessageContaining("Atlantis");
    }

    @Test
    @DisplayName("on a capped list, stored postings past the end are kept while recent, not closed")
    void keepsRecentPostingsPastTheCap() {
        LocalDate today = LocalDate.of(2026, 10, 11);
        RawPosting listed = posting("A", today.minusDays(1));
        RawPosting slidOff = posting("B", today.minusDays(20));
        RawPosting old = posting("C", today.minusDays(WorkdayFetcher.CAPPED_KEEP_DAYS + 1));
        RawPosting undated = posting("D", null);
        StoredPostings.Known known = new StoredPostings.Known(
                Map.of("A", listed, "B", slidOff, "C", old, "D", undated), today.toEpochDay());

        List<RawPosting> out = new ArrayList<>(List.of(listed));
        Set<String> ids = new HashSet<>(Set.of("A"));
        int kept = WorkdayFetcher.keepPastTheCap(known, ids, out, today);

        assertThat(kept).isEqualTo(1);
        assertThat(out).extracting(RawPosting::externalId).containsExactly("A", "B");
    }

    private static RawPosting posting(String id, LocalDate posted) {
        return new RawPosting(id, "Software Engineer", "Bengaluru", "text", "https://x/" + id, posted);
    }
}
