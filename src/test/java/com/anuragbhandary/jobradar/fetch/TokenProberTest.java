package com.anuragbhandary.jobradar.fetch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.anuragbhandary.jobradar.domain.BoardToken;
import com.anuragbhandary.jobradar.domain.Source;
import com.anuragbhandary.jobradar.fetch.HttpFetchClient.HttpResult;
import com.anuragbhandary.jobradar.repo.BoardTokenRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TokenProberTest {

    @Mock
    private HttpFetchClient http;

    @Mock
    private BoardTokenRepository boards;

    private TokenProber prober;

    @BeforeEach
    void setUp() {
        prober = new TokenProber(http, new ObjectMapper(), boards);
        when(boards.findBySourceAndToken(any(), anyString())).thenReturn(Optional.empty());
    }

    private void answer(String urlFragment, int status, String body) throws Exception {
        when(http.getRaw(org.mockito.ArgumentMatchers.contains(urlFragment), any()))
                .thenReturn(new HttpResult(status, body));
    }

    private ProbeResult resultFor(List<ProbeResult> results, Source source) {
        return results.stream().filter(r -> r.source() == source).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("all four platforms are tried")
    void triesEveryPlatform() throws Exception {
        answer("greenhouse.io", 404, "");
        answer("ashbyhq.com", 404, "");
        answer("lever.co", 404, "");
        answer("smartrecruiters.com", 200, "{\"totalFound\":0}");

        // Amazon is deliberately absent: its tokens are country codes, not
        // companies, and all four are swept on every run anyway.
        assertThat(prober.probe("acme")).extracting(ProbeResult::source)
                .containsExactly(Source.GREENHOUSE, Source.ASHBY,
                        Source.LEVER, Source.SMARTRECRUITERS);
    }

    @Test
    @DisplayName("a board with postings is FOUND, with its count")
    void reportsFoundWithCount() throws Exception {
        answer("greenhouse.io", 200, "{\"jobs\":[{},{},{}]}");
        answer("ashbyhq.com", 404, "");
        answer("lever.co", 404, "");
        answer("smartrecruiters.com", 200, "{\"totalFound\":0}");

        ProbeResult greenhouse = resultFor(prober.probe("acme"), Source.GREENHOUSE);
        assertThat(greenhouse.outcome()).isEqualTo(ProbeResult.Outcome.FOUND);
        assertThat(greenhouse.postings()).isEqualTo(3);
        assertThat(greenhouse.isInteresting()).isTrue();
    }

    @Test
    @DisplayName("404 is ABSENT - the company is provably not on that platform")
    void reportsAbsentOn404() throws Exception {
        answer("greenhouse.io", 404, "");
        answer("ashbyhq.com", 404, "");
        answer("lever.co", 404, "");
        answer("smartrecruiters.com", 200, "{\"totalFound\":0}");

        assertThat(resultFor(prober.probe("acme"), Source.LEVER).outcome())
                .isEqualTo(ProbeResult.Outcome.ABSENT);
    }

    @Test
    @DisplayName("an empty SmartRecruiters board is not evidence of absence")
    void smartRecruitersEmptyIsAmbiguous() throws Exception {
        answer("greenhouse.io", 404, "");
        answer("ashbyhq.com", 404, "");
        answer("lever.co", 404, "");
        answer("smartrecruiters.com", 200, "{\"totalFound\":0,\"content\":[]}");

        // Greenhouse, Ashby and Lever 404 an unknown token, so their empty means
        // a real board with no openings. SmartRecruiters answers 200 for any
        // company name at all, so its empty means nothing either way - and that
        // is why eight seeded tokens cannot be declared dead.
        ProbeResult sr = resultFor(prober.probe("acme"), Source.SMARTRECRUITERS);
        assertThat(sr.outcome()).isEqualTo(ProbeResult.Outcome.EMPTY);
        assertThat(sr.detail()).contains("not proof of absence");

        assertThat(resultFor(prober.probe("acme"), Source.GREENHOUSE).outcome())
                .isEqualTo(ProbeResult.Outcome.ABSENT);
    }

    @Test
    @DisplayName("a confirmed-absent company is not probed at all")
    void skipsKnownAbsent() throws Exception {
        // Four requests to learn what two rounds of guessing already established.
        List<ProbeResult> results = prober.probe("Juspay");

        assertThat(results).hasSize(1);
        assertThat(results.getFirst().outcome()).isEqualTo(ProbeResult.Outcome.SKIPPED);
        verify(http, never()).getRaw(anyString(), any());
    }

    @Test
    @DisplayName("an already-seeded board is reported, not re-fetched")
    void skipsSeededBoards() throws Exception {
        when(boards.findBySourceAndToken(Source.GREENHOUSE, "stripe"))
                .thenReturn(Optional.of(new BoardToken(Source.GREENHOUSE, "stripe", "Stripe")));
        answer("ashbyhq.com", 404, "");
        answer("lever.co", 404, "");
        answer("smartrecruiters.com", 200, "{\"totalFound\":0}");

        assertThat(resultFor(prober.probe("stripe"), Source.GREENHOUSE).outcome())
                .isEqualTo(ProbeResult.Outcome.ALREADY_KNOWN);
    }

    @Test
    @DisplayName("token casing follows each platform's convention")
    void normalisesTokenPerPlatform() throws Exception {
        answer("greenhouse.io", 404, "");
        answer("ashbyhq.com", 404, "");
        answer("lever.co", 404, "");
        answer("smartrecruiters.com", 200, "{\"totalFound\":0}");

        List<ProbeResult> results = prober.probe("Delivery Hero");

        // Greenhouse, Ashby and Lever use lowercase handles; SmartRecruiters
        // uses the company's own casing.
        assertThat(resultFor(results, Source.GREENHOUSE).token()).isEqualTo("deliveryhero");
        assertThat(resultFor(results, Source.SMARTRECRUITERS).token()).isEqualTo("Delivery Hero");
    }
}
