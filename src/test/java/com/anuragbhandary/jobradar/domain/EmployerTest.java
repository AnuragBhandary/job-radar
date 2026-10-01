package com.anuragbhandary.jobradar.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class EmployerTest {

    @Test
    @DisplayName("Arbeitnow's 'Role @ Company' gives the employer, not Arbeitnow")
    void arbeitnow() {
        assertThat(Employer.split("Arbeitnow", Source.ARBEITNOW,
                "AI Agent Engineer (f/m/d) @ Manex AI GmbH"))
                .containsExactly("Manex AI GmbH", "AI Agent Engineer (f/m/d)");
    }

    @Test
    @DisplayName("We Work Remotely's 'Company: Role'")
    void weWorkRemotely() {
        assertThat(Employer.split("We Work Remotely", Source.WE_WORK_REMOTELY,
                "Twikey: Java Developer")).containsExactly("Twikey", "Java Developer");
    }

    @Test
    @DisplayName("a Hacker News header skips URLs and work-mode words")
    void hackerNews() {
        assertThat(Employer.split("HN Who is hiring", Source.HACKER_NEWS,
                "CyberAtlas | https://cyberatlas.ai | Software Engineer | REMOTE Worldwide"))
                .containsExactly("CyberAtlas", "Software Engineer");
    }

    @Test
    @DisplayName("a direct board keeps its label and title")
    void direct() {
        assertThat(Employer.split("Target", Source.WORKDAY, "Engineer"))
                .containsExactly("Target", "Engineer");
    }

    @Test
    @DisplayName("a relisted role keys the same across boards and gender tags")
    void roleKeyMatchesRelists() {
        String viaArbeitnow = Employer.roleKey("Arbeitnow", Source.ARBEITNOW,
                "Software Engineer, Infrastructure - Self Managed Experience (SMX) @ Camunda");
        String direct = Employer.roleKey("Camunda", Source.ASHBY,
                "Software Engineer, Infrastructure - Self Managed Experience (SMX)");
        assertThat(viaArbeitnow).isEqualTo(direct);
        assertThat(Employer.roleKey("Arbeitnow", Source.ARBEITNOW, "AI Agent Engineer (f/m/d) @ Manex AI GmbH"))
                .isEqualTo(Employer.roleKey("Arbeitnow", Source.ARBEITNOW, "AI Agent Engineer (m/w/d) @ Manex AI"));
    }

    @Test
    void jobgetherHidesTheEmployer() {
        assertThat(Employer.hidesEmployer(Source.LEVER, "jobgether")).isTrue();
        assertThat(Employer.hidesEmployer(Source.LEVER, "shyftlabs")).isFalse();
        assertThat(Employer.hidesEmployer(Source.GREENHOUSE, "jobgether")).isFalse();
    }
}
