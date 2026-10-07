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

    /** Graduate programmes from the 2026-10-01 window, none open to a 2025 graduate. */
    @ParameterizedTest
    @ValueSource(strings = {
            "Students must have a graduation date between December 2026 and June 2027.",
            "To be eligible you must be a final year undergraduate or Masters student.",
            "You're enrolled in a full-time Bachelors or Masters degree in Computer Science.",
            "A degree in Computer Science or a related technical field (graduated within the last year)."})
    void graduateProgrammesForThisYearOnly(String text) {
        assertThat(Internship.studentOnly(text)).isPresent();
    }

    @Test
    void graduateProgrammeTitles() {
        assertThat(Internship.isGraduateProgramme("Software Engineer, New Grad")).isTrue();
        assertThat(Internship.isGraduateProgramme("Engineering Graduate Programme (Backend)")).isTrue();
        assertThat(Internship.isGraduateProgramme("Campus - Full Time - Software Engineer - 2027 (UK - London)")).isTrue();
        assertThat(Internship.isGraduateProgramme("Backend Engineer")).isFalse();
        assertThat(Internship.studentOnly("Named to the Enterprise Tech 30 Class of 2026.")).isEmpty();
        // A recent graduate is still welcome.
        assertThat(Internship.studentOnly("This role suits a recent graduate (or someone with a strong "
                + "project record).")).isEmpty();
    }

    /** UK graduate schemes and placements, 2026-10-07. */
    @Test
    void ukPlacementWordingIsStudentOnly() {
        assertThat(Internship.studentOnly("The ideal candidate will have the following skills and"
                + " experience: A current undergraduate, master's or PhD student in machine"
                + " learning or a related discipline")).isPresent();
        assertThat(Internship.studentOnly("This is a 12-month industrial placement and applicants"
                + " must be studying a relevant University Degree course that includes a dedicated"
                + " placement year July 2027 to July 2028.")).isPresent();
        assertThat(Internship.studentOnly("This role is available to candidates who qualify for a"
                + " placement year and will commence Summer of 2027.")).isPresent();
        assertThat(Internship.studentOnly("Working towards a degree in Engineering, Quality or"
                + " Data analysis.")).isPresent();
        assertThat(Internship.studentOnly("You have a degree and are working towards a cloud"
                + " certification.")).isEmpty();
    }
}
