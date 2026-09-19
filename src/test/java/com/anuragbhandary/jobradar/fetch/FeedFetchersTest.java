package com.anuragbhandary.jobradar.fetch;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Trimmed from real responses on 2026-09-19. */
class FeedFetchersTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    @DisplayName("Jobicy: level and company go in the title, geography becomes a remote location")
    void jobicy() throws Exception {
        String body = """
                {"jobs":[{"id":"153608","url":"https://jobicy.com/jobs/153608-x",
                  "jobTitle":"Software Engineer - Auth","companyName":"Supabase",
                  "jobGeo":"Anywhere","jobLevel":"Senior",
                  "jobDescription":"<p>Build auth &amp; more</p>",
                  "pubDate":"2026-09-18T14:43:01+00:00"}]}
                """;
        List<RawPosting> postings = new JobicyFetcher(null, json).parse(body);

        assertThat(postings).hasSize(1);
        RawPosting p = postings.getFirst();
        assertThat(p.title()).isEqualTo("Software Engineer - Auth (Senior) @ Supabase");
        assertThat(p.location()).isEqualTo("Remote, Anywhere");
        assertThat(p.description()).isEqualTo("Build auth & more");
        assertThat(p.postedDate()).isEqualTo(LocalDate.of(2026, 9, 18));
    }

    @Test
    @DisplayName("We Work Remotely: the region becomes a remote location, the link is the id")
    void weWorkRemotely() throws Exception {
        String body = """
                <?xml version="1.0" encoding="UTF-8"?>
                <rss version="2.0"><channel><item>
                  <title>Twikey: Java Developer</title>
                  <region>Anywhere in the World</region>
                  <description>&lt;p&gt;Java and Spring&lt;/p&gt;</description>
                  <pubDate>Thu, 17 Sep 2026 10:51:23 +0000</pubDate>
                  <link>https://weworkremotely.com/remote-jobs/twikey-java-developer</link>
                </item></channel></rss>
                """;
        List<RawPosting> postings = new WeWorkRemotelyFetcher(null).parse(body);

        assertThat(postings).hasSize(1);
        RawPosting p = postings.getFirst();
        assertThat(p.title()).isEqualTo("Twikey: Java Developer");
        assertThat(p.location()).isEqualTo("Remote, Anywhere in the World");
        assertThat(p.externalId()).isEqualTo(p.url());
        assertThat(p.description()).isEqualTo("Java and Spring");
        assertThat(p.postedDate()).isEqualTo(LocalDate.of(2026, 9, 17));
    }

    @Test
    @DisplayName("Arbeitnow: the company goes in the title, the remote flag in the location")
    void arbeitnow() throws Exception {
        String body = """
                {"data":[{"slug":"backend-dev-berlin-1","company_name":"Acme GmbH",
                  "title":"Backend Developer","description":"<p>Kotlin</p>","remote":true,
                  "url":"https://www.arbeitnow.com/jobs/acme/backend-dev-berlin-1",
                  "location":"Berlin","created_at":1789799441}]}
                """;
        List<RawPosting> postings = new ArbeitnowFetcher(null, json).parse(body);

        assertThat(postings).hasSize(1);
        RawPosting p = postings.getFirst();
        assertThat(p.title()).isEqualTo("Backend Developer @ Acme GmbH");
        assertThat(p.location()).isEqualTo("Remote, Berlin");
        assertThat(p.externalId()).isEqualTo("backend-dev-berlin-1");
    }
}
