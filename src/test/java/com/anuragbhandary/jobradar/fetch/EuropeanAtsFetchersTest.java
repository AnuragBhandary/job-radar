package com.anuragbhandary.jobradar.fetch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Shapes trimmed from the live feeds of 2026-10-20 (Personio, Teamtailor, Hugging Face). */
class EuropeanAtsFetchersTest {

    private final HttpFetchClient http = mock(HttpFetchClient.class);

    @Test
    void personioKeepsEveryOfficeAndEverySection() throws Exception {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <workzag-jobs>
                <position>
                    <id>1834171</id>
                    <subcompany>Personio SE &amp; Co. KG</subcompany>
                    <office>Munich</office>
                    <additionalOffices><office>Berlin</office></additionalOffices>
                    <name>Junior Software Engineer</name>
                    <jobDescriptions>
                        <jobDescription><name>The Role</name><value><![CDATA[<p>Build things.</p>]]></value></jobDescription>
                        <jobDescription><name>Requirements</name><value><![CDATA[<ul><li>1+ years of Python</li></ul>]]></value></jobDescription>
                    </jobDescriptions>
                    <createdAt>2026-10-13T14:10:41+00:00</createdAt>
                </position>
                </workzag-jobs>
                """;
        List<RawPosting> postings = new PersonioFetcher(http).parse(xml, "acme");
        assertThat(postings).singleElement().satisfies(p -> {
            assertThat(p.externalId()).isEqualTo("1834171");
            assertThat(p.title()).isEqualTo("Junior Software Engineer");
            assertThat(p.location()).isEqualTo("Munich; Berlin");
            assertThat(p.description()).contains("Build things.").contains("1+ years of Python");
            assertThat(p.url()).isEqualTo("https://acme.jobs.personio.de/job/1834171?language=en");
            assertThat(p.postedDate()).isEqualTo(LocalDate.of(2026, 10, 13));
        });
    }

    @Test
    void personioRejectsAPageThatIsNotItsFeed() {
        assertThatThrownBy(() -> new PersonioFetcher(http).parse("<html><body/></html>", "nope"))
                .isInstanceOf(FetchException.class);
    }

    @Test
    void teamtailorReadsLocationsAndRemote() throws Exception {
        String rss = """
                <?xml version="1.0" encoding="UTF-8"?>
                <rss version="2.0" xmlns:tt="https://teamtailor.com/locations">
                  <channel>
                    <item>
                      <title>Backend Developer</title>
                      <description>&lt;p&gt;Python and Postgres.&lt;/p&gt;</description>
                      <pubDate>Fri, 24 Jul 2026 09:05:16 +0200</pubDate>
                      <link>https://acme.teamtailor.com/jobs/8118064-backend-developer</link>
                      <remoteStatus>fully</remoteStatus>
                      <guid>4ff07fd5-3ac0-4333-8f4c-955380396321</guid>
                      <tt:locations>
                        <tt:location><tt:name>HQ</tt:name><tt:city>Amsterdam</tt:city><tt:country>Netherlands</tt:country></tt:location>
                      </tt:locations>
                    </item>
                  </channel>
                </rss>
                """;
        List<RawPosting> postings = new TeamtailorFetcher(http).parse(rss, "acme");
        assertThat(postings).singleElement().satisfies(p -> {
            assertThat(p.externalId()).isEqualTo("4ff07fd5-3ac0-4333-8f4c-955380396321");
            assertThat(p.location()).isEqualTo("Amsterdam, Netherlands; Remote");
            assertThat(p.description()).contains("Python and Postgres.");
            assertThat(p.postedDate()).isEqualTo(LocalDate.of(2026, 7, 24));
        });
        assertThat(TeamtailorFetcher.feed("acme")).isEqualTo("https://acme.teamtailor.com/jobs.rss");
        assertThat(TeamtailorFetcher.feed("career.teamtailor.com"))
                .isEqualTo("https://career.teamtailor.com/jobs.rss");
    }

    @Test
    void workableReadsTheWidgetApi() throws Exception {
        String body = """
                {"name":"Hugging Face","jobs":[{"title":"Open-Source Machine Learning Engineer",
                 "shortcode":"81B46579FE","telecommuting":true,"url":"https://apply.workable.com/j/81B46579FE",
                 "published_on":"2026-05-29","country":"France","city":"Paris",
                 "locations":[{"country":"France","countryCode":"FR","city":"Paris","hidden":false}],
                 "description":"<p>Work on transformers.</p>"}]}
                """;
        List<RawPosting> postings = new WorkableFetcher(http, new ObjectMapper()).parse(body, "huggingface");
        assertThat(postings).singleElement().satisfies(p -> {
            assertThat(p.externalId()).isEqualTo("81B46579FE");
            assertThat(p.location()).isEqualTo("Paris, France; Remote");
            assertThat(p.description()).contains("Work on transformers.");
            assertThat(p.postedDate()).isEqualTo(LocalDate.of(2026, 5, 29));
        });
    }
}
