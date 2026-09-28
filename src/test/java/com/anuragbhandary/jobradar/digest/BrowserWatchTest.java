package com.anuragbhandary.jobradar.digest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class BrowserWatchTest {

    @Test
    void rendersOneLinePerPage() {
        var watch = new BrowserWatch(List.of(
                new BrowserWatch.Page("Upstox", "Mumbai", "https://upstox.example/jobs", null),
                new BrowserWatch.Page("smallcase", null, "https://smallcase.example/jobs", "0 open")));
        assertThat(watch.render())
                .contains("## Check in the browser (2)")
                .contains("- Upstox (Mumbai): https://upstox.example/jobs\n")
                .contains("- smallcase: https://smallcase.example/jobs · 0 open\n");
    }

    @Test
    void nothingWatchedAddsNothing() {
        assertThat(new BrowserWatch(null).render()).isEmpty();
    }
}
