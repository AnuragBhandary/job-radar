package com.anuragbhandary.jobradar.digest;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FrontendSignalTest {

    @Test
    @DisplayName("two or more frontend terms are flagged")
    void flagsFullStackDescriptions() {
        assertThat(DigestWriter.frontendSignal(
                "You will build features in React and TypeScript on top of our Python API."))
                .contains("react").contains("typescript");
    }

    @Test
    @DisplayName("one passing mention is not a signal")
    void ignoresOneMention() {
        assertThat(DigestWriter.frontendSignal(
                "Backend services in Python that serve our React clients.")).isNull();
    }
}
