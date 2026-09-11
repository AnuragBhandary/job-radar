package com.anuragbhandary.jobradar.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.anuragbhandary.jobradar.apply.TestProfiles;
import com.anuragbhandary.jobradar.apply.resume.ResumeModel;
import com.anuragbhandary.jobradar.apply.resume.ResumeRenderer;
import com.anuragbhandary.jobradar.apply.resume.ResumeTailor;
import com.anuragbhandary.jobradar.apply.resume.analysis.CoverageAnalyzer;
import com.anuragbhandary.jobradar.apply.resume.analysis.ResumeSources;
import com.anuragbhandary.jobradar.apply.resume.plan.ResumePipeline;
import com.anuragbhandary.jobradar.apply.resume.plan.TailoringPlanner;
import com.anuragbhandary.jobradar.domain.Posting;
import com.anuragbhandary.jobradar.domain.Source;
import com.anuragbhandary.jobradar.evidence.EvidenceBank;
import com.anuragbhandary.jobradar.evidence.EvidenceFixtures;
import com.anuragbhandary.jobradar.evidence.EvidenceProperties;
import com.anuragbhandary.jobradar.knowledge.experience.ExperienceIndex;
import com.anuragbhandary.jobradar.knowledge.experience.ExperiencePositioner;
import com.anuragbhandary.jobradar.repo.PostingRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The {@code evidence} command, including generating a resume from the terminal. */
class EvidenceCommandTest {

    private static final ResumeModel RESUME = EvidenceFixtures.resume();

    private static EvidenceCommand command(EvidenceBank bank, PostingRepository postings) {
        ResumeTailor tailor = new ResumeTailor(RESUME);
        CoverageAnalyzer analyzer = new CoverageAnalyzer(
                new ExperiencePositioner(new ExperienceIndex(RESUME)), new ResumeSources(RESUME));
        ResumePipeline pipeline = new ResumePipeline(tailor, analyzer,
                new TailoringPlanner(RESUME, tailor, bank), new EvidenceProperties(null, true));
        return new EvidenceCommand(bank, RESUME, postings, pipeline,
                new ResumeRenderer(TestProfiles.indianApplicant()));
    }

    private static EvidenceCommand command() {
        return command(EvidenceFixtures.bank(), mock(PostingRepository.class));
    }

    @Test
    @DisplayName("the summary counts what the bank holds and says whether resumes use it")
    void summary() {
        String out = command().execute(Map.of());

        assertThat(out).contains("4 source(s), 9 item(s), 3 approved variant(s), 1 proposed")
                .contains("acme")
                .contains("Resumes are planned from the bank.");
    }

    @Test
    @DisplayName("--check lists each problem and what it means for tailoring")
    void check() {
        EvidenceBank broken = EvidenceFixtures.bank(EvidenceFixtures.BANK_YAML
                .replace("technologies: [Redis]", "technologies: [Redis, Terraform]"));

        String out = command(broken, mock(PostingRepository.class)).execute(Map.of("check", "true"));

        assertThat(out).contains("ERROR acme-cache").contains("'Terraform'")
                .contains("Resumes are tailored as before: the bank does not match the resume.");
        assertThat(command().execute(Map.of("check", "true")))
                .contains("The file has no problems.").contains("Against the resume: consistent.");
    }

    @Test
    @DisplayName("--for ranks the evidence, and says plainly when there is none")
    void strongest() {
        String kafka = command().execute(Map.of("for", "event-driven backend systems"));
        String cobol = command().execute(Map.of("for", "COBOL"));
        String streams = command().execute(Map.of("for", "Kafka Streams"));

        assertThat(kafka).contains("1. acme-replay");
        assertThat(cobol).contains("Nothing in the bank supports this. It will not be claimed.");
        assertThat(streams).contains("not a claim of \"Kafka Streams\"");
    }

    @Test
    @DisplayName("--plan --html generates the resume from the terminal: the plan, and the rendered page")
    void generatesAResume(@TempDir Path dir) throws IOException {
        Posting posting = new Posting(Source.GREENHOUSE, "acme", "7", "Backend Engineer");
        posting.setDescriptionText("Requirements:\nExperience building event-driven backend systems with Kafka.");
        PostingRepository postings = mock(PostingRepository.class);
        when(postings.findById(7L)).thenReturn(Optional.of(posting));
        Path html = dir.resolve("resume.html");

        String out = command(EvidenceFixtures.bank(), postings)
                .execute(Map.of("plan", "true", "posting-id", "7", "html", html.toString()));

        assertThat(out).contains("Resume plan: selected from the evidence bank")
                .contains("+ acme-replay").contains("events-first")
                .contains("Wrote " + html.toAbsolutePath());
        assertThat(Files.readString(html)).contains(EvidenceFixtures.EVENTS_FIRST)
                .doesNotContain(EvidenceFixtures.UNAPPROVED);
    }

    @Test
    @DisplayName("usage and a missing posting are answered, not thrown")
    void usage() {
        PostingRepository postings = mock(PostingRepository.class);
        when(postings.findById(99L)).thenReturn(Optional.empty());

        assertThat(command(EvidenceFixtures.bank(), postings).execute(Map.of("plan", "true")))
                .startsWith("Usage:");
        assertThat(command(EvidenceFixtures.bank(), postings)
                .execute(Map.of("plan", "true", "posting-id", "99"))).isEqualTo("No posting 99\n");
        assertThat(command(EvidenceBank.empty("none", List.of()), postings).execute(Map.of()))
                .contains("there is no usable evidence");
    }
}
