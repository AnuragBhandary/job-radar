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
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Parsing, the pinned schema, planning, skill order and assembly. */
class RewritePipelineTest {

    private static final CoverageAnalyzer ANALYZER = new CoverageAnalyzer(
            new ExperiencePositioner(new ExperienceIndex(RESUME)), SOURCES);

    private static Requirement req(String term, RequirementImportance importance) {
        return Requirement.of(term, RequirementCategory.TECHNOLOGY, importance, term);
    }

    private static CoverageLedger ledger(Requirement... requirements) {
        return ANALYZER.analyse(1L, "Backend Engineer", "test", List.of(requirements));
    }

    private static CoverageLedger standardLedger() {
        return ledger(req("Kafka", RequirementImportance.REQUIRED),
                req("Kubernetes", RequirementImportance.REQUIRED),
                req("Docker", RequirementImportance.REQUIRED),
                req("Java", RequirementImportance.PREFERRED),
                req("Redis", RequirementImportance.PREFERRED));
    }

    // ---- Parsing and the pinned schema -----------------------------------

    @Test
    @DisplayName("the schema allows exactly one sourceId, the one asked about")
    void schemaPinsTheSourceId() {
        Map<String, Object> schema = RewritePrompt.schema("pg-aws");

        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> sourceId = (Map<String, Object>) properties.get("sourceId");
        assertThat(sourceId.get("enum")).isEqualTo(List.of("pg-aws"));
        assertThat(schema.get("required")).isEqualTo(List.of("sourceId", "rewrittenText"));
    }

    @Test
    @DisplayName("the allowed id parses")
    void parsesValidReply() {
        RewriteParser.Parsed parsed = RewriteParser.parse(
                "```json\n{\"sourceId\":\"pg-aws\",\"rewrittenText\":\"  Stored metadata in PostgreSQL.  \"}\n```",
                "pg-aws");

        assertThat(parsed.ok()).isTrue();
        assertThat(parsed.text()).isEqualTo("Stored metadata in PostgreSQL.");
    }

    @Test
    @DisplayName("any other id is rejected even if the schema was somehow bypassed")
    void rejectsWrongSource() {
        RewriteParser.Parsed parsed = RewriteParser.parse(
                "{\"sourceId\":\"pg-aw5\",\"rewrittenText\":\"Built things.\"}", "pg-aws");

        assertThat(parsed.ok()).isFalse();
        assertThat(parsed.wrongSource()).isTrue();
    }

    @Test
    @DisplayName("a missing id, malformed JSON and empty text are all rejected")
    void rejectsUnusableReplies() {
        assertThat(RewriteParser.parse("{\"rewrittenText\":\"x\"}", "pg-aws").wrongSource()).isTrue();
        assertThat(RewriteParser.parse("{\"sourceId\": \"pg-aws\", \"rewrittenText\": ", "pg-aws").ok())
                .isFalse();
        assertThat(RewriteParser.parse("Sure! Here is the bullet.", "pg-aws").ok()).isFalse();
        assertThat(RewriteParser.parse("", "pg-aws").ok()).isFalse();
        assertThat(RewriteParser.parse("{\"sourceId\":\"pg-aws\",\"rewrittenText\":\"\"}", "pg-aws").ok())
                .isFalse();
    }

    // ---- Planning ---------------------------------------------------------

    @Test
    @DisplayName("a bullet is pointed only at requirements its own words carry")
    void plansFromOwnEvidence() {
        RewriteRequest kafka = RewritePlanner.forBullet(
                EvidenceScope.forBullet(SOURCES, "kafka-events").orElseThrow(), standardLedger());
        RewriteRequest docker = RewritePlanner.forBullet(
                EvidenceScope.forBullet(SOURCES, "docker-services").orElseThrow(), standardLedger());

        assertThat(kafka.targets()).extracting(RewriteRequest.Target::term).containsExactly("Kafka");
        assertThat(kafka.prohibited()).contains("Kubernetes");
        assertThat(docker.targets()).extracting(RewriteRequest.Target::term).containsExactly("Docker");
        assertThat(docker.adjacent()).extracting(RewriteRequest.Adjacent::term).contains("Kubernetes");
    }

    @Test
    @DisplayName("AI summary rewriting is disabled: there is no way to ask for one")
    void summaryRewritingIsDisabled() {
        for (Class<?> type : List.of(RewritePlanner.class, RewritePrompt.class,
                EvidenceScope.class, GenerativeTailor.class)) {
            assertThat(Arrays.stream(type.getDeclaredMethods()).map(Method::getName)
                    .map(name -> name.toLowerCase(Locale.ROOT)))
                    .as(type.getSimpleName()).noneMatch(name -> name.contains("summary"));
        }
        assertThat(RewritePrompt.system().toLowerCase(Locale.ROOT)).doesNotContain("summary");
        assertThat(Arrays.stream(RewriteRequest.class.getRecordComponents())
                .map(c -> c.getName().toLowerCase(Locale.ROOT))).noneMatch(n -> n.contains("summary"));
    }

    // ---- Skills -----------------------------------------------------------

    @Test
    @DisplayName("skills are reordered by what the posting requires, and nothing is added")
    void reordersSkillsOnly() {
        List<ResumeModel.SkillGroup> ordered = SkillOrdering.reorder(RESUME.skills(), standardLedger());

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

    @Test
    @DisplayName("Kubernetes lifts Docker, which is his, and never appears itself")
    void adjacentLiftsItsEvidenceOnly() {
        List<ResumeModel.SkillGroup> ordered = SkillOrdering.reorder(RESUME.skills(),
                ledger(req("Kubernetes", RequirementImportance.REQUIRED),
                        req("Java", RequirementImportance.PREFERRED)));

        assertThat(ordered.getFirst().group()).isEqualTo("Cloud & DevOps");
        assertThat(ordered.getFirst().items().getFirst()).isEqualTo("Docker");
        assertThat(ordered).flatExtracting(ResumeModel.SkillGroup::items)
                .noneMatch(item -> item.toLowerCase().contains("kubernetes"));
    }

    @Test
    @DisplayName("Terraform lifts AWS, keeps its bracket together, and never appears itself")
    void terraformLiftsAws() {
        List<ResumeModel.SkillGroup> ordered = SkillOrdering.reorder(RESUME.skills(),
                ledger(req("Terraform", RequirementImportance.REQUIRED)));

        assertThat(ordered.getFirst().items()).startsWith("AWS (EC2", "S3)");
        assertThat(ordered).flatExtracting(ResumeModel.SkillGroup::items)
                .noneMatch(item -> item.toLowerCase().contains("terraform"));
    }

    // ---- Assembly ---------------------------------------------------------

    @Test
    @DisplayName("assembly swaps only accepted bullets, keeps every fact field and the summary")
    void assemblesByIdOnly() {
        Posting posting = new Posting(Source.GREENHOUSE, "acme", "1", "Backend Engineer");
        posting.setDescriptionText("Kafka, Docker and Redis.");
        TailoredResume base = new ResumeTailor(RESUME).tailor(posting);

        TailoredResume candidate = GenerativeTailor.assemble(base, SOURCES,
                Map.of("kafka-events", "Built event-driven game-event processing on Kafka.",
                        "not-a-real-id", "Invented."),
                SkillOrdering.reorder(base.skills(), standardLedger()));

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
        assertThat(candidate.summary()).isEqualTo(base.summary());
    }
}
