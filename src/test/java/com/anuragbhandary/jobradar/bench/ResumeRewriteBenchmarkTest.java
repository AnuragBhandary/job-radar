package com.anuragbhandary.jobradar.bench;

import static org.assertj.core.api.Assertions.assertThat;

import com.anuragbhandary.jobradar.apply.TestResumes;
import com.anuragbhandary.jobradar.apply.resume.ResumeModel;
import com.anuragbhandary.jobradar.apply.resume.ResumeTailor;
import com.anuragbhandary.jobradar.apply.resume.analysis.CoverageAnalyzer;
import com.anuragbhandary.jobradar.apply.resume.analysis.RequirementImportance;
import com.anuragbhandary.jobradar.apply.resume.analysis.ResumeSources;
import com.anuragbhandary.jobradar.apply.resume.rewrite.EvidenceScope;
import com.anuragbhandary.jobradar.apply.resume.rewrite.ResumeClaimValidator;
import com.anuragbhandary.jobradar.apply.resume.rewrite.RewriteQuality;
import com.anuragbhandary.jobradar.apply.resume.rewrite.RewriteRequest;
import com.anuragbhandary.jobradar.bench.ResumeRewriteBenchmark.ItemResult;
import com.anuragbhandary.jobradar.bench.ResumeRewriteBenchmark.Outcome;
import com.anuragbhandary.jobradar.domain.Posting;
import com.anuragbhandary.jobradar.domain.Source;
import com.anuragbhandary.jobradar.knowledge.experience.ExperienceIndex;
import com.anuragbhandary.jobradar.knowledge.experience.ExperiencePositioner;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** What becomes of each kind of model reply. Whatever fails, the source wording stands. */
class ResumeRewriteBenchmarkTest {

    private static final String SOURCE = "Built a Kafka-based event replay service handling "
            + "12 streams with deduplication.";
    private static final ResumeModel.Bullet BULLET =
            new ResumeModel.Bullet(SOURCE, List.of("kafka", "event-driven"), "replay");
    private static final ResumeSources SOURCES = new ResumeSources(new ResumeModel("h",
            List.of(new ResumeModel.Summary("default", List.of(), "Engineer.")), List.of(),
            List.of(new ResumeModel.Job("Co", "Engineer", "Remote", "2025", null, List.of(BULLET))),
            List.of(), List.of(), List.of(), 2, 4, 3));

    private static RewriteRequest request() {
        EvidenceScope scope = EvidenceScope.forBullet(SOURCES, "replay").orElseThrow();
        return new RewriteRequest("replay", SOURCE, scope,
                List.of(new RewriteRequest.Target("event-driven", RequirementImportance.REQUIRED,
                        "Build event-driven backend services")),
                List.of(), List.of("Kubernetes"), 200);
    }

    private static ItemResult evaluate(ModelCall call) {
        return ResumeRewriteBenchmark.evaluate(request(), call, Set.of("replay"), "JOB_BULLET", "Co");
    }

    private static ModelCall reply(String json) {
        return new ModelCall(true, json, null, 1_000, 200, 40, 500_000_000L, 0L, "stop");
    }

    @Test
    @DisplayName("an accepted rewrite replaces the text and is labelled a candidate improvement")
    void accepted() {
        ItemResult result = evaluate(reply("""
                {"sourceId":"replay","rewrittenText":"Built an event-driven Kafka replay system for 12 streams with deduplication."}"""));

        assertThat(result.outcome()).isEqualTo(Outcome.ACCEPTED);
        assertThat(result.quality()).isEqualTo(RewriteQuality.Label.CANDIDATE_IMPROVEMENT);
        assertThat(result.finalText()).startsWith("Built an event-driven");
        assertThat(result.sourceMetricsKept()).isEqualTo(1);
    }

    @Test
    @DisplayName("a rejected rewrite keeps the source text and says why")
    void rejected() {
        ItemResult result = evaluate(reply("""
                {"sourceId":"replay","rewrittenText":"Deployed a Kubernetes replay system for 40 games."}"""));

        assertThat(result.outcome()).isEqualTo(Outcome.REJECTED);
        assertThat(result.finalText()).isEqualTo(SOURCE);
        assertThat(result.issues()).extracting(ResumeClaimValidator.Issue::type)
                .contains(ResumeClaimValidator.IssueType.UNSUPPORTED_TECHNOLOGY,
                        ResumeClaimValidator.IssueType.INVENTED_NUMBER);
    }

    @Test
    @DisplayName("the source returned as-is is unchanged, not an improvement")
    void unchanged() {
        ItemResult result = evaluate(reply("{\"sourceId\":\"replay\",\"rewrittenText\":\"" + SOURCE + "\"}"));

        assertThat(result.outcome()).isEqualTo(Outcome.UNCHANGED);
        assertThat(result.quality()).isEqualTo(RewriteQuality.Label.UNCHANGED);
    }

    @Test
    @DisplayName("an empty or invalid reply falls back to the source")
    void invalidReply() {
        ItemResult empty = evaluate(reply(""));
        ItemResult prose = evaluate(reply("Here is a stronger bullet!"));

        assertThat(empty.outcome()).isEqualTo(Outcome.REJECTED);
        assertThat(empty.finalText()).isEqualTo(SOURCE);
        assertThat(prose.issues()).extracting(ResumeClaimValidator.Issue::type)
                .containsExactly(ResumeClaimValidator.IssueType.INVALID_RESPONSE);
    }

    @Test
    @DisplayName("a reply for another source id is an identity failure")
    void wrongSource() {
        ItemResult result = evaluate(reply("{\"sourceId\":\"other\",\"rewrittenText\":\"Built it.\"}"));

        assertThat(result.issues()).extracting(ResumeClaimValidator.Issue::type)
                .containsExactly(ResumeClaimValidator.IssueType.UNKNOWN_SOURCE);
        assertThat(result.finalText()).isEqualTo(SOURCE);
    }

    @Test
    @DisplayName("a model failure is recorded as one and the source stands")
    void modelFailure() {
        ItemResult result = evaluate(ModelCall.failed("timed out after 300s", 300_000));

        assertThat(result.outcome()).isEqualTo(Outcome.MODEL_FAILED);
        assertThat(result.finalText()).isEqualTo(SOURCE);
        assertThat(result.generated()).isNull();
    }

    // ---- The whole run, against a scripted server ---------------------------

    /** Records every request; answers extraction with one requirement and rewrites unchanged. */
    private static final class Recording implements LocalModel {
        final List<String> systems = new ArrayList<>();
        final List<String> users = new ArrayList<>();
        final List<Map<String, Object>> formats = new ArrayList<>();
        final List<String> unloaded = new ArrayList<>();

        @Override
        @SuppressWarnings("unchecked")
        public ModelCall chat(String model, String system, String user, Map<String, Object> format) {
            systems.add(system);
            users.add(user);
            formats.add(format);
            if (system.contains("extract the requirements")) {
                return reply("""
                        {"requirements":[{"term":"Kafka","category":"TECHNOLOGY","importance":"REQUIRED","quote":"Kafka"}]}""");
            }
            Map<String, Object> properties = (Map<String, Object>) format.get("properties");
            List<String> ids = (List<String>) ((Map<String, Object>) properties.get("sourceId")).get("enum");
            String source = user.substring(user.indexOf(")\n", user.indexOf("SOURCE (sourceId")) + 2,
                    user.indexOf("\n\nLimit"));
            return reply("{\"sourceId\":\"" + ids.getFirst() + "\",\"rewrittenText\":\""
                    + source.replace("\"", "\\\"") + "\"}");
        }

        @Override
        public void unload(String model) {
            unloaded.add(model);
        }
    }

    @Test
    @DisplayName("a full run rewrites bullets only, pins each id, never touches the summary, and unloads")
    void summaryIsNeverSentToTheModel() {
        ResumeModel resume = TestResumes.backendResume();
        ResumeSources sources = new ResumeSources(resume);
        Recording server = new Recording();
        ResumeRewriteBenchmark benchmark = new ResumeRewriteBenchmark(server, server, "m",
                new ResumeTailor(resume), new CoverageAnalyzer(
                        new ExperiencePositioner(new ExperienceIndex(resume)), sources),
                sources, s -> { });
        Posting posting = new Posting(Source.GREENHOUSE, "acme", "1", "Backend Engineer");
        posting.setDescriptionText("Requirements: Kafka and Java.");

        ResumeRewriteBenchmark.PostingRun run = benchmark.run(
                List.of(new ResumeRewriteBenchmark.Case(posting, "test"))).getFirst();

        int bullets = ResumeRewriteBenchmark.selectedBullets(run.deterministic()).size();
        assertThat(server.systems).hasSize(1 + bullets);
        assertThat(server.systems).noneMatch(s -> s.toLowerCase(Locale.ROOT).contains("summary"));
        assertThat(server.users).noneMatch(u -> u.contains(run.deterministic().summary().text())
                && !u.startsWith("TITLE:"));
        for (Map<String, Object> format : server.formats.subList(1, server.formats.size())) {
            @SuppressWarnings("unchecked")
            List<String> ids = (List<String>) ((Map<String, Object>) ((Map<String, Object>)
                    format.get("properties")).get("sourceId")).get("enum");
            assertThat(ids).hasSize(1);
            assertThat(sources.contains(ids.getFirst())).isTrue();
        }
        assertThat(run.generative().summary()).isEqualTo(run.deterministic().summary());
        assertThat(run.summaryPolicy()).contains("disabled");
        assertThat(run.bullets()).allMatch(b -> b.outcome() == Outcome.UNCHANGED);
        assertThat(server.unloaded).containsExactly("m");
    }
}
