package com.anuragbhandary.jobradar.filter;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ResidencyRequirementTest {

    @ParameterizedTest
    @ValueSource(strings = {
            // Bjak, 2026-09-22.
            "This role is remote, but candidates must be based in Germany. We are hiring "
                    + "specifically for this market, so applicants should already be based in Germany.",
            "You must currently reside in the Netherlands.",
            "Applicants must be a resident of the United Kingdom.",
            "Candidates need to already be living in Ireland to be considered.",
    })
    void findsAResidencyLock(String text) {
        assertThat(ResidencyRequirement.find(text)).isPresent();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            // Naming the office is not a lock: relocating satisfies it.
            "You must be based in Munich or willing to commute three days a week.",
            "Candidates must already be based in India or APAC.",
            "You should currently live in Europe or be willing to relocate.",
            "Must currently reside anywhere within the EU or Asia.",
            "We build software for residents of Germany.",
    })
    void ignoresWhatIsNotALock(String text) {
        assertThat(ResidencyRequirement.find(text)).isEmpty();
    }
}
