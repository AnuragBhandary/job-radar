package com.anuragbhandary.jobradar.apply.resume.rewrite;

import static org.assertj.core.api.Assertions.assertThat;

import com.anuragbhandary.jobradar.apply.resume.rewrite.RewriteQuality.Label;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** "More job keywords" is not "better". These are the patterns that say so. */
class RewriteQualityTest {

    @Test
    @DisplayName("a technology tacked onto the end is found, even after other small edits")
    void appendedSuffix() {
        assertThat(RewriteQuality.appendedSuffix(
                "Implemented retry handling for reliable backend workflows.",
                "Implemented retries for reliable backend workflows using python.")).isPresent();
        assertThat(RewriteQuality.appendedSuffix("Built the scheduler.",
                "Built the scheduler with Kafka and Redis.")).contains("with Kafka and Redis");
    }

    @Test
    @DisplayName("a technology the sentence already had, or one worked into it, is not stuffing")
    void naturalUseIsNotStuffing() {
        assertThat(RewriteQuality.appendedSuffix("Stored results in PostgreSQL.",
                "Stored processing results in PostgreSQL.")).isEmpty();
        assertThat(RewriteQuality.appendedSuffix("Built a Kafka-based replay system.",
                "Built an event-driven, Kafka-based replay system.")).isEmpty();
        assertThat(RewriteQuality.appendedSuffix("Built the upload service.",
                "Built the upload service in a small team of engineers.")).isEmpty();
    }

    @Test
    @DisplayName("product names in lower case are noticed")
    void lowercaseNames() {
        assertThat(RewriteQuality.lowercaseNames("Integrated containerised deployment.",
                "Integrated docker containerised deployment.")).containsExactly("docker");
        assertThat(RewriteQuality.lowercaseNames("Built APIs.", "Built rest apis.")).contains("rest apis");
        assertThat(RewriteQuality.lowercaseNames("Built APIs.", "Built REST APIs in Python.")).isEmpty();
    }

    @Test
    @DisplayName("labels follow the text, not the keyword count")
    void classify() {
        assertThat(RewriteQuality.classify("UNCHANGED", "a", "a", 0, 0, 0, 0)).isEqualTo(Label.UNCHANGED);
        assertThat(RewriteQuality.classify("ACCEPTED", "Built the scheduler.",
                "Built the scheduler using Kafka.", 0, 1, 0, 1)).isEqualTo(Label.STUFFED);
        assertThat(RewriteQuality.classify("ACCEPTED", "Integrated containerised deployment.",
                "Integrated docker containerised deployment.", 0, 1, 0, 2)).isEqualTo(Label.UNNATURAL);
        assertThat(RewriteQuality.classify("ACCEPTED", "Implemented retry handling.",
                "Implemented retries.", 0, 0, 0, 0)).isEqualTo(Label.COSMETIC);
        assertThat(RewriteQuality.classify("ACCEPTED", "Built a Kafka replay system.",
                "Built an event-driven Kafka replay system.", 0, 1, 1, 2))
                .isEqualTo(Label.CANDIDATE_IMPROVEMENT);
        assertThat(RewriteQuality.classify("MODEL_FAILED", "a", null, 0, 0, 0, 0))
                .isEqualTo(Label.MODEL_FAILED);
    }
}
