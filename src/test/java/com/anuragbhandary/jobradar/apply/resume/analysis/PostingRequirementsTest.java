package com.anuragbhandary.jobradar.apply.resume.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PostingRequirementsTest {

    private static Map<String, Requirement> read(String title, String description) {
        return PostingRequirements.extract(title, description).stream()
                .collect(Collectors.toMap(Requirement::term, Function.identity()));
    }

    @Test
    @DisplayName("a heading sets how strongly what follows is asked for")
    void sectionsSetImportance() {
        Map<String, Requirement> found = read("Software Engineer", """
                About us
                We build payment infrastructure with Elasticsearch.
                Requirements:
                - Java and Spring Boot
                - PostgreSQL
                Nice to have:
                - Kubernetes
                Benefits
                - Free lunch""");

        assertThat(found.get("java").importance()).isEqualTo(RequirementImportance.REQUIRED);
        assertThat(found.get("spring boot").importance()).isEqualTo(RequirementImportance.REQUIRED);
        assertThat(found.get("postgresql").importance()).isEqualTo(RequirementImportance.REQUIRED);
        assertThat(found.get("kubernetes").importance()).isEqualTo(RequirementImportance.PREFERRED);
        assertThat(found.get("elasticsearch").importance()).isEqualTo(RequirementImportance.SIGNAL);
        // "spring" inside "spring boot" is the same mention.
        assertThat(found).doesNotContainKey("spring");
    }

    @Test
    @DisplayName("a sentence can mark itself preferred inside a requirements section")
    void inlinePreference() {
        Map<String, Requirement> found = read("Engineer", """
                Requirements:
                - Strong Java.
                - Experience with Kafka is a plus.""");

        assertThat(found.get("java").importance()).isEqualTo(RequirementImportance.REQUIRED);
        assertThat(found.get("kafka").importance()).isEqualTo(RequirementImportance.PREFERRED);
    }

    @Test
    @DisplayName("an inline 'Preferred:' in run-on text, the way Amazon postings arrive")
    void inlinePreferredHeading() {
        Map<String, Requirement> found = read("Software Development Engineer",
                "- Experience designing systems - Knowledge of parallel computing\n\n"
                        + "Preferred: - Experience with code reviews and testing - "
                        + "Experience programming with C, C++ or Rust Amazon is an equal "
                        + "opportunities employer.");

        assertThat(found.get("rust").importance()).isEqualTo(RequirementImportance.PREFERRED);
        assertThat(found.get("c++").importance()).isEqualTo(RequirementImportance.PREFERRED);
        assertThat(found.get("code review").importance()).isEqualTo(RequirementImportance.PREFERRED);
    }

    @Test
    @DisplayName("what the title names is required, because the title is the job")
    void titleIsRequired() {
        Map<String, Requirement> found = read("Java Backend Engineer", "You will write code.");

        assertThat(found.get("java").importance()).isEqualTo(RequirementImportance.REQUIRED);
        assertThat(found.get("backend engineering").importance())
                .isEqualTo(RequirementImportance.REQUIRED);
    }

    @Test
    @DisplayName("with no headings everything is a signal, not a guess at a requirement")
    void noHeadingsMeansSignal() {
        Map<String, Requirement> found = read("Engineer",
                "You'll work with Kotlin, Kafka, Kubernetes and Docker.");

        assertThat(found.values()).extracting(Requirement::importance)
                .containsOnly(RequirementImportance.SIGNAL);
    }

    @Test
    @DisplayName("every quote is found verbatim in the posting")
    void quotesAreGrounded() {
        String title = "Backend Engineer, Payments";
        String description = """
                Requirements:
                - You’ll design and build RESTful APIs in Java.
                - Solid grasp of distributed systems and Aurora Postgres.
                Nice to have: on-call experience and Kubernetes.""";

        List<Requirement> found = PostingRequirements.extract(title, description);
        String text = PostingRequirements.groundingText(title, description);

        assertThat(found).isNotEmpty();
        assertThat(found).allSatisfy(r ->
                assertThat(QuoteGrounding.isGrounded(text, r.quote())).as(r.term()).isTrue());
        assertThat(found).extracting(Requirement::term)
                .contains("REST APIs", "distributed systems", "postgresql", "on-call");
    }

    @Test
    @DisplayName("ordinary words that are also technologies need a capital to count")
    void ambiguousWords() {
        assertThat(read("Engineer", "The rest of the team will go beyond this spring."))
                .doesNotContainKeys("go", "spring", "rest");
        assertThat(read("Engineer", "Services written in Go.")).containsKey("go");
    }

    @Test
    @DisplayName("a model's wording is reduced to the name the index knows, and no further")
    void canonicalSubject() {
        assertThat(PostingRequirements.canonicalSubject("Strong Java experience")).isEqualTo("java");
        assertThat(PostingRequirements.canonicalSubject("Apache Kafka")).isEqualTo("kafka");
        assertThat(PostingRequirements.canonicalSubject("Postgres")).isEqualTo("postgresql");
        assertThat(PostingRequirements.canonicalSubject("design REST APIs")).isEqualTo("REST APIs");
        assertThat(PostingRequirements.canonicalSubject("Kafka Streams")).isEqualTo("Kafka Streams");
    }
}
