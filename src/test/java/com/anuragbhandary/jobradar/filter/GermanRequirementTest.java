package com.anuragbhandary.jobradar.filter;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class GermanRequirementTest {

    /** From the real corpus. */
    @ParameterizedTest
    @ValueSource(strings = {
            "What we look for: Fluent German and business-level English. Both are used daily.",
            "Native-level or near-native German language skills (C1/C2), with the ability to present.",
            "Language: Strong proficiency in German is required.",
            "Sehr gute Deutschkenntnisse (C1) sowie gute Englischkenntnisse (B2) bringst du mit.",
            "Gute Deutschkenntnisse in Wort und Schrift, Englischkenntnisse von Vorteil.",
            "Qualifications: Fluency in German is mandatory. Bilingual in English is required.",
    })
    void findsARequirement(String text) {
        assertThat(GermanRequirement.find(text)).isPresent();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Nice to Have: Full professional proficiency in English and German.",
            "German is a plus. Strong English communication skills required.",
            "Good German skills are an advantage.",
            "We build good software for German and Dutch customers in English.",
            "Our team speaks English; German is welcome but not needed.",
    })
    void ignoresAWish(String text) {
        assertThat(GermanRequirement.find(text)).isEmpty();
    }

    @Test
    @DisplayName("a posting written in German needs German")
    void germanPosting() {
        String text = ("Wir suchen dich als Entwickler für unser Team. Du arbeitest mit uns an der "
                + "Plattform und bist Teil von einem Team, das sich auf die Qualität der Software "
                + "konzentriert. ").repeat(3);
        assertThat(GermanRequirement.find(text)).contains("posting written in German");
    }

    @Test
    @DisplayName("an English posting that names a German city is not a German posting")
    void englishPosting() {
        String text = ("We are looking for a backend engineer to join our team in Berlin. You will "
                + "work with Java and Kafka on the payments platform and the data pipeline. ").repeat(3);
        assertThat(GermanRequirement.find(text)).isEmpty();
    }
}
