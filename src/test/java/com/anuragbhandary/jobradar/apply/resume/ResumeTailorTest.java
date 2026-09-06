package com.anuragbhandary.jobradar.apply.resume;

import static org.assertj.core.api.Assertions.assertThat;

import com.anuragbhandary.jobradar.domain.Posting;
import com.anuragbhandary.jobradar.domain.Source;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ResumeTailorTest {

    private static final ResumeModel RESUME = new ResumeModel(
            "Backend Software Engineer",
            List.of(
                    new ResumeModel.Summary("default", List.of("backend"), "General."),
                    new ResumeModel.Summary("java", List.of("java", "spring boot"), "Java one."),
                    new ResumeModel.Summary("python", List.of("python", "fastapi"), "Python one.")),
            List.of(new ResumeModel.SkillGroup("Languages", List.of("Java", "Python"))),
            List.of(new ResumeModel.Job("Acme", "Engineer", "Remote", "2025-2026", "note",
                    List.of(new ResumeModel.Bullet("Built things.", List.of("kafka"))))),
            List.of(
                    new ResumeModel.Project("Java Scheduler", "Java · Spring Boot",
                            List.of("java", "spring boot", "kafka"),
                            List.of(new ResumeModel.Bullet("Schedules jobs.", List.of("java")))),
                    new ResumeModel.Project("Python Docs", "Python · FastAPI",
                            List.of("python", "fastapi"),
                            List.of(new ResumeModel.Bullet("Processes docs.", List.of("python")))),
                    new ResumeModel.Project("Risk Platform", "Java · Python",
                            List.of("java", "python", "ml"),
                            List.of(new ResumeModel.Bullet("Scores risk.", List.of("ml"))))),
            List.of(new ResumeModel.Education("M.S.", "A University", "Somewhere",
                    "2023-2025", "GPA")),
            List.of("LeetCode"),
            2, 4, 3);

    private final ResumeTailor tailor = new ResumeTailor(RESUME);

    private static Posting posting(String title, String description) {
        Posting posting = new Posting(Source.GREENHOUSE, "acme", "1", title);
        posting.setDescriptionText(description);
        return posting;
    }

    @Test
    @DisplayName("a Java posting leads with the Java project and the Java summary")
    void ordersByRelevance() {
        TailoredResume tailored = tailor.tailor(
                posting("Java Backend Engineer",
                        "You will work with Spring Boot and Kafka on our platform."));

        assertThat(tailored.summary().id()).isEqualTo("java");
        assertThat(tailored.projects().getFirst().name()).isEqualTo("Java Scheduler");
    }

    @Test
    void aPythonPostingReordersTheSameMaterial() {
        TailoredResume tailored = tailor.tailor(
                posting("Python Engineer", "FastAPI services and async workers."));

        assertThat(tailored.summary().id()).isEqualTo("python");
        assertThat(tailored.projects().getFirst().name()).isEqualTo("Python Docs");
    }

    @Test
    @DisplayName("a posting matching nothing gets the safe default, not an arbitrary choice")
    void unmatchedPostingFallsBackToTheFirstSummary() {
        TailoredResume tailored = tailor.tailor(
                posting("Engineer", "We are looking for someone great."));

        assertThat(tailored.summary().id()).isEqualTo("default");
    }

    @Test
    @DisplayName("projects beyond max-projects are dropped and named")
    void dropsToFitOnePage() {
        TailoredResume tailored = tailor.tailor(
                posting("Java Engineer", "Spring Boot."));

        assertThat(tailored.projects()).hasSize(2);
        assertThat(tailored.droppedProjects()).hasSize(1);
        assertThat(tailored.note()).contains("dropped");
    }

    @Test
    @DisplayName("the note says what was changed, in words a human can check")
    void noteIsReadable() {
        TailoredResume tailored = tailor.tailor(
                posting("Java Backend Engineer", "Kafka and Spring Boot."));

        assertThat(tailored.note())
                .contains("summary 'java'")
                .contains("led with Java Scheduler");
        assertThat(tailored.matchedTags()).contains("java", "kafka", "spring boot");
    }

    // -----------------------------------------------------------------------
    // Word-boundary matching. The same class of bug as the "distributed" match
    // in the geography filter, and it surfaces here as an unexplainable order.
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("only the most relevant bullets survive, in their written order")
    void selectsBulletsByRelevanceButKeepsNarrativeOrder() {
        ResumeModel resume = new ResumeModel(
                "H", List.of(new ResumeModel.Summary("default", List.of(), "S.")),
                List.of(), List.of(), 
                List.of(new ResumeModel.Project("P", "stack", List.of(),
                        List.of(
                                new ResumeModel.Bullet("first", List.of("irrelevant")),
                                new ResumeModel.Bullet("second", List.of("kafka")),
                                new ResumeModel.Bullet("third", List.of("irrelevant")),
                                new ResumeModel.Bullet("fourth", List.of("redis"))))),
                List.of(), List.of(), 1, 4, 2);

        TailoredResume tailored = new ResumeTailor(resume)
                .tailor(posting("Engineer", "We run Kafka and Redis."));

        // Chosen by score, then put back in the order they were written - a
        // bullet list re-sorted by relevance reads as machine-assembled.
        assertThat(tailored.projects().getFirst().bullets())
                .extracting(ResumeModel.Bullet::text)
                .containsExactly("second", "fourth");
    }

    @Test
    @DisplayName("with nothing matching, the opening bullets are kept")
    void tiesFallToTheWrittenOrder() {
        ResumeModel resume = new ResumeModel(
                "H", List.of(new ResumeModel.Summary("default", List.of(), "S.")),
                List.of(), List.of(),
                List.of(new ResumeModel.Project("P", "stack", List.of(),
                        List.of(
                                new ResumeModel.Bullet("a", List.of("x")),
                                new ResumeModel.Bullet("b", List.of("y")),
                                new ResumeModel.Bullet("c", List.of("z"))))),
                List.of(), List.of(), 1, 4, 2);

        TailoredResume tailored = new ResumeTailor(resume)
                .tailor(posting("Engineer", "Nothing in common."));

        assertThat(tailored.projects().getFirst().bullets())
                .extracting(ResumeModel.Bullet::text)
                .containsExactly("a", "b");
    }

    @Test
    @DisplayName("tags match whole words only")
    void doesNotMatchInsideOtherWords() {
        assertThat(ResumeTailor.mentions("we use java", "java")).isTrue();
        assertThat(ResumeTailor.mentions("javascript everywhere", "java")).isFalse();
        assertThat(ResumeTailor.mentions("strong go skills", "go")).isTrue();
        assertThat(ResumeTailor.mentions("django and algorithms", "go")).isFalse();
        assertThat(ResumeTailor.mentions("c++ and rust", "c")).isFalse();
    }

    @Test
    @DisplayName("a tag at the end of a sentence still matches")
    void trailingPunctuationDoesNotBreakMatching() {
        // A full stop must not count as a word character, or every tag that
        // happens to end a sentence scores zero and nothing says so.
        assertThat(ResumeTailor.mentions("experience with spring boot.", "spring boot")).isTrue();
        assertThat(ResumeTailor.mentions("we use kafka, redis and go.", "go")).isTrue();
        // ...while node.js stays one token.
        assertThat(ResumeTailor.mentions("node.js on the backend", "node")).isFalse();
        assertThat(ResumeTailor.mentions("node.js on the backend", "node.js")).isTrue();
    }
}
