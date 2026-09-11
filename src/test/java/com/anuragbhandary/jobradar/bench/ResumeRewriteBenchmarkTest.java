package com.anuragbhandary.jobradar.bench;

import static org.assertj.core.api.Assertions.assertThat;

import com.anuragbhandary.jobradar.apply.resume.ResumeModel;
import com.anuragbhandary.jobradar.apply.resume.analysis.RequirementImportance;
import com.anuragbhandary.jobradar.apply.resume.analysis.ResumeSources;
import com.anuragbhandary.jobradar.apply.resume.rewrite.EvidenceScope;
import com.anuragbhandary.jobradar.apply.resume.rewrite.ResumeClaimValidator;
import com.anuragbhandary.jobradar.apply.resume.rewrite.RewriteRequest;
import com.anuragbhandary.jobradar.bench.ResumeRewriteBenchmark.ItemResult;
import com.anuragbhandary.jobradar.bench.ResumeRewriteBenchmark.Outcome;
import java.util.List;
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
                List.of(), List.of("Kubernetes"), 200, false);
    }

    private static ItemResult evaluate(ModelCall call) {
        return ResumeRewriteBenchmark.evaluate(request(), call, Set.of("replay"), "JOB_BULLET", "Co");
    }

    private static ModelCall reply(String json) {
        return new ModelCall(true, json, null, 1_000, 200, 40, 500_000_000L, 0L, "stop");
    }

    @Test
    @DisplayName("an accepted rewrite replaces the text and records that it is more specific")
    void accepted() {
        ItemResult result = evaluate(reply("""
                {"sourceId":"replay","rewrittenText":"Built an event-driven Kafka replay system for 12 streams with deduplication."}"""));

        assertThat(result.outcome()).isEqualTo(Outcome.ACCEPTED);
        assertThat(result.finalText()).startsWith("Built an event-driven");
        assertThat(result.targetsNamedAfter()).isGreaterThan(result.targetsNamedBefore());
        assertThat(result.moreJobSpecific()).isTrue();
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
        assertThat(result.moreJobSpecific()).isFalse();
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
}
