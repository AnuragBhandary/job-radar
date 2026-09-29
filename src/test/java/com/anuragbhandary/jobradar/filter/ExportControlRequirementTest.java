package com.anuragbhandary.jobradar.filter;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ExportControlRequirementTest {

    @ParameterizedTest
    @ValueSource(strings = {
            // Swarm Aero, 2026-09-29.
            "ITAR requirement To conform to U.S. Government export regulations, applicant must "
                    + "be a U.S. citizen, lawful permanent resident of the U.S.",
            // Rubrik FedRAMP.
            "this position will require the following: • U.S. citizenship at the time of hire.",
            // MongoDB Atlas.
            "This role is fully remote for a candidate based in the United States with US citizenship.",
            "U.S. citizenship is required to support Federal customer engagements.",
            // Beacon AI.
            "Due to U.S. export control regulations, we can only hire U.S. Persons (U.S. citizens, "
                    + "Green Card holders).",
            // Cambridge Consultants.
            "This role requires you to obtain a Security Check level security clearance.",
            "An active Top Secret clearance is required.",
    })
    void findsAUsPersonsOrClearanceLock(String text) {
        assertThat(ExportControlRequirement.find(text)).isPresent();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            // Export-licence boilerplate is a process, not a bar.
            "If you are located in or are a national of one of the listed countries, an export "
                    + "license may be required as a condition of your employment in this role.",
            "All employment is contingent upon ServiceNow obtaining any export license or other "
                    + "approval that may be required by relevant export control authorities.",
            "Existing or ability to obtain a U.S. government security clearance is a strong plus.",
            "We build compliance software for U.S. persons filing taxes abroad.",
    })
    void ignoresWhatIsNotALock(String text) {
        assertThat(ExportControlRequirement.find(text)).isEmpty();
    }
}
