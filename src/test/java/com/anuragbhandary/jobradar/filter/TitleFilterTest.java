package com.anuragbhandary.jobradar.filter;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TitleFilterTest {

    private final TitleFilter filter = new TitleFilter(RealConfig.withScreening());

    @ParameterizedTest
    @ValueSource(strings = {
            "Associate Software Engineer - Java",
            "Software Engineer, New Grad",
            "Graduate Software Engineer",
            "SDE I",
            "Junior Backend Engineer",
            "Software Development Engineer",
            "Python Developer",
            "Data Engineer",
            "Platform Engineer",
            "Machine Learning Engineer",
    })
    @DisplayName("entry-level software titles are accepted")
    void acceptsTargetTitles(String title) {
        assertThat(filter.screen(title).accepted()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Senior Software Engineer",
            "Staff Backend Engineer",
            "Principal Engineer",
            "Engineering Manager",
            "Director of Engineering",
            "Sales Engineer",
            "Solutions Architect",
            "Customer Success Manager",
            "Technical Support Engineer",
            "Software Engineering Intern",
            "Werkstudent Software Engineering",
            "Marketing Manager",
    })
    @DisplayName("senior, adjacent and non-engineering titles are rejected")
    void rejectsExcludedTitles(String title) {
        assertThat(filter.screen(title).accepted()).isFalse();
    }

    @Test
    @DisplayName("'Intermediate Backend Engineer' is rejected")
    void rejectsIntermediate() {
        FilterVerdict verdict = filter.screen("Intermediate Backend Engineer");
        assertThat(verdict.accepted()).isFalse();
        assertThat(verdict.reason()).contains("intermediate");
    }

    @Test
    @DisplayName("exclusion beats inclusion")
    void exclusionWins() {
        // "Senior Software Engineer" contains "software". A filter that checked
        // inclusion first would keep it.
        assertThat(filter.screen("Senior Software Engineer").accepted()).isFalse();
    }

    @Test
    @DisplayName("Engineer II and III are levels, but SDE I is a target")
    void handlesRomanNumerals() {
        assertThat(filter.screen("Software Engineer II").accepted()).isFalse();
        assertThat(filter.screen("Software Engineer III").accepted()).isFalse();
        assertThat(filter.screen("Software Engineer I").accepted()).isTrue();
    }

    @Test
    @DisplayName("Arabic seniority levels count too")
    void handlesArabicLevels() {
        // MongoDB advertises "Software Engineer 2" in Dublin. It read as
        // entry-level for a whole screening run, because only Roman numerals
        // were being treated as levels.
        assertThat(filter.screen("Software Engineer 2").accepted()).isFalse();
        assertThat(filter.screen("Technical Escalations Engineer 2").accepted()).isFalse();
        assertThat(filter.screen("Software Engineer 1").accepted()).isTrue();
        assertThat(filter.screen("SDE 1").accepted()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Avionics Hardware Engineer",
            "RF & EMC Engineer",
            "Design Engineer - Electronics",
            "Stress & Dynamics Engineer",
            "Camera Engineer",
            "Wireless Communications Systems Engineer",
            "Embedded Firmware Engineer",
    })
    @DisplayName("hardware and defence engineering is a different discipline, not a near miss")
    void rejectsHardwareEngineering(String title) {
        // Helsing alone supplied 31 of the first 80 candidates, almost all of
        // them hardware roles matched only by the bare word "engineer".
        assertThat(filter.screen(title).accepted()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Value Engineer-Scale-NAM",
            "Solution Engineer : Mumbai",
            "Onboarding Engineer - Presales",
            "Technical Services Engineer",
            "Junior Recipe Developer (all genders)",
    })
    @DisplayName("customer-facing and non-software 'engineer' titles are rejected")
    void rejectsAdjacentTitles(String title) {
        assertThat(filter.screen(title).accepted()).isFalse();
    }

    @Test
    @DisplayName("a double-i inside a word is not a seniority level")
    void doesNotMatchNumeralsInsideWords() {
        // Substring matching on "ii" would reject this for the wrong reason.
        assertThat(filter.screen("Software Engineer, Hawaii").accepted()).isTrue();
    }

    @Test
    @DisplayName("a title that is not a software role at all is rejected")
    void rejectsNonSoftwareTitles() {
        FilterVerdict verdict = filter.screen("Financial Analyst");
        assertThat(verdict.accepted()).isFalse();
        assertThat(verdict.reason()).contains("not a software role");
    }

    @Test
    @DisplayName("graduate signals are found in the title or the description")
    void detectsGraduateSignals() {
        assertThat(filter.hasGraduateSignal("Software Engineer, New Grad", null)).isTrue();
        assertThat(filter.hasGraduateSignal("Software Engineer",
                "You must have graduated within the last 24 months.")).isTrue();
        assertThat(filter.hasGraduateSignal("Software Engineer",
                "Join our platform team.")).isFalse();
    }

    @Test
    @DisplayName("an absent title is rejected rather than passed through")
    void rejectsMissingTitle() {
        assertThat(filter.screen(null).accepted()).isFalse();
        assertThat(filter.screen("  ").accepted()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Software Engineer (v/m/x)",
            "Software Engineer (m/v)",
            "Backend Developer (m/w/d)",
            "Data Engineer (m,f,x)",
            "Platform Engineer (all genders)",
    })
    @DisplayName("Dutch and German gender tags are not seniority levels")
    void genderTagsAreNotSeniorityLevels(String title) {
        // "(v/m/x)" is vrouw/man/x, not Roman five. Coolblue's Dutch postings were
        // being rejected as senior roles on the strength of that "v".
        assertThat(filter.screen(title).accepted()).isTrue();
    }

    @Test
    @DisplayName("'2 Year Rotational Programme' is a graduate scheme, not a level")
    void programmeDurationInATitleIsNotALevel() {
        // The years extractor protects this phrasing carefully. It never got the
        // chance, because a bare digit in the title was read as seniority first.
        assertThat(filter.screen("Software Engineer, 2 Year Rotational Programme")
                .accepted()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Software Engineer II", "SDE III - Devops", "Software Engineer 2",
            "Engineer 3 - GTM Tech", "Security Engineer - II (SOC)",
            "Associate TSE II", "Software Engineer 3, Atlas Growth 2",
    })
    @DisplayName("real seniority levels are still rejected")
    void stillRejectsRealLevels(String title) {
        assertThat(filter.screen(title).accepted()).isFalse();
    }

}
