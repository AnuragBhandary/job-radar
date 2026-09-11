package com.anuragbhandary.jobradar.bench;

import static org.assertj.core.api.Assertions.assertThat;

import com.anuragbhandary.jobradar.apply.resume.analysis.QuoteGrounding;
import com.anuragbhandary.jobradar.apply.resume.analysis.Requirement;
import com.anuragbhandary.jobradar.apply.resume.analysis.RequirementImportance;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The model proposes requirements; this is the layer that decides which ones
 * exist. Every test here is a way a reply can be wrong, and what must happen.
 */
class RequirementParserTest {

    private static final String POSTING = """
            Senior Backend Engineer
            Requirements:
            - 3+ years with Java and Spring Boot.
            - Experience with Kafka.
            Nice to have: Kubernetes is a plus.
            You’ll work with a friendly team.
            We offer great benefits.""";

    private static String item(String term, String category, String importance, String quote) {
        return """
                {"term":"%s","category":"%s","importance":"%s","quote":"%s"}"""
                .formatted(term, category, importance, quote);
    }

    private static String reply(String... items) {
        return "{\"requirements\":[" + String.join(",", items) + "]}";
    }

    private static Map<String, Requirement> byTerm(RequirementParser.Result result) {
        return result.accepted().stream()
                .collect(Collectors.toMap(r -> r.term().toLowerCase(), Function.identity()));
    }

    @Test
    @DisplayName("a well-formed, grounded reply is accepted whole, with its importance")
    void validOutput() {
        RequirementParser.Result result = RequirementParser.parse(reply(
                item("Java", "LANGUAGE", "REQUIRED", "3+ years with Java and Spring Boot"),
                item("Kafka", "TECHNOLOGY", "REQUIRED", "Experience with Kafka"),
                item("Kubernetes", "CLOUD", "PREFERRED", "Kubernetes is a plus")), POSTING);

        assertThat(result.schemaValid()).isTrue();
        assertThat(result.rejected()).isEmpty();
        assertThat(result.accepted()).hasSize(3);
        assertThat(byTerm(result).get("java").importance()).isEqualTo(RequirementImportance.REQUIRED);
        assertThat(byTerm(result).get("kubernetes").importance())
                .isEqualTo(RequirementImportance.PREFERRED);
        // Ids are Java's, derived from the term, never the model's.
        assertThat(byTerm(result).get("kafka").id()).isEqualTo("req-kafka");
    }

    @Test
    @DisplayName("one quote may support several requirements")
    void multipleRequirementsInOneQuote() {
        String quote = "3+ years with Java and Spring Boot";
        RequirementParser.Result result = RequirementParser.parse(reply(
                item("Java", "LANGUAGE", "REQUIRED", quote),
                item("Spring Boot", "FRAMEWORK", "REQUIRED", quote)), POSTING);

        assertThat(result.accepted()).extracting(Requirement::term)
                .containsExactly("Java", "Spring Boot");
        assertThat(result.duplicates()).isZero();
    }

    @Test
    @DisplayName("text that is not JSON is schema-invalid and yields nothing")
    void malformedOutput() {
        RequirementParser.Result result =
                RequirementParser.parse("{\"requirements\": [ {\"term\": \"Java\"", POSTING);

        assertThat(result.schemaValid()).isFalse();
        assertThat(result.error()).startsWith("not JSON");
        assertThat(result.accepted()).isEmpty();
    }

    @Test
    @DisplayName("JSON without a requirements array is schema-invalid")
    void wrongShape() {
        RequirementParser.Result result = RequirementParser.parse("{\"items\":[]}", POSTING);

        assertThat(result.schemaValid()).isFalse();
        assertThat(result.accepted()).isEmpty();
    }

    @Test
    @DisplayName("a requirement with no quote is rejected and breaks the schema")
    void missingQuote() {
        RequirementParser.Result result = RequirementParser.parse(reply(
                "{\"term\":\"Java\",\"category\":\"LANGUAGE\",\"importance\":\"REQUIRED\"}",
                item("Kafka", "TECHNOLOGY", "REQUIRED", "Experience with Kafka")), POSTING);

        assertThat(result.schemaValid()).isFalse();
        assertThat(result.count(RequirementParser.Reason.MISSING_QUOTE)).isEqualTo(1);
        assertThat(result.accepted()).extracting(Requirement::term).containsExactly("Kafka");
    }

    @Test
    @DisplayName("a quote the posting does not contain is ungrounded and never accepted")
    void quoteNotInPosting() {
        RequirementParser.Result result = RequirementParser.parse(reply(
                item("Rust", "LANGUAGE", "REQUIRED", "5+ years of Rust in production"),
                // A paraphrase is not a quote, however close.
                item("Kafka", "TECHNOLOGY", "REQUIRED", "Kafka experience")), POSTING);

        assertThat(result.schemaValid())
                .as("the reply followed the schema; the content is what was invented")
                .isTrue();
        assertThat(result.accepted()).isEmpty();
        assertThat(result.count(RequirementParser.Reason.UNGROUNDED)).isEqualTo(2);
        assertThat(result.withQuote()).isEqualTo(2);
        assertThat(result.grounded()).isZero();
    }

    @Test
    @DisplayName("duplicates merge on id and keep the stronger importance")
    void duplicateRequirements() {
        RequirementParser.Result result = RequirementParser.parse(reply(
                item("Kafka", "TECHNOLOGY", "SIGNAL", "Experience with Kafka"),
                item("kafka", "TECHNOLOGY", "REQUIRED", "Experience with Kafka")), POSTING);

        assertThat(result.accepted()).hasSize(1);
        assertThat(result.accepted().getFirst().importance()).isEqualTo(RequirementImportance.REQUIRED);
        assertThat(result.duplicates()).isEqualTo(1);
    }

    @Test
    @DisplayName("required and preferred are kept apart")
    void requiredVersusPreferred() {
        RequirementParser.Result result = RequirementParser.parse(reply(
                item("Kafka", "TECHNOLOGY", "REQUIRED", "Experience with Kafka"),
                item("Kubernetes", "CLOUD", "preferred", "Kubernetes is a plus")), POSTING);

        assertThat(result.accepted()).extracting(Requirement::importance)
                .containsExactly(RequirementImportance.REQUIRED, RequirementImportance.PREFERRED);
    }

    @Test
    @DisplayName("an empty list is a valid answer with nothing in it")
    void emptyResult() {
        RequirementParser.Result result = RequirementParser.parse("{\"requirements\":[]}", POSTING);

        assertThat(result.schemaValid()).isTrue();
        assertThat(result.proposed()).isZero();
        assertThat(result.accepted()).isEmpty();
    }

    @Test
    @DisplayName("an empty reply is a failure, not an empty list")
    void blankReply() {
        assertThat(RequirementParser.parse("", POSTING).schemaValid()).isFalse();
        assertThat(RequirementParser.parse(null, POSTING).schemaValid()).isFalse();
    }

    @Test
    @DisplayName("a category or importance outside the enum is malformed")
    void unknownEnumValue() {
        RequirementParser.Result result = RequirementParser.parse(reply(
                item("Kafka", "LIBRARY", "REQUIRED", "Experience with Kafka"),
                item("Java", "LANGUAGE", "CRITICAL", "3+ years with Java")), POSTING);

        assertThat(result.schemaValid()).isFalse();
        assertThat(result.count(RequirementParser.Reason.MALFORMED_ITEM)).isEqualTo(2);
        assertThat(result.accepted()).isEmpty();
    }

    @Test
    @DisplayName("typography and fences do not make a faithful quote ungrounded")
    void toleratesTypographyNotWording() {
        RequirementParser.Result result = RequirementParser.parse("```json\n" + reply(
                item("Teamwork", "SOFT_SKILL", "SIGNAL", "You'll work with a friendly team."),
                item("Kafka", "TECHNOLOGY", "REQUIRED", "...Experience   with Kafka..."))
                + "\n```", POSTING);

        assertThat(result.accepted()).hasSize(2);
    }

    @Test
    @DisplayName("a two-letter quote proves nothing and is rejected")
    void tooShortToGround() {
        assertThat(QuoteGrounding.isGrounded(POSTING, "a")).isFalse();
        assertThat(QuoteGrounding.isGrounded(POSTING, "Experience with Kafka")).isTrue();
        assertThat(QuoteGrounding.isGrounded(POSTING, "Experience with Kafka and Flink")).isFalse();
    }
}
