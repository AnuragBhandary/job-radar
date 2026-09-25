package com.anuragbhandary.jobradar.cli;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class NotifyCommandTest {

    @Test
    @DisplayName("an aggregator's 'Role @ Company' names the real employer")
    void readsEmployerFromAggregatorRole() {
        assertThat(NotifyCommand.companyAndRole("Arbeitnow",
                "AI Agent Engineer (f/m/d) @ Manex AI GmbH"))
                .containsExactly("Manex AI GmbH", "AI Agent Engineer (f/m/d)");
    }

    @Test
    @DisplayName("a Hacker News header gives the company first and skips the URL")
    void readsHackerNewsHeader() {
        assertThat(NotifyCommand.companyAndRole("HN Who is hiring",
                "CyberAtlas | https://cyberatlas.ai | Software Engineer | REMOTE Worldwide"))
                .containsExactly("CyberAtlas", "Software Engineer");
    }

    @Test
    @DisplayName("We Work Remotely's 'Company: Role'")
    void readsWeWorkRemotely() {
        assertThat(NotifyCommand.companyAndRole("We Work Remotely", "Twikey: Java Developer"))
                .containsExactly("Twikey", "Java Developer");
    }

    @Test
    @DisplayName("a direct board keeps its own company")
    void keepsDirectCompany() {
        assertThat(NotifyCommand.companyAndRole("Target", "Engineer"))
                .containsExactly("Target", "Engineer");
    }

    @Test
    @DisplayName("the latest appended note is the one sent")
    void usesLatestNote() {
        assertThat(NotifyCommand.latestNote("Apply now. Old view\nStretch. New view\n"))
                .isEqualTo("Stretch. New view");
    }
}
