package com.anuragbhandary.jobradar.prep;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TechVocabularyTest {

    @Test
    void findsTheTechnologiesInADescription() {
        assertThat(TechVocabulary.found(
                "You will work with Java, Spring Boot and Kafka on top of PostgreSQL, "
                        + "deployed to Kubernetes on AWS."))
                .contains("java", "spring boot", "kafka", "postgresql", "kubernetes", "aws");
    }

    @Test
    @DisplayName("short names match as whole words only")
    void shortNamesDoNotMatchInsideOtherWords() {
        // "go" is inside "django" and "algorithm"; "c" is inside almost everything.
        assertThat(TechVocabulary.found("We use Django and good algorithms"))
                .doesNotContain("go");
        assertThat(TechVocabulary.found("Strong Go skills required")).contains("go");
        assertThat(TechVocabulary.found("C++ and Rust")).contains("c++", "rust");
        assertThat(TechVocabulary.found("Category theory")).doesNotContain("c");
    }

    @Test
    @DisplayName("a technology at the end of a sentence still matches")
    void trailingPunctuationDoesNotBreakMatching() {
        // Technologies are usually listed, so they usually end sentences.
        assertThat(TechVocabulary.found("Experience with Spring Boot."))
                .contains("spring boot");
        assertThat(TechVocabulary.found("We run Kafka, Redis and Go."))
                .contains("kafka", "redis", "go");
    }

    @Test
    void keepsDottedNamesTogether() {
        assertThat(TechVocabulary.found("Node.js on the backend"))
                .contains("node.js").doesNotContain("go");
    }

    @Test
    @DisplayName("the more specific name is reported alongside the family")
    void multiWordTermsAreFound() {
        assertThat(TechVocabulary.found("Spring Boot, Spring Data and Spring Security"))
                .contains("spring boot", "spring data", "spring security", "spring");
    }

    @Test
    void emptyInputFindsNothing() {
        assertThat(TechVocabulary.found(null)).isEmpty();
        assertThat(TechVocabulary.found("")).isEmpty();
        assertThat(TechVocabulary.found("We are looking for a great teammate.")).isEmpty();
    }
}
