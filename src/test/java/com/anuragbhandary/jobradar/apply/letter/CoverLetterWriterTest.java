package com.anuragbhandary.jobradar.apply.letter;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The validator, tested on its own.
 *
 * <p>It is the only thing standing between a model's habits and a false claim on
 * a real application, so it is tested against what models actually write.
 */
class CoverLetterWriterTest {

    private static final int CAP = 2000;

    @Test
    @DisplayName("a claim about years of experience is rejected outright")
    void rejectsInventedExperience() {
        // The single most common thing a model adds unprompted, and the one claim
        // that must never appear: the honest figure is one year, unpaid, at a
        // company that no longer exists.
        assertThat(CoverLetterWriter.validate(
                "With over 3 years of experience in backend systems, I would...", CAP))
                .isFalse();
        assertThat(CoverLetterWriter.validate(
                "I bring 5 years' experience with Kafka.", CAP)).isFalse();
    }

    @Test
    @DisplayName("an unfilled placeholder is rejected")
    void rejectsPlaceholders() {
        assertThat(CoverLetterWriter.validate(
                "I am applying to [Company Name] for the backend role.", CAP)).isFalse();
        assertThat(CoverLetterWriter.validate(
                "Dear {{hiring_manager}}, I would like to apply.", CAP)).isFalse();
    }

    @Test
    void rejectsModelBoilerplate() {
        assertThat(CoverLetterWriter.validate(
                "I am writing to express my strong interest in this role.", CAP)).isFalse();
        assertThat(CoverLetterWriter.validate(
                "As an AI language model, I would be delighted to apply.", CAP)).isFalse();
    }

    @Test
    @DisplayName("a sign-off means the other rules were probably ignored too")
    void rejectsSignOffs() {
        assertThat(CoverLetterWriter.validate(
                "I built a Kafka replay system.\n\nSincerely,\nAda", CAP)).isFalse();
    }

    @Test
    void rejectsAnythingOverTheBoxLimit() {
        assertThat(CoverLetterWriter.validate("x".repeat(2001), CAP)).isFalse();
        assertThat(CoverLetterWriter.validate("A short honest letter.", 10)).isFalse();
    }

    @Test
    void acceptsALetterMadeOnlyOfRealClaims() {
        assertThat(CoverLetterWriter.validate("""
                I am applying for the Backend Engineer role at Acme.

                I built a Kafka-based game-event replay system with deduplication and
                ordering preservation, and FastAPI services with WebSocket streaming.

                The overlap with this posting is Kafka, FastAPI and PostgreSQL.
                """, CAP)).isTrue();
    }

    @Test
    void rejectsNothingAtAll() {
        assertThat(CoverLetterWriter.validate("", CAP)).isFalse();
        assertThat(CoverLetterWriter.validate(null, CAP)).isFalse();
        assertThat(CoverLetterWriter.validate("   ", CAP)).isFalse();
    }
}
