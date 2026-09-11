package com.anuragbhandary.jobradar.apply.resume.plan;

import static org.assertj.core.api.Assertions.assertThat;

import com.anuragbhandary.jobradar.apply.ApplyService;
import com.anuragbhandary.jobradar.apply.llm.LlmClient;
import com.anuragbhandary.jobradar.apply.resume.ResumeTailor;
import com.anuragbhandary.jobradar.apply.resume.analysis.CoverageAnalyzer;
import com.anuragbhandary.jobradar.apply.resume.rewrite.GenerativeTailor;
import com.anuragbhandary.jobradar.apply.resume.rewrite.RewritePlanner;
import com.anuragbhandary.jobradar.apply.resume.rewrite.RewritePrompt;
import com.anuragbhandary.jobradar.bench.LocalModel;
import com.anuragbhandary.jobradar.bench.OllamaClient;
import com.anuragbhandary.jobradar.bench.ResumeRewriteBenchmark;
import com.anuragbhandary.jobradar.chat.ChatService;
import com.anuragbhandary.jobradar.evidence.EvidenceBank;
import com.anuragbhandary.jobradar.evidence.EvidenceBankLoader;
import com.anuragbhandary.jobradar.evidence.EvidenceFixtures;
import com.anuragbhandary.jobradar.evidence.EvidenceMatcher;
import com.anuragbhandary.jobradar.evidence.EvidenceValidator;
import com.anuragbhandary.jobradar.knowledge.ai.AnswerProposer;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The resume path cannot reach a model, and the rewrite benchmark's protections now
 * guard the bank.
 */
class PipelineSafetyTest {

    /** Anything that talks to a model, or is the model-rewrite experiment. */
    private static final List<Class<?>> MODEL_TYPES = List.of(LocalModel.class, OllamaClient.class,
            LlmClient.class, ChatService.class, AnswerProposer.class, GenerativeTailor.class,
            RewritePlanner.class, RewritePrompt.class, ResumeRewriteBenchmark.class);

    private static List<Class<?>> dependencies(Class<?> type) {
        List<Class<?>> out = new ArrayList<>();
        for (Field field : type.getDeclaredFields()) {
            out.add(field.getType());
        }
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            out.addAll(Arrays.asList(constructor.getParameterTypes()));
        }
        return out;
    }

    @Test
    @DisplayName("no class on the resume path holds or receives anything that talks to a model")
    void noModelOnTheResumePath() {
        for (Class<?> type : List.of(ResumePipeline.class, TailoringPlanner.class,
                ResumeVerifier.class, ResumeTailor.class, CoverageAnalyzer.class, EvidenceBank.class,
                EvidenceBankLoader.class, EvidenceMatcher.class, EvidenceValidator.class)) {
            for (Class<?> dependency : dependencies(type)) {
                assertThat(MODEL_TYPES).as(type.getSimpleName() + " depends on " + dependency.getSimpleName())
                        .noneMatch(model -> model.isAssignableFrom(dependency));
            }
        }
    }

    @Test
    @DisplayName("applications get their resume from the pipeline, never from the rewrite experiment")
    void applyUsesThePipeline() {
        List<Class<?>> dependencies = dependencies(ApplyService.class);

        assertThat(dependencies).contains(ResumePipeline.class);
        assertThat(dependencies).doesNotContain(ResumeTailor.class, GenerativeTailor.class,
                ResumeRewriteBenchmark.class, LocalModel.class, OllamaClient.class);
    }

    // ---- Phase 2 protections, now applied to approved variants --------------------

    private static final String HEAD = """
            version: 1
            sources:
              - id: acme
                kind: employment
                name: Acme Streaming
                stack: [Python, Kafka, Redis]
            items:
            """;

    private static List<String> variantErrors(String claim, String metrics, String variant) {
        EvidenceBank bank = EvidenceFixtures.bank(HEAD + """
                  - id: item
                    source: acme
                    claim: "%s"
                    technologies: [Kafka, Redis, Python]
                    metrics: %s
                    variants:
                      - id: v
                        text: "%s"
                        approved: true
                """.formatted(claim, metrics, variant));
        return EvidenceFixtures.errors(bank);
    }

    @Test
    @DisplayName("'roughly fourfold' cannot become 'fourfold'")
    void hedgeOnAFigure() {
        assertThat(variantErrors(
                "Reduced report generation time roughly fourfold with Redis.", "[roughly fourfold]",
                "Reduced report generation time fourfold with Redis."))
                .anySatisfy(e -> assertThat(e).contains("roughly fourfold"));
    }

    @Test
    @DisplayName("'self-built' cannot be dropped, and 'using Python' cannot be tacked on")
    void qualifierAndStuffing() {
        assertThat(variantErrors("Built a self-built Kafka replay tool.", "[]",
                "Built a Kafka replay tool.")).anySatisfy(e -> assertThat(e).contains("QUALIFIER_LOST"));
        assertThat(variantErrors("Built a Kafka replay tool.", "[]",
                "Built a Kafka replay tool using Python.")).anySatisfy(e -> assertThat(e).contains("KEYWORD_STUFFING"));
    }

    @Test
    @DisplayName("a compound requirement never hides the component he has - the Python bug stays fixed")
    void compoundRequirementsStayFixed() {
        EvidenceBank bank = EvidenceFixtures.bank();

        assertThat(bank.strongestFor("Python / JavaScript / TypeScript")).isNotEmpty();
        assertThat(bank.strongestFor("REST / GraphQL")).extracting(m -> m.itemId()).contains("sched-api");
    }
}
