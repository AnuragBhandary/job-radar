package com.anuragbhandary.jobradar.resume;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ResumeSelectionTest {

    private static ResumeSource.Bullet b(String text) {
        return new ResumeSource.Bullet(text, List.of(), null);
    }

    private static final ResumeSource SOURCE = new ResumeSource(
            "Backend Engineer",
            List.of(new ResumeSource.Summary("backend", List.of(), "Backend summary."),
                    new ResumeSource.Summary("data", List.of(), "Data summary.")),
            List.of(),
            List.of(new ResumeSource.Job("Acme", "Engineer", "Remote", "2025", null,
                    List.of(b("J1"), b("J2"), b("J3"), b("J4"), b("J5")))),
            List.of(new ResumeSource.Project("Alpha", "Java", List.of(), List.of(b("A1"), b("A2"))),
                    new ResumeSource.Project("Beta", "Python", List.of(), List.of(b("B1"), b("B2"))),
                    new ResumeSource.Project("Gamma", "Go", List.of(), List.of(b("C1")))),
            List.of(), List.of(), 2, 4, 1);

    private static List<String> texts(List<ResumeSource.Bullet> bullets) {
        return bullets.stream().map(ResumeSource.Bullet::text).toList();
    }

    @Test
    @DisplayName("with no picks, the defaults and caps apply")
    void defaults() {
        ResumeSelection.Selected s = new ResumeSelection(null, List.of()).apply(SOURCE);
        assertThat(s.summary().id()).isEqualTo("backend");
        assertThat(texts(s.experience().getFirst().bullets())).containsExactly("J1", "J2", "J3", "J4");
        assertThat(s.projects()).extracting(ResumeSource.Project::name).containsExactly("Alpha", "Beta");
        assertThat(texts(s.projects().getFirst().bullets())).containsExactly("A1");
    }

    @Test
    @DisplayName("picked bullets print exactly, in the order given")
    void picksOrderAndSelect() {
        ResumeSelection.Selected s = new ResumeSelection("data",
                List.of("e1.5", "e1.2", "p2.2", "p3.1", "p2.1")).apply(SOURCE);
        assertThat(s.summary().id()).isEqualTo("data");
        assertThat(texts(s.experience().getFirst().bullets())).containsExactly("J5", "J2");
        assertThat(s.projects()).extracting(ResumeSource.Project::name).containsExactly("Beta", "Gamma");
        assertThat(texts(s.projects().getFirst().bullets())).containsExactly("B2", "B1");
    }

    @Test
    @DisplayName("an unknown reference is an error, never skipped")
    void unknownIsAnError() {
        assertThatThrownBy(() -> new ResumeSelection(null, List.of("e1.9")).apply(SOURCE))
                .hasMessageContaining("e1.9");
        assertThatThrownBy(() -> new ResumeSelection(null, List.of("x1")).apply(SOURCE))
                .hasMessageContaining("Not a bullet reference");
        assertThatThrownBy(() -> new ResumeSelection("nope", List.of()).apply(SOURCE))
                .hasMessageContaining("No summary");
    }
}
