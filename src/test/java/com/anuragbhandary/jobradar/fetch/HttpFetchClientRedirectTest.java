package com.anuragbhandary.jobradar.fetch;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import org.junit.jupiter.api.Test;

class HttpFetchClientRedirectTest {

    /** Wipro's Location header for a "%2B" job link, 2026-10-01. */
    @Test
    void encodesWhatAUriMayNotHoldRaw() {
        String fixed = HttpFetchClient.encodeIllegal("/job/Pune-SDET- -Python-IND-411005/199871-en_US/");
        assertThat(fixed).isEqualTo("/job/Pune-SDET-%20-Python-IND-411005/199871-en_US/");
        assertThat(URI.create("https://careers.wipro.com/job/x/1/").resolve(fixed).toString())
                .isEqualTo("https://careers.wipro.com/job/Pune-SDET-%20-Python-IND-411005/199871-en_US/");
        // Existing escapes are left as they are.
        assertThat(HttpFetchClient.encodeIllegal("/job/JAVA%2BKafka/1/")).isEqualTo("/job/JAVA%2BKafka/1/");
    }
}
