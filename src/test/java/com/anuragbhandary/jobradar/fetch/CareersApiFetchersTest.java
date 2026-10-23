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

    @Nested
    class Jibe {

        private final JibeFetcher fetcher = new JibeFetcher(null, json);

        @Test
        void reportsSource() {
            assertThat(fetcher.source()).isEqualTo(Source.JIBE);
        }

        @Test
        @DisplayName("the canonical page is the URL, not iCIMS's login screen")
        void mapsAJob() throws Exception {
            RawPosting first = fetcher.parse(FixtureSupport.load("jibe-amd-india.json")).getFirst();
            assertThat(first.externalId()).isEqualTo("89389");
            assertThat(first.title()).isEqualTo("SOC Design verification engineer");
            assertThat(first.location()).isEqualTo("Hyderabad, India");
            assertThat(first.url()).isEqualTo("https://careers.amd.com/jobs/89389?lang=en-us");
            assertThat(first.postedDate()).isEqualTo(LocalDate.of(2026, 9, 8));
            assertThat(first.description()).contains("THE ROLE").doesNotContain("<p>");
        }

        @Test
        @DisplayName("the token names the site and the country to search")
        void parsesTheToken() throws Exception {
            var board = JibeFetcher.Board.parse("booking/jobs.booking.com/United Kingdom");
            assertThat(board.page(2))
                    .isEqualTo("https://jobs.booking.com/api/jobs?location=United+Kingdom&page=2&limit=100");
            assertThatThrownBy(() -> JibeFetcher.Board.parse("amd/careers.amd.com"))
                    .isInstanceOf(FetchException.class);
        }
    }

    @Nested
    class DeShaw {

        private final DeShawIndiaFetcher fetcher = new DeShawIndiaFetcher(null, json, null, StoredPostings.none());

        @Test
        void reportsSource() {
            assertThat(fetcher.source()).isEqualTo(Source.DESHAW);
        }

        @Test
        @DisplayName("only /careers/<slug>-<id> pages are jobs; culture and FAQ pages are not")
        void listsJobPagesOnly() {
            var listed = JsonLdSitemapFetcher.listed(FixtureSupport.load("deshaw-sitemap.xml"),
                    fetcher.jobUrl(DeShawIndiaFetcher.BOARD));
            assertThat(listed).singleElement().satisfies(job -> {
                assertThat(job.id()).isEqualTo("6941");
                assertThat(job.slugText()).isEqualTo("software engineer application engineering senior member");
            });
        }

        @Test
        @DisplayName("the JSON-LD gives the job, and the office named in the text gives the place")
        void readsTheJobPage() {
            var job = new JsonLdSitemapFetcher.Listed("6941",
                    "https://www.deshawindia.com/careers/software-engineer-application-engineering-senior-member-6941",
                    "software engineer application engineering senior member");
            RawPosting posting = fetcher.parsePage(FixtureSupport.load("deshaw-job.html"), job);
            assertThat(posting.title()).isEqualTo("Software Engineer (Application Engineering) - Senior Member");
            assertThat(posting.location()).startsWith("Hyderabad, India");
            assertThat(posting.description()).contains("C# .NET Developer");
            assertThat(posting.postedDate()).isNull();
        }

        @Test
        void fallsBackToIndia() {
            assertThat(DeShawIndiaFetcher.offices("no office named")).isEqualTo("India");
        }
    }

    @Nested
    class RippleHire {

        private final RippleHireFetcher fetcher = new RippleHireFetcher(null, json, null, StoredPostings.none());

        @Test
        void reportsSource() {
            assertThat(fetcher.source()).isEqualTo(Source.RIPPLEHIRE);
        }

        @Test
        @DisplayName("the list gives the city with the country, and the stated minimum")
        void readsTheList() throws Exception {
            var listed = fetcher.parseList(FixtureSupport.load("ripplehire-ltm-list.json"));
            assertThat(listed).hasSize(2);
            var first = listed.getFirst();
            assertThat(first.id()).isEqualTo("903856");
            assertThat(first.place()).isEqualTo("Bengaluru, India");
            assertThat(first.experience()).isEqualTo("8 - 12 Years");
            assertThat(first.minYears()).isEqualTo(8);
            // "0 - 0 Years" is no statement at all, not an opening for freshers.
            assertThat(listed.get(1).minYears()).isNull();
            assertThat(listed.get(1).experience()).isEmpty();
        }

        @Test
        @DisplayName("the detail's HTML job text becomes plain text")
        void readsTheDetail() throws Exception {
            assertThat(fetcher.parseDetail(FixtureSupport.load("ripplehire-ltm-detail.json")))
                    .isNotBlank().doesNotContain("<p>");
        }

        @Test
        @DisplayName("list and job links carry the company's careers token")
        void buildsUrls() throws Exception {
            var board = RippleHireFetcher.Board.parse("ltm/ltimindtree.ripplehire.com/abc123");
            assertThat(board.list(2)).startsWith("https://ltimindtree.ripplehire.com/candidate/candidatejobsearch?")
                    .contains("%22page%22%3A2").contains("abc123");
            assertThat(board.page("903856")).endsWith("#detail/job/903856");
        }
    }

    @Nested
    class Spire2Grow {

        private final Spire2GrowFetcher fetcher = new Spire2GrowFetcher(null, json);

        @Test
        void reportsSource() {
            assertThat(fetcher.source()).isEqualTo(Source.SPIRE2GROW);
        }

        @Test
        @DisplayName("the display id makes the page link, and the months become stated years")
        void mapsARequisition() throws Exception {
            List<RawPosting> postings = fetcher.parse(FixtureSupport.load("spire2grow-myntra.json"), "jobs.myntra.com");
            assertThat(postings).hasSize(2);
            RawPosting first = postings.getFirst();
            assertThat(first.externalId()).isEqualTo("3787207691");
            assertThat(first.url()).isEqualTo("https://jobs.myntra.com/jobs/3787207691");
            assertThat(first.location()).isEqualTo("Bangalore, Karnataka, India");
            assertThat(first.description()).startsWith("Experience: 4 - 7 years\n\n").doesNotContain("<p>");
            assertThat(first.postedDate()).isNotNull();
        }

        @Test
        void rejectsATokenWithoutAHost() {
            assertThatThrownBy(() -> Spire2GrowFetcher.Board.parse("myntra")).isInstanceOf(FetchException.class);
        }
    }

    @Nested
    class ZohoRecruit {

        private final ZohoRecruitFetcher fetcher = new ZohoRecruitFetcher(null);

        @Test
        void reportsSource() {
            assertThat(fetcher.source()).isEqualTo(Source.ZOHO_RECRUIT);
        }

        @Test
        @DisplayName("each item gives title, the long id in its link, and the Location line")
        void readsTheFeed() throws Exception {
            List<RawPosting> postings = fetcher.parse(FixtureSupport.load("zoho-recruit-careers.xml"));
            assertThat(postings).hasSize(2);
            RawPosting first = postings.getFirst();
            assertThat(first.title()).isEqualTo("Software Developers");
            assertThat(first.externalId()).isEqualTo("193779000024446019");
            assertThat(first.location()).endsWith("India");
            assertThat(first.url()).doesNotContain("source=RSS");
            assertThat(first.description()).contains("low-latency").doesNotContain("<span");
            // "Content&#x2f;Technical Writers": entities in titles are decoded.
            assertThat(postings.get(1).title()).isEqualTo("Content/Technical Writers");
        }

        @Test
        void rejectsSomethingElse() {
            assertThatThrownBy(() -> fetcher.parse("<html></html>")).isInstanceOf(FetchException.class);
        }
    }

    @Nested
    class SocieteGenerale {

        private final SocieteGeneraleFetcher fetcher =
                new SocieteGeneraleFetcher(null, json, null, StoredPostings.none());

        @Test
        void reportsSource() {
            assertThat(fetcher.source()).isEqualTo(Source.SOCGEN);
        }

        @Test
        @DisplayName("only English job-offer pages are jobs, keyed by their reference")
        void listsJobPages() {
            var listed = JsonLdSitemapFetcher.listed(FixtureSupport.load("socgen-sitemap.xml"),
                    fetcher.jobUrl(SocieteGeneraleFetcher.BOARD));
            assertThat(listed).singleElement().satisfies(job -> {
                assertThat(job.id()).isEqualTo("26000IZY");
                assertThat(job.slugText()).isEqualTo("business analyst it sql");
            });
        }

        @Test
        @DisplayName("the JSON-LD gives city, country and a real posted date")
        void readsTheJobPage() {
            var job = new JsonLdSitemapFetcher.Listed("26000IZY",
                    "https://careers.societegenerale.com/en/job-offers/business-analyst-it-sql-26000IZY-en",
                    "business analyst it sql");
            RawPosting posting = fetcher.parsePage(FixtureSupport.load("socgen-job.html"), job);
            assertThat(posting.title()).startsWith("Business Analyst - IT/SQL");
            assertThat(posting.location()).isEqualTo("Bangalore, India");
            assertThat(posting.postedDate()).isEqualTo(LocalDate.of(2026, 10, 23));
            assertThat(posting.description()).contains("RWA");
        }
    }

    @Nested
    class Phenom {

        private static final String TOKEN = "lowes/talent.lowes.com/in/en/sitemap1.xml";
        private final PhenomFetcher fetcher = new PhenomFetcher(null, json, null, StoredPostings.none());

        @Test
        void reportsSource() {
            assertThat(fetcher.source()).isEqualTo(Source.PHENOM);
        }

        @Test
        @DisplayName("the locale's job URLs give the id and, from the slug after it, the title words")
        void listsJobPages() throws Exception {
            var listed = JsonLdSitemapFetcher.listed(FixtureSupport.load("phenom-lowes-india-sitemap.xml"),
                    fetcher.jobUrl(TOKEN));
            assertThat(listed).singleElement().satisfies(job -> {
                assertThat(job.id()).isEqualTo("JR-02625299");
                assertThat(job.slugText()).isEqualTo("Analyst EDI");
            });
            assertThat(fetcher.sitemapUrl(TOKEN)).isEqualTo("https://talent.lowes.com/in/en/sitemap1.xml");
        }

        @Test
        @DisplayName("the JSON-LD gives city, country and the posted date")
        void readsTheJobPage() {
            var job = new JsonLdSitemapFetcher.Listed("JR-02625299",
                    "https://talent.lowes.com/in/en/job/JR-02625299/Analyst-EDI", "Analyst EDI");
            RawPosting posting = fetcher.parsePage(FixtureSupport.load("phenom-lowes-job.html"), job);
            assertThat(posting.title()).isEqualTo("Analyst - EDI");
            assertThat(posting.location()).isEqualTo("Bengaluru, India");
            assertThat(posting.postedDate()).isEqualTo(LocalDate.of(2026, 9, 26));
        }

        @Test
        void rejectsATokenWithoutASitemap() {
            assertThatThrownBy(() -> PhenomFetcher.Board.parse("lowes/talent.lowes.com"))
                    .isInstanceOf(FetchException.class);
        }
    }
}
