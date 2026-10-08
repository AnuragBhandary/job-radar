package com.anuragbhandary.jobradar.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RoleCategoryTest {

    /** Titles from his applications and the 2026-10-07 review. */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Software Development Engineer - 2026 (SDE-I, new grad req)|SOFTWARE",
            "Junior Backend Software Engineer (Kotlin / Java) - Banking Platform (m/f/x)|SOFTWARE",
            "Data Engineer (1 - 2 years of experience in Java / Python / Go, Kafka, Flink, Hadoop)|DATA_ENGINEERING",
            "Analytics Engineer|DATA_ENGINEERING",
            "Python Pipeline Developer|DATA_ENGINEERING",
            "Associate Data Scientist|DATA_ANALYTICS",
            "Analyst-Data Analytics|DATA_ANALYTICS",
            "Business Intelligence Analyst|DATA_ANALYTICS",
            "AI Agent Engineer|AI_ML",
            "Assoc Machine Learning Engineer|AI_ML",
            "Site Reliability Engineer|PLATFORM",
            "Integration Engineer|PLATFORM",
            "Software Quality Engineer – Automation & AI|QA",
            "Software Development Engineer in Test|QA",
            "Full Stack Engineer, Employee Engagement Engineering|FULL_STACK",
            "Frontend Developer|FULL_STACK",
            "Software Engineering PMTS|SOFTWARE",
            "Engineering-L2-Analyst-Software Engineering|SOFTWARE",
            "Software Engineer, Portfolio Management Group, Analyst|SOFTWARE",
            "2027 New Analyst - Legal|OTHER",
            "2026 EMEA Birmingham, Asset Management, Controls Governance & Testing, New Analyst|OTHER",
    })
    void categorises(String title, RoleCategory expected) {
        assertThat(RoleCategory.of(title)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({"DATA_ENGINEERING,Data resume", "DATA_ANALYTICS,Data resume", "AI_ML,Software resume",
            "QA,Software resume"})
    void resumes(RoleCategory category, String resume) {
        assertThat(category.resume().label()).isEqualTo(resume);
        assertThat(RoleCategory.fromLabel(category.label())).isEqualTo(category);
    }
}
