package com.anuragbhandary.jobradar.discover;

import static org.assertj.core.api.Assertions.assertThat;

import com.anuragbhandary.jobradar.discover.ArchiveIndex.Platform;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The URLs are shaped like lines of the archive's CDX answers on 2026-10-01. */
class ArchiveIndexTest {

    @Test
    @DisplayName("each platform's URL gives its board token")
    void readsTokens() {
        assertThat(ArchiveIndex.token(Platform.GREENHOUSE, "https://job-boards.greenhouse.io/Razorpay/jobs/123"))
                .isEqualTo("razorpay");
        assertThat(ArchiveIndex.token(Platform.GREENHOUSE,
                "https://boards.greenhouse.io/embed/job_board?for=postman&b=x")).isEqualTo("postman");
        assertThat(ArchiveIndex.token(Platform.LEVER, "https://jobs.lever.co/stable-money1/abc-123"))
                .isEqualTo("stable-money1");
        assertThat(ArchiveIndex.token(Platform.ASHBY, "https://jobs.ashbyhq.com/Notion")).isEqualTo("Notion");
        assertThat(ArchiveIndex.token(Platform.RECRUITEE, "https://channable.recruitee.com/o/backend"))
                .isEqualTo("channable");
        assertThat(ArchiveIndex.token(Platform.SMARTRECRUITERS,
                "https://jobs.smartrecruiters.com/PHONEPELIMITED/744000-sde")).isEqualTo("PHONEPELIMITED");
        assertThat(ArchiveIndex.token(Platform.WORKDAY,
                "https://Walmart.wd504.myworkdayjobs.com/en-US/WalmartExternal/job/IN-Bangalore/x_R-1"))
                .isEqualTo("walmart/wd504/WalmartExternal");
        assertThat(ArchiveIndex.token(Platform.WORKDAY, "https://visa.wd5.myworkdayjobs.com/Visa_Early_Careers"))
                .isEqualTo("visa/wd5/Visa_Early_Careers");
        assertThat(ArchiveIndex.token(Platform.EIGHTFOLD,
                "https://paypal.eightfold.ai/careers/job/274912?hl=en&domain=paypal.com"))
                .isEqualTo("paypal/paypal.eightfold.ai/paypal.com");
        assertThat(ArchiveIndex.token(Platform.EIGHTFOLD,
                "https://aexp-sandbox.eightfold.ai/careers?domain=aexp-sandbox.com")).isNull();
        assertThat(ArchiveIndex.token(Platform.ORACLE_HCM,
                "https://jpmc.fa.oraclecloud.com/hcmUI/CandidateExperience/en/sites/CX_1001/job/210774158"))
                .isEqualTo("jpmc.fa.oraclecloud.com/CX_1001");
        assertThat(ArchiveIndex.token(Platform.ORACLE_HCM,
                "https://eeho.fa.us2.oraclecloud.com/hcmUI/CandidateExperience/en/sites/jobsearch/jobs"))
                .isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://jobs.ashbyhq.com/$10.2K",
            "https://jobs.ashbyhq.com/favicon.ico",
            "https://jobs.ashbyhq.com/api",
            "https://jobs.ashbyhq.com/-v-_wgCO6X2t2oY5F0J17EyRjTKe2HoTtdkUm01TaOlCt",
            // A real kind of junk in the index: credentials typed into a URL.
            // The address here is invented.
            "https://acme.wd103.myworkdayjobs.com/:someone@example.com:Secret@123",
            "https://acme.wd1.myworkdayjobs.com/robots.txt",
            "https://jobs.lever.co/",
            "https://jobs.lever.co/1005",
    })
    @DisplayName("junk in the archive is never a token")
    void rejectsJunk(String url) {
        Platform platform = url.contains("workday") ? Platform.WORKDAY
                : url.contains("lever") ? Platform.LEVER : Platform.ASHBY;
        assertThat(ArchiveIndex.token(platform, url)).isNull();
    }

    @Test
    @DisplayName("a response is de-duplicated across case, keeping the first form")
    void collectsDistinct() {
        Map<String, String> found = new LinkedHashMap<>();
        ArchiveIndex.collect(Platform.ASHBY, """
                https://jobs.ashbyhq.com/Notion
                https://jobs.ashbyhq.com/notion/1f2e
                https://jobs.ashbyhq.com/linear
                https://jobs.ashbyhq.com/$9.6K
                """, found);
        assertThat(found).containsExactly(Map.entry("notion", "Notion"), Map.entry("linear", "linear"));
    }
}
