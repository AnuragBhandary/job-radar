package com.anuragbhandary.jobradar.apply.resume.rewrite;

import static com.anuragbhandary.jobradar.apply.resume.rewrite.RewriteFixtures.RESUME;
import static com.anuragbhandary.jobradar.apply.resume.rewrite.RewriteFixtures.SOURCES;
import static org.assertj.core.api.Assertions.assertThat;

import com.anuragbhandary.jobradar.apply.resume.ResumeModel;
import com.anuragbhandary.jobradar.apply.resume.ResumeTailor;
import com.anuragbhandary.jobradar.apply.resume.TailoredResume;
import com.anuragbhandary.jobradar.apply.resume.analysis.CoverageAnalyzer;
import com.anuragbhandary.jobradar.apply.resume.analysis.CoverageLedger;
import com.anuragbhandary.jobradar.apply.resume.analysis.Requirement;
import com.anuragbhandary.jobradar.apply.resume.analysis.RequirementCategory;
import com.anuragbhandary.jobradar.apply.resume.analysis.RequirementImportance;
import com.anuragbhandary.jobradar.domain.Posting;
import com.anuragbhandary.jobradar.domain.Source;
import com.anuragbhandary.jobradar.knowledge.experience.ExperienceIndex;
import com.anuragbhandary.jobradar.knowledge.experience.ExperiencePositioner;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Parsing, planning, skill order and assembly - everything around the model call. */
class RewritePipelineTest {

    private static final CoverageAnalyzer ANALYZER = new CoverageAnalyzer(
            new ExperiencePositioner(new ExperienceIndex(RESUME)), SOURCES);

    private static CoverageLedger ledger() {
        return ANALYZER.analyse(1L, "Backend Engineer", "test", List.of(
                Requirement.of("Kafka", RequirementCategory.TECHNOLOGY, RequirementImportance.REQUIRED, "Kafka"),
                Requirement.of("Kubernetes", RequirementCategory.CLOUD, RequirementImportance.REQUIRED, "Kubernetes"),
                Requirement.of("Docker", RequirementCategory.CLOUD, RequirementImportance.REQUIRED, "Docker"),
                Requirement.of("Java", RequirementCategory.LANGUAGE, RequirementImportance.PREFERRED, "Java"),
                Requirement.of("Redis", RequirementCategory.DATABASE, RequirementImportance.PREFERRED, "Redis")));
    }

    // ---- Parsing ----------------------------------------------------------

    @Test
    @DisplayName("a reply for the item asked about parses")
    void parsesValidReply() {
        RewriteParser.Parsed parsed = RewriteParser.parse(
                "```json\n{\"sourceId\":\"pg-aws\",\"rewrittenText\":\"  Stored metadata in PostgreSQL.  \"}\n```",
                "pg-aws");

        assertThat(parsed.ok()).isTrue();
        assertThat(parsed.text()).isEqualTo("Stored metadata in PostgreSQL.");
    }

    @Test
    @DisplayName("a reply for a different item is rejected, not re-attached")
    void rejectsWrongSource() {
        RewriteParser.Parsed parsed = RewriteParser.parse(
                "{\"sourceId\":\"docker-services\",\"rewrittenText\":\"Built things.\"}", "pg-aws");

        assertThat(parsed.ok()).isFalse();
        assertThat(parsed.wrongSource()).isTrue();
    }

    @Test
    @DisplayName("empty, non-JSON and textless replies are rejected")
    void rejectsUnusableReplies() {
        assertThat(RewriteParser.parse("", "pg-aws").ok()).isFalse();
        assertThat(RewriteParser.parse("Sure! Here is the bullet.", "pg-aws").ok()).isFalse();
        assertThat(RewriteParser.parse("{\"sourceId\":\"pg-aws\",\"rewrittenText\":\"\"}", "pg-aws").ok())
                .isFalse();
        assertThat(RewriteParser.parse("{\"rewrittenText\":\"x\"}", "pg-aws").wrongSource()).isTrue();
    }

    // ---- Planning ---------------------------------------------------------

    @Test
    @DisplayName("a bullet is pointed only at requirements its own evidence carries")
    void plansFromOwnEvidence() {
        RewriteRequest kafka = RewritePlanner.forBullet(
                EvidenceScope.forBullet(SOURCES, "kafka-events").orElseThrow(), ledger());
        RewriteRequest docker = RewritePlanner.forBullet(
                EvidenceScope.forBullet(SOURCES, "docker-services").orElseThrow(), ledger());

        assertThat(kafka.targets()).extracting(RewriteRequest.Target::term).containsExactly("Kafka");
        assertThat(kafka.prohibited()).contains("Kubernetes");
        assertThat(docker.targets()).extracting(RewriteRequest.Target::term).containsExactly("Docker");
        assertThat(docker.adjacent()).extracting(RewriteRequest.Adjacent::term).contains("Kubernetes");
    }

    @Test
    @DisplayName("the summary may speak to every DIRECT requirement, and still not Kubernetes")
    void plansSummary() {
        RewriteRequest summary = RewritePlanner.forSummary(EvidenceScope.forSummary(SOURCES,
                "default", RESUME.summaries().getFirst().text()), ledger());

        assertThat(summary.targets()).extracting(RewriteRequest.Target::term)
                .contains("Kafka", "Docker", "Java", "Redis");
        assertThat(summary.prohibited()).contains("Kubernetes");
    }

    // ---- Skills -----------------------------------------------------------

    @Test
    @DisplayName("skills are reordered by what the posting requires, and nothing is added")
    void reordersSkillsOnly() {
        List<ResumeModel.SkillGroup> ordered = SkillOrdering.reorder(RESUME.skills(), ledger());

        assertThat(ordered).extracting(ResumeModel.SkillGroup::group)
                .containsExactly("Cloud & DevOps", "Languages");
        assertThat(ordered.get(0).items()).containsExactly("Docker", "AWS (EC2", "S3)");
        assertThat(ordered.get(1).items()).containsExactly("Java", "Python");

        List<String> before = new ArrayList<>();
        RESUME.skills().forEach(g -> before.addAll(g.items()));
        List<String> after = new ArrayList<>();
        ordered.forEach(g -> after.addAll(g.items()));
        assertThat(after).containsExactlyInAnyOrderElementsOf(before);
        assertThat(after).noneMatch(item -> item.toLowerCase().contains("kubernetes"));
    }

    // ---- Assembly ---------------------------------------------------------

    @Test
    @DisplayName("assembly swaps only the accepted bullets and keeps every fact field")
    void assemblesByIdOnly() {
        Posting posting = new Posting(Source.GREENHOUSE, "acme", "1", "Backend Engineer");
        posting.setDescriptionText("Kafka, Docker and Redis.");
        TailoredResume base = new ResumeTailor(RESUME).tailor(posting);

        TailoredResume candidate = GenerativeTailor.assemble(base, SOURCES,
                Map.of("kafka-events", "Built event-driven game-event processing on Kafka.",
                        "not-a-real-id", "Invented."),
                "A new summary.", SkillOrdering.reorder(base.skills(), ledger()));

        ResumeModel.Job before = base.experience().getFirst();
        ResumeModel.Job after = candidate.experience().getFirst();
        assertThat(after.company()).isEqualTo(before.company());
        assertThat(after.title()).isEqualTo(before.title());
        assertThat(after.period()).isEqualTo(before.period());
        assertThat(after.bullets()).hasSameSizeAs(before.bullets());
        assertThat(after.bullets()).extracting(ResumeModel.Bullet::text)
                .contains("Built event-driven game-event processing on Kafka.")
                .doesNotContain("Invented.");
        assertThat(candidate.projects()).extracting(ResumeModel.Project::name)
                .isEqualTo(base.projects().stream().map(ResumeModel.Project::name).toList());
        assertThat(candidate.education()).isEqualTo(base.education());
        assertThat(candidate.summary().text()).isEqualTo("A new summary.");
        assertThat(candidate.summary().id()).isEqualTo(base.summary().id());
    }
}
