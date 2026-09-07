package com.anuragbhandary.jobradar.apply;

import static org.assertj.core.api.Assertions.assertThat;

import com.anuragbhandary.jobradar.domain.Posting;
import com.anuragbhandary.jobradar.domain.Source;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ApplyServiceUrlTest {

    private static Posting posting(Source source, String url) {
        Posting posting = new Posting(source, "acme", "1", "Backend Engineer");
        posting.setUrl(url);
        return posting;
    }

    @Test
    @DisplayName("Ashby puts the form on its own URL, behind a role=tab link")
    void ashbyFormIsASeparatePage() {
        // Found by a real run: the Camunda posting rendered zero fields, and the
        // link to the form is role=tab - so looking for a button or link named
        // "Apply" never finds it, however many spellings are tried.
        assertThat(ApplyService.applicationUrlFor(posting(Source.ASHBY,
                "https://jobs.ashbyhq.com/camunda/93fc1bfe")))
                .isEqualTo("https://jobs.ashbyhq.com/camunda/93fc1bfe/application");
    }

    @Test
    void leverAppendsApply() {
        assertThat(ApplyService.applicationUrlFor(posting(Source.LEVER,
                "https://jobs.lever.co/cred/abc-123")))
                .isEqualTo("https://jobs.lever.co/cred/abc-123/apply");
    }

    @Test
    @DisplayName("query strings and trailing slashes are stripped before appending")
    void normalisesBeforeAppending() {
        assertThat(ApplyService.applicationUrlFor(posting(Source.ASHBY,
                "https://jobs.ashbyhq.com/camunda/abc/?utm_source=x")))
                .isEqualTo("https://jobs.ashbyhq.com/camunda/abc/application");
    }

    @Test
    void doesNotDoubleAppend() {
        assertThat(ApplyService.applicationUrlFor(posting(Source.ASHBY,
                "https://jobs.ashbyhq.com/camunda/abc/application"))).isNull();
        assertThat(ApplyService.applicationUrlFor(posting(Source.LEVER,
                "https://jobs.lever.co/cred/abc/apply"))).isNull();
    }

    @Test
    @DisplayName("platforms whose form is inline are left alone")
    void doesNotGuessOnOtherPlatforms() {
        // Navigating away from a page that does have the form turns a working
        // application into a 404.
        assertThat(ApplyService.applicationUrlFor(posting(Source.GREENHOUSE,
                "https://job-boards.greenhouse.io/acme/jobs/123"))).isNull();
        assertThat(ApplyService.applicationUrlFor(posting(Source.RECRUITEE,
                "https://jobs.acme.com/o/role/c/new"))).isNull();
        assertThat(ApplyService.applicationUrlFor(posting(Source.WORKDAY, "https://x"))).isNull();
    }

    @Test
    void handlesAMissingUrl() {
        assertThat(ApplyService.applicationUrlFor(posting(Source.ASHBY, null))).isNull();
        assertThat(ApplyService.applicationUrlFor(posting(Source.ASHBY, ""))).isNull();
    }
}
