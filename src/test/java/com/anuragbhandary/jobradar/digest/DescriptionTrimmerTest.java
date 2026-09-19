package com.anuragbhandary.jobradar.digest;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DescriptionTrimmerTest {

    @Test
    @DisplayName("a short description is kept whole")
    void shortIsWhole() {
        assertThat(DescriptionTrimmer.trim("Build APIs.\n\n\n\nIn Java."))
                .isEqualTo("Build APIs.\n\nIn Java.");
    }

    @Test
    @DisplayName("a long one keeps the opening and the requirements, and marks the cut")
    void keepsOpeningAndRequirements() {
        String story = "About us. ".repeat(400);
        String text = "You will build payment APIs.\n\n" + story
                + "\nRequirements\n\nJava and Kafka.\n\n" + "Benefits. ".repeat(300);

        String out = DescriptionTrimmer.trim(text);

        assertThat(out).startsWith("You will build payment APIs.");
        assertThat(out).contains("[…]").contains("Requirements\n\nJava and Kafka.");
        assertThat(out.length()).isLessThanOrEqualTo(DescriptionTrimmer.MAX + 20);
    }

    @Test
    @DisplayName("no requirements heading means the first part is kept")
    void noHeadingKeepsTheStart() {
        String out = DescriptionTrimmer.trim("word ".repeat(1000));
        assertThat(out).endsWith("[…]");
        assertThat(out.length()).isLessThanOrEqualTo(DescriptionTrimmer.MAX + 20);
    }

    @Test
    @DisplayName("a run-in heading in a one-paragraph description is found")
    void runInHeading() {
        String text = "You will build trading systems. " + "Our culture is great. ".repeat(150)
                + "What you need to succeed Java or C++ experience. " + "Perks. ".repeat(300);
        assertThat(DescriptionTrimmer.trim(text)).contains("What you need to succeed Java or C++");
    }

    @Test
    @DisplayName("requirements that start early are kept from the top")
    void earlyRequirementsKept() {
        String text = "Requirements: Java and Kafka. " + "Detail. ".repeat(100)
                + "Preferred qualifications: Go. " + "Perks. ".repeat(400);
        assertThat(DescriptionTrimmer.trim(text)).startsWith("Requirements: Java and Kafka.");
    }

    @Test
    @DisplayName("nothing stored says so")
    void missing() {
        assertThat(DescriptionTrimmer.trim(null)).isEqualTo("(no description stored)");
    }
}
