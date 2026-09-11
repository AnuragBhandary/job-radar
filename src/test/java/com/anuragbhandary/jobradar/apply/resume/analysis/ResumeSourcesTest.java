package com.anuragbhandary.jobradar.apply.resume.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.anuragbhandary.jobradar.apply.TestResumes;
import com.anuragbhandary.jobradar.apply.resume.ResumeModel;
import com.anuragbhandary.jobradar.apply.resume.ResumeTailor;
import com.anuragbhandary.jobradar.apply.resume.TailoredResume;
import com.anuragbhandary.jobradar.domain.Posting;
import com.anuragbhandary.jobradar.domain.Source;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/**
 * Source ids exist so a future rewrite can name the bullet it came from. These
 * tests are the properties that makes true: unique, stable, and reachable from the
 * tailored resume that actually gets rendered.
 */
class ResumeSourcesTest {

    private static ResumeModel withProjectBullets(List<ResumeModel.Bullet> bullets) {
        ResumeModel base = TestResumes.backendResume();
        return new ResumeModel(base.headline(), base.summaries(), base.skills(),
                base.experience(),
                List.of(new ResumeModel.Project("Scheduler", "Java · Kafka",
                        List.of("java"), bullets)),
                base.education(), base.extras(), 3, 4, 3);
    }

    private static Posting posting(String title, String description) {
        Posting posting = new Posting(Source.GREENHOUSE, "acme", "1", title);
        posting.setDescriptionText(description);
        return posting;
    }

    @Test
    @DisplayName("every item has an id, and no two share one")
    void idsAreUnique() {
        ResumeSources sources = new ResumeSources(TestResumes.backendResume());
        List<String> ids = sources.all().stream().map(ResumeSources.SourceItem::id).toList();

        assertThat(ids).isNotEmpty().doesNotHaveDuplicates();
        assertThat(ids).anyMatch(id -> id.startsWith("job-an-employer-"));
        assertThat(ids).anyMatch(id -> id.startsWith("proj-distributed-job-scheduler-"));
        assertThat(ids).contains("proj-distributed-job-scheduler-stack", "skills-languages");
    }

    @Test
    @DisplayName("the same resume gives the same ids every time")
    void idsAreStableAcrossRuns() {
        List<String> first = new ResumeSources(TestResumes.backendResume()).all().stream()
                .map(ResumeSources.SourceItem::id).toList();
        List<String> second = new ResumeSources(TestResumes.backendResume()).all().stream()
                .map(ResumeSources.SourceItem::id).toList();

        assertThat(second).isEqualTo(first);
    }

    @Test
    @DisplayName("reordering bullets does not move an id to a different sentence")
    void idsDoNotDependOnPosition() {
        ResumeModel.Bullet a = new ResumeModel.Bullet("Built the scheduler.", List.of("java"));
        ResumeModel.Bullet b = new ResumeModel.Bullet("Added Kafka replay.", List.of("kafka"));

        ResumeSources forward = new ResumeSources(withProjectBullets(List.of(a, b)));
        ResumeSources reversed = new ResumeSources(withProjectBullets(List.of(b, a)));

        assertThat(forward.idOf(a)).isEqualTo(reversed.idOf(a));
        assertThat(forward.idOf(b)).isEqualTo(reversed.idOf(b));
        assertThat(forward.idOf(a)).isNotEqualTo(forward.idOf(b));
    }

    @Test
    @DisplayName("an id written in applicant.yml is used as written")
    void explicitIdIsHonoured() {
        ResumeModel.Bullet written = new ResumeModel.Bullet("Built the scheduler.", List.of(),
                "scheduler-core");
        ResumeSources sources = new ResumeSources(withProjectBullets(List.of(written)));

        assertThat(sources.find("scheduler-core")).isPresent();
        assertThat(sources.idOf(written)).contains("scheduler-core");
    }

    @Test
    @DisplayName("two bullets with one written id refuse to start")
    void duplicateExplicitIdsFail() {
        ResumeModel resume = withProjectBullets(List.of(
                new ResumeModel.Bullet("One.", List.of(), "same"),
                new ResumeModel.Bullet("Two.", List.of(), "same")));

        assertThatThrownBy(() -> new ResumeSources(resume))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("used twice");
    }

    @Test
    @DisplayName("an id that could not be passed back safely is refused")
    void malformedIdFails() {
        ResumeModel resume = withProjectBullets(List.of(
                new ResumeModel.Bullet("One.", List.of(), "has spaces in it")));

        assertThatThrownBy(() -> new ResumeSources(resume))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("identical sentences in one place still get distinct ids")
    void identicalTextGetsDistinctIds() {
        ResumeModel.Bullet a = new ResumeModel.Bullet("Same words.", List.of());
        ResumeModel.Bullet b = new ResumeModel.Bullet("Same words.", List.of());
        ResumeSources sources = new ResumeSources(withProjectBullets(List.of(a, b)));

        assertThat(sources.idOf(a)).isPresent();
        assertThat(sources.idOf(a)).isNotEqualTo(sources.idOf(b));
    }

    @Test
    @DisplayName("every bullet the tailor keeps resolves to its source id")
    void tailoredBulletsKeepTheirIds() {
        ResumeModel resume = TestResumes.backendResume();
        ResumeSources sources = new ResumeSources(resume);
        TailoredResume tailored = new ResumeTailor(resume)
                .tailor(posting("Java Backend Engineer", "Kafka and Spring Boot."));

        List<ResumeModel.Bullet> kept = new ArrayList<>();
        tailored.experience().forEach(job -> kept.addAll(job.bullets()));
        tailored.projects().forEach(project -> kept.addAll(project.bullets()));

        assertThat(kept).isNotEmpty();
        for (ResumeModel.Bullet bullet : kept) {
            String id = sources.idOf(bullet).orElseThrow();
            assertThat(sources.find(id).orElseThrow().text()).isEqualTo(bullet.text());
        }
    }

    @Test
    @DisplayName("ids change nothing the tailor chooses or the renderer prints")
    void tailoredOutputIsUnchangedByIds() {
        ResumeModel plain = TestResumes.backendResume();
        List<ResumeModel.Job> jobs = plain.experience().stream().map(job ->
                new ResumeModel.Job(job.company(), job.title(), job.location(), job.period(),
                        job.note(), job.bullets().stream().map(b ->
                                new ResumeModel.Bullet(b.text(), b.tags(), "id-" + b.text().length()))
                                .toList()))
                .toList();
        ResumeModel withIds = new ResumeModel(plain.headline(), plain.summaries(), plain.skills(),
                jobs, plain.projects(), plain.education(), plain.extras(), 3, 4, 3);
        Posting posting = posting("Java Backend Engineer", "Kafka, WebSockets and Spring Boot.");

        TailoredResume a = new ResumeTailor(plain).tailor(posting);
        TailoredResume b = new ResumeTailor(withIds).tailor(posting);

        assertThat(b.summary()).isEqualTo(a.summary());
        assertThat(b.projects()).isEqualTo(a.projects());
        assertThat(b.matchedTags()).isEqualTo(a.matchedTags());
        assertThat(b.experience().getFirst().bullets()).extracting(ResumeModel.Bullet::text)
                .isEqualTo(a.experience().getFirst().bullets().stream()
                        .map(ResumeModel.Bullet::text).toList());
    }

    @Test
    @DisplayName("applicant.yml binds with or without an id on a bullet")
    void bindsFromConfiguration() {
        Map<String, Object> yaml = Map.of(
                "job-radar.resume.summaries[0].id", "default",
                "job-radar.resume.summaries[0].text", "Engineer.",
                "job-radar.resume.projects[0].name", "Scheduler",
                "job-radar.resume.projects[0].stack", "Java",
                "job-radar.resume.projects[0].bullets[0].text", "Built it.",
                "job-radar.resume.projects[0].bullets[0].id", "sched-1",
                "job-radar.resume.projects[0].bullets[1].text", "Tested it.");

        ResumeModel resume = new Binder(new MapConfigurationPropertySource(yaml))
                .bind("job-radar.resume", ResumeModel.class).get();

        List<ResumeModel.Bullet> bullets = resume.projects().getFirst().bullets();
        assertThat(bullets.get(0).id()).isEqualTo("sched-1");
        assertThat(bullets.get(1).id()).isNull();
        assertThat(bullets.get(1).tags()).isEmpty();
        assertThat(new HashSet<>(new ResumeSources(resume).all().stream()
                .map(ResumeSources.SourceItem::id).toList())).contains("sched-1");
    }
}
