package com.anuragbhandary.jobradar.discover;

import static org.assertj.core.api.Assertions.assertThat;

import com.anuragbhandary.jobradar.discover.DiscoveryService.Surveyed;
import com.anuragbhandary.jobradar.domain.Source;
import com.anuragbhandary.jobradar.fetch.BoardSurveyor.Survey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DiscoveryServiceTest {

    @Test
    @DisplayName("a board with target roles is added as it is")
    void addsBoard() {
        assertThat(DiscoveryService.addedTokens(new Surveyed(Source.LEVER, "acme",
                new Survey(40, 2, 0, 0), null))).containsExactly("acme");
    }

    @Test
    @DisplayName("a board without them, or that failed, is not")
    void skipsBoard() {
        assertThat(DiscoveryService.addedTokens(new Surveyed(Source.LEVER, "acme",
                new Survey(40, 0, 0, 0), null))).isEmpty();
        assertThat(DiscoveryService.addedTokens(new Surveyed(Source.LEVER, "acme",
                null, "HTTP 404"))).isEmpty();
    }

    @Test
    @DisplayName("a large Workday site becomes one search per target country")
    void splitsLargeWorkdaySite() {
        // Walmart's site lists 2,000 at a time; the fetcher's page limit would
        // stop a whole-site crawl long before the 228 in India.
        assertThat(DiscoveryService.addedTokens(new Surveyed(Source.WORKDAY,
                "walmart/wd504/WalmartExternal", new Survey(2000, 228, 0, 0), null)))
                .containsExactly("walmart/wd504/WalmartExternal/india");
        assertThat(DiscoveryService.addedTokens(new Surveyed(Source.WORKDAY,
                "small/wd1/Careers", new Survey(80, 3, 0, 0), null)))
                .containsExactly("small/wd1/Careers");
    }

    @Test
    @DisplayName("a seeded Workday search is the same site as the bare token")
    void baseToken() {
        assertThat(DiscoveryService.baseToken(Source.WORKDAY, "kla/wd1/Search/india"))
                .isEqualTo("kla/wd1/search");
        assertThat(DiscoveryService.baseToken(Source.LEVER, "Stable-Money1")).isEqualTo("stable-money1");
    }

    @Test
    void labelsReadably() {
        assertThat(DiscoveryService.label(Source.LEVER, "stable-money1")).isEqualTo("Stable Money1");
        assertThat(DiscoveryService.label(Source.WORKDAY, "walmart/wd504/WalmartExternal"))
                .isEqualTo("Walmart");
    }
}
