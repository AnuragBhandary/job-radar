package com.anuragbhandary.jobradar.fetch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class HackerNewsFetcherTest {

    private final HackerNewsFetcher fetcher = new HackerNewsFetcher(null, new ObjectMapper());

    @Test
    @DisplayName("picks the newest 'Who is hiring?' thread, not 'Who wants to be hired?'")
    void picksHiringThread() throws Exception {
        String body = """
                {"hits":[
                  {"objectID":"2","title":"Ask HN: Who wants to be hired? (September 2026)"},
                  {"objectID":"1","title":"Ask HN: Who is hiring? (September 2026)"}]}
                """;
        assertThat(fetcher.latestThread(body)).isEqualTo("1");
        assertThatThrownBy(() -> fetcher.latestThread("{\"hits\":[]}"))
                .isInstanceOf(FetchException.class);
    }

    @Test
    @DisplayName("each top-level comment is a posting headed by its first line")
    void parsesComments() throws Exception {
        String body = """
                {"children":[
                  {"id":101,"created_at_i":1788307200,
                   "text":"Proxybase | Backend Engineer (Rust) | REMOTE, Global | Full-Time<p>We build proxies &amp; more.<p>Apply: jobs@example.com"},
                  {"id":102,"text":null}]}
                """;
        List<RawPosting> postings = fetcher.parse(body);

        assertThat(postings).hasSize(1);
        RawPosting p = postings.getFirst();
        assertThat(p.externalId()).isEqualTo("101");
        assertThat(p.title()).isEqualTo("Proxybase | Backend Engineer (Rust) | REMOTE, Global | Full-Time");
        assertThat(p.location()).isEqualTo(p.title());
        assertThat(p.description()).contains("We build proxies & more.\n\nApply:");
        assertThat(p.url()).isEqualTo("https://news.ycombinator.com/item?id=101");
        assertThat(p.postedDate()).isEqualTo(LocalDate.of(2026, 9, 2));
    }
}
