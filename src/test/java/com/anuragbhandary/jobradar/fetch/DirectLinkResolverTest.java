package com.anuragbhandary.jobradar.fetch;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DirectLinkResolverTest {

    @Test
    @DisplayName("handles drop legal forms and try joined, hyphenated and first-word forms")
    void handles() {
        assertThat(DirectLinkResolver.handles("Manex AI GmbH"))
                .containsExactly("manexai", "manex-ai", "manex");
        assertThat(DirectLinkResolver.handles("Twikey")).containsExactly("twikey");
    }

    @Test
    @DisplayName("the same title with a gender tag and an @Company suffix matches")
    void matchesTitles() {
        assertThat(DirectLinkResolver.similarity("AI Agent Engineer (f/m/d)",
                "AI Agent Engineer (m/w/d) @ Manex AI GmbH")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("a one-word title does not match a long, different one")
    void rejectsLooseMatches() {
        assertThat(DirectLinkResolver.similarity("Engineer",
                "Senior Staff Machine Learning Infrastructure Engineer")).isLessThan(0.75);
    }
}
