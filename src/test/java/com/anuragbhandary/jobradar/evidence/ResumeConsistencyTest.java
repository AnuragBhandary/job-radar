package com.anuragbhandary.jobradar.evidence;

import static com.anuragbhandary.jobradar.evidence.EvidenceFixtures.BANK_YAML;
import static org.assertj.core.api.Assertions.assertThat;

import com.anuragbhandary.jobradar.apply.resume.ResumeModel;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;

/** The resume may say nothing the bank does not approve. */
class ResumeConsistencyTest {

    private static final ResumeModel RESUME = EvidenceFixtures.resume();

    @Test
    @DisplayName("the fixture bank backs every bullet of the fixture resume")
    void consistent() {
        ResumeConsistency consistency = ResumeConsistency.check(EvidenceFixtures.bank(), RESUME);

        assertThat(consistency.problems()).isEmpty();
        assertThat(consistency.consistent()).isTrue();
    }

    @Test
    @DisplayName("a resume bullet the bank does not approve is drift, and named")
    void driftIsAnError() {
        EvidenceBank edited = EvidenceFixtures.bank(BANK_YAML.replace(
                EvidenceFixtures.CACHE, "Reduced report generation time by caching results in Redis."
        ).replace("    metrics: [roughly fourfold]\n", ""));

        assertThat(edited.problems()).as("the edited file itself is valid").isEmpty();

        ResumeConsistency consistency = ResumeConsistency.check(edited, RESUME);

        assertThat(consistency.consistent()).isFalse();
        assertThat(consistency.errors()).singleElement()
                .satisfies(p -> assertThat(p.message()).contains("Reduced report generation time roughly fourfold"));
    }

    @Test
    @DisplayName("a resume bullet equal to an unapproved variant is not backed")
    void unapprovedDoesNotBack() {
        ResumeModel resume = withFirstJobBullet(EvidenceFixtures.UNAPPROVED);

        assertThat(ResumeConsistency.check(EvidenceFixtures.bank(), resume).consistent()).isFalse();
        assertThat(ResumeConsistency.check(EvidenceFixtures.bank(),
                withFirstJobBullet(EvidenceFixtures.EVENTS_FIRST)).consistent())
                .as("an approved variant backs it").isTrue();
    }

    @Test
    @DisplayName("a job with no source, and a stack entry the resume never mentions, are errors")
    void missingSourceAndStack() {
        EvidenceBank renamed = EvidenceFixtures.bank(BANK_YAML.replace("name: Acme Streaming", "name: Acme"));
        EvidenceBank stack = EvidenceFixtures.bank(BANK_YAML.replace(
                "stack: [Python, FastAPI, Kafka, WebSockets, Redis]",
                "stack: [Python, FastAPI, Kafka, WebSockets, Redis, Kubernetes]"));

        assertThat(ResumeConsistency.check(renamed, RESUME).errors())
                .anySatisfy(p -> assertThat(p.message()).contains("no employment source named 'Acme Streaming'"));
        assertThat(ResumeConsistency.check(stack, RESUME).errors()).singleElement()
                .satisfies(p -> assertThat(p.message()).contains("'Kubernetes'"));
    }

    @Test
    @DisplayName("a source that is not on the resume is a warning: the bank may hold more than the resume")
    void extraSourceIsAWarning() {
        EvidenceBank bank = EvidenceFixtures.bank(BANK_YAML.replace("""
                  - id: site
                    kind: project
                    name: Static Site
                """, """
                  - id: site
                    kind: project
                    name: Static Site
                  - id: old
                    kind: project
                    name: An Old Project
                """));

        ResumeConsistency consistency = ResumeConsistency.check(bank, RESUME);

        assertThat(consistency.consistent()).isTrue();
        assertThat(consistency.problems()).singleElement()
                .satisfies(p -> assertThat(p.severity()).isEqualTo(EvidenceProblem.Severity.WARNING));
    }

    @Test
    @DisplayName("the committed example bank is valid and matches the committed example profile")
    void committedExamplesAgree() throws IOException {
        EvidenceBank bank = EvidenceBankLoader.load(Path.of("evidence.example.yml"));
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("example", new FileSystemResource("applicant.example.yml"));
        ResumeModel resume = new Binder(ConfigurationPropertySources.from(sources))
                .bind("job-radar.resume", ResumeModel.class).get();

        assertThat(Files.exists(Path.of("evidence.example.yml"))).isTrue();
        assertThat(bank.problems()).isEmpty();
        assertThat(bank.items()).isNotEmpty();
        assertThat(bank.items()).anySatisfy(item -> assertThat(item.approvedVariants()).isNotEmpty());
        assertThat(ResumeConsistency.check(bank, resume).errors()).isEmpty();
    }

    private static ResumeModel withFirstJobBullet(String text) {
        ResumeModel.Job job = RESUME.experience().getFirst();
        List<ResumeModel.Bullet> bullets = new java.util.ArrayList<>(job.bullets());
        bullets.set(0, new ResumeModel.Bullet(text, List.of("kafka")));
        return new ResumeModel(RESUME.headline(), RESUME.summaries(), RESUME.skills(),
                List.of(new ResumeModel.Job(job.company(), job.title(), job.location(), job.period(),
                        job.note(), bullets)),
                RESUME.projects(), RESUME.education(), RESUME.extras(), 2, 3, 2);
    }
}
