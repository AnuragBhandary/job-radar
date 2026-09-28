package com.anuragbhandary.jobradar.filter;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class InternshipTest {

    @Test
    void readsTheTitle() {
        assertThat(Internship.isInternship("Software Engineering Intern")).isTrue();
        assertThat(Internship.isInternship("SDE Internship - Bangalore")).isTrue();
        assertThat(Internship.isInternship("Backend Engineer, Internal Tools")).isFalse();
        assertThat(Internship.isInternship("International Payments Engineer")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Candidates must be currently enrolled in a Bachelor's or Master's program.",
            "You are currently pursuing a B.Tech in Computer Science.",
            "Pursuing a degree in computer science, graduating in 2027.",
            "Open to the 2026 batch only.",
            "Final year students of B.E./B.Tech are eligible.",
            "You will return to school after the internship ends.",
            "Expected graduation date: December 2026 or later.",
            "An understanding of computer science through pursuit of a Bachelor\u2019s or Master\u2019s degree.",
    })
    void findsStudentOnlyWording(String text) {
        assertThat(Internship.studentOnly(text)).isPresent();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Currently pursuing or recently completed a degree in computer science.",
            "Open to recent graduates and final-year students alike, freshers and graduates welcome.",
            "A degree in computer science or a related field. Graduated in 2024 or 2025.",
            "Strong Python and SQL.",
    })
    void graduatesMayApply(String text) {
        assertThat(Internship.studentOnly(text)).isEmpty();
    }

    @Test
    void readsConversionAndLength() {
        String text = "This is a 6-month internship with a pre-placement offer for strong performers.";
        assertThat(Internship.conversion(text)).hasValueSatisfying(
                s -> assertThat(s).containsIgnoringCase("pre-placement offer"));
        assertThat(Internship.duration(text)).hasValue("6-month");
        assertThat(Internship.conversion("Interns may convert to full-time roles.")).isPresent();
        assertThat(Internship.conversion("A three-month project.")).isEmpty();
        assertThat(Internship.duration("Requires 6 months of experience with Python.")).isEmpty();
    }
}
