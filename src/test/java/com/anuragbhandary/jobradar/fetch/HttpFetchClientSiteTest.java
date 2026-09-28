package com.anuragbhandary.jobradar.fetch;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class HttpFetchClientSiteTest {

    @Test
    void everyTenantOfOnePlatformIsOneSite() {
        assertThat(HttpFetchClient.site("https://target.wd5.myworkdayjobs.com/wday/cxs/x"))
                .isEqualTo(HttpFetchClient.site("https://nvidia.wd5.myworkdayjobs.com/y"))
                .isEqualTo("myworkdayjobs.com");
        assertThat(HttpFetchClient.site("https://boards-api.greenhouse.io/v1/boards/stripe/jobs"))
                .isEqualTo("greenhouse.io");
        assertThat(HttpFetchClient.site("https://api.lever.co/v0/postings/paytm"))
                .isNotEqualTo(HttpFetchClient.site("https://api.ashbyhq.com/posting-api/job-board/x"));
    }

    @Test
    void oddInputIsStillAKey() {
        assertThat(HttpFetchClient.site("not a url")).isEmpty();
        assertThat(HttpFetchClient.site("http://localhost:8080/x")).isEqualTo("localhost");
    }
}
