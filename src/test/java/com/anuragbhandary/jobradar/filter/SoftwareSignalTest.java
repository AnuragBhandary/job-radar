package com.anuragbhandary.jobradar.filter;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SoftwareSignalTest {

    private static String padded(String text) {
        return text + " " + "Our benefits include flexible hours and a pension. ".repeat(20);
    }

    /** Quantum-Systems, 2026-09-28: aerospace engineering with MATLAB, nothing else. */
    @Test
    void rejectsAnAerospaceRole() {
        String text = padded("Design, implement, and validate functions for automatic waypoint"
                + " navigation. Develop software for safety-critical applications. You have at"
                + " least 1 year of professional experience in aerospace engineering with MATLAB,"
                + " Simulink, and Stateflow. Further programming languages are beneficial.");
        assertThat(SoftwareSignal.missing(text)).hasValueSatisfying(
                reason -> assertThat(reason).contains("no software tools"));
    }

    /** The Exploration Company, 2026-09-28: one passing mention is not a stack. */
    @Test
    void rejectsOnePassingMention() {
        String text = padded("Mechanical assembly for integration, electrical skill for test"
                + " (data acquisition, simple programming of python scripts, harness).");
        assertThat(SoftwareSignal.missing(text)).hasValueSatisfying(
                reason -> assertThat(reason).contains("only python"));
    }

    /** Lio SRE, 2026-09-28: a software job names its tools. */
    @Test
    void keepsASoftwareRole() {
        String text = padded("Strong Python skills and experience optimizing backend services."
                + " Experience optimizing databases at scale (MongoDB is a plus). CI/CD.");
        assertThat(SoftwareSignal.missing(text)).isEmpty();
    }

    @Test
    void leavesShortDescriptionsAlone() {
        assertThat(SoftwareSignal.missing("Engineer, Bangalore. Apply on our site.")).isEmpty();
        assertThat(SoftwareSignal.missing(null)).isEmpty();
    }

    @Test
    void matchesWholeTermsOnly() {
        assertThat(SoftwareSignal.termsIn("restaurant javascript gitlab c++ c#"))
                .containsExactlyInAnyOrder("javascript", "c++", "c#");
    }

    /** Amazon, OpenAI, Canonical: a software title is taken at its word. */
    @Test
    void trustsASoftwareTitle() {
        String silent = padded("Join the team that builds the service used by millions.");
        assertThat(SoftwareSignal.missing("Software Development Engineer, AWS DMS", silent)).isEmpty();
        assertThat(SoftwareSignal.missing("Site Reliability Engineer", silent)).isEmpty();
        assertThat(SoftwareSignal.missing("Engineer", silent)).isEmpty();
        assertThat(SoftwareSignal.missing("Engineer II", silent)).isEmpty();
        assertThat(SoftwareSignal.missing("AIT Propulsion Engineer", silent)).isPresent();
        assertThat(SoftwareSignal.missing("Manufacturing Engineer – Magnet Industrialisation", silent))
                .isPresent();
    }
}
