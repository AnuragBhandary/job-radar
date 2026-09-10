package com.anuragbhandary.jobradar.knowledge.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.anuragbhandary.jobradar.apply.llm.LlmClient;
import com.anuragbhandary.jobradar.apply.resume.ResumeModel;
import com.anuragbhandary.jobradar.knowledge.Concept;
import com.anuragbhandary.jobradar.knowledge.Concepts;
import com.anuragbhandary.jobradar.knowledge.Confidence;
import com.anuragbhandary.jobradar.knowledge.Contexts;
import com.anuragbhandary.jobradar.knowledge.Evidence;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What the model is allowed to get away with.
 *
 * <p>The old cover-letter path validated against a list of forbidden phrases,
 * which catches how a model sounds and cannot catch what it invents. Every test
 * below is a draft that would have passed every one of those checks.
 */
class AnswerProposerTest {

    /** Java, Spring Boot, Kafka and Postgres. Deliberately no Kubernetes. */
    private static final ResumeModel RESUME = new ResumeModel(
            "Backend Engineer",
            List.of(new ResumeModel.Summary("default", List.of(), "A summary.")),
            List.of(new ResumeModel.SkillGroup("Backend",
                    List.of("Java", "Spring Boot", "Kafka", "PostgreSQL"))),
            List.of(),
            List.of(new ResumeModel.Project("Job Radar", "Java, Spring Boot, SQLite",
                    List.of("java", "spring boot"),
                    List.of(new ResumeModel.Bullet(
                            "Built a Kafka consumer that reconciles postings.",
                            List.of("kafka"))))),
            List.of(new ResumeModel.Education("MS Computer Science", "UT Arlington",
                    "Texas", "2023-2025", "")),
            List.of(), 2, 4, 3);

    private AnswerProposer proposerReturning(String json) {
        LlmClient llm = mock(LlmClient.class);
        when(llm.isUsable()).thenReturn(true);
        when(llm.complete(anyString(), anyString())).thenReturn(Optional.of(json));
        return new AnswerProposer(llm, RESUME, new ObjectMapper(),
                new com.anuragbhandary.jobradar.knowledge.experience.ExperienceIndex(RESUME));
    }

    private ProposedAnswer propose(String json) {
        return proposerReturning(json)
                .propose(Concepts.WHY_COMPANY, Contexts.germanyOnsite(), "Why us?")
                .orElseThrow();
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("a grounded, cited draft is proposed")
    void groundedDraftIsAccepted() {
        ProposedAnswer proposal = propose("""
                {"status":"PROPOSED","concept":"why_company",
                 "answer":"Your workflow engine is the kind of system I have been building. I wrote a Kafka consumer that reconciles job postings, and the same problems show up in process orchestration.",
                 "evidence":["P1"],"confidence":"MEDIUM"}
                """);

        assertThat(proposal.status()).isEqualTo(ProposedAnswer.Status.PROPOSED);
        assertThat(proposal.isUsable()).isTrue();
        assertThat(proposal.evidenceRefs()).containsExactly("P1");
    }

    @Test
    @DisplayName("a draft claiming a technology the resume never mentions is rejected")
    void fabricatedTechnologyIsRejected() {
        // The check a phrase blocklist cannot do. This paragraph is fluent,
        // dash-free, cites a real item, and describes work he has never done.
        ProposedAnswer proposal = propose("""
                {"status":"PROPOSED","concept":"why_company",
                 "answer":"I have run production Kubernetes clusters and would enjoy doing that here.",
                 "evidence":["P1"],"confidence":"HIGH"}
                """);

        assertThat(proposal.status()).isEqualTo(ProposedAnswer.Status.REJECTED);
        assertThat(proposal.rejectionReason()).contains("kubernetes");
        assertThat(proposal.isUsable()).isFalse();
    }

    @Test
    @DisplayName("a draft citing material that does not exist is rejected")
    void fabricatedCitationIsRejected() {
        ProposedAnswer proposal = propose("""
                {"status":"PROPOSED","concept":"why_company",
                 "answer":"My work on the payments platform is the closest thing to this role.",
                 "evidence":["R7","P9"],"confidence":"HIGH"}
                """);

        assertThat(proposal.status()).isEqualTo(ProposedAnswer.Status.REJECTED);
        assertThat(proposal.rejectionReason()).contains("does not exist");
    }

    @Test
    @DisplayName("a draft that cites nothing is rejected")
    void uncitedDraftIsRejected() {
        // If it cites nothing, nothing in it can be checked - which is the same
        // as it being unverifiable, and unverifiable is not good enough to send.
        ProposedAnswer proposal = propose("""
                {"status":"PROPOSED","concept":"why_company",
                 "answer":"I admire the company's engineering culture and its people.",
                 "evidence":[],"confidence":"HIGH"}
                """);

        assertThat(proposal.status()).isEqualTo(ProposedAnswer.Status.REJECTED);
        assertThat(proposal.rejectionReason()).contains("cited nothing");
    }

    @Test
    @DisplayName("NOTHING is a first-class answer, not a failure")
    void nothingIsRespected() {
        ProposedAnswer proposal = propose("""
                {"status":"NOTHING"}
                """);
        assertThat(proposal.status()).isEqualTo(ProposedAnswer.Status.NOTHING);
        assertThat(proposal.isUsable()).isFalse();
    }

    @Test
    @DisplayName("output that is not the requested JSON is rejected")
    void unstructuredOutputIsRejected() {
        // A model that ignored the output contract has ignored the rules with it.
        ProposedAnswer proposal = propose(
                "I would love to work here because I am passionate about your mission!");
        assertThat(proposal.status()).isEqualTo(ProposedAnswer.Status.REJECTED);
        assertThat(proposal.rejectionReason()).contains("JSON");
    }

    @Test
    @DisplayName("a draft that reads as generated is rejected")
    void generatedToneIsRejected() {
        ProposedAnswer proposal = propose("""
                {"status":"PROPOSED","concept":"why_company",
                 "answer":"I am deeply passionate about leveraging cutting-edge technology to drive impactful solutions in a fast-paced environment.",
                 "evidence":["P1"],"confidence":"HIGH"}
                """);
        assertThat(proposal.status()).isEqualTo(ProposedAnswer.Status.REJECTED);
        assertThat(proposal.rejectionReason()).contains("reads as generated");
    }

    @Test
    @DisplayName("the model's own confidence is never taken as HIGH")
    void modelConfidenceIsCapped() {
        // A model's opinion of itself is not evidence of anything, and HIGH is
        // the band that would make an answer auto-fillable.
        ProposedAnswer proposal = propose("""
                {"status":"PROPOSED","concept":"why_company",
                 "answer":"Your workflow engine is the kind of system I have built with Java and Spring Boot.",
                 "evidence":["P1"],"confidence":"HIGH"}
                """);
        assertThat(proposal.status()).isEqualTo(ProposedAnswer.Status.PROPOSED);
        assertThat(proposal.confidence()).isNotEqualTo(Confidence.HIGH);
    }

    @Test
    @DisplayName("a factual concept is never sent to a model at all")
    void factualConceptsAreNotProposed() {
        AnswerProposer proposer = proposerReturning("""
                {"status":"PROPOSED","answer":"Two weeks","evidence":["P1"]}
                """);
        for (Concept concept : List.of(Concepts.SPONSORSHIP_REQUIRED, Concepts.NOTICE_PERIOD,
                Concepts.SALARY_EXPECTATION, Concepts.WORK_AUTHORISATION)) {
            assertThat(proposer.propose(concept, Contexts.germanyOnsite(), "?"))
                    .as("%s must not be drafted by a model", concept.id())
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("with no model configured nothing is proposed")
    void withoutAModelNothingHappens() {
        LlmClient llm = mock(LlmClient.class);
        when(llm.isUsable()).thenReturn(false);
        AnswerProposer proposer = new AnswerProposer(llm, RESUME, new ObjectMapper(),
                new com.anuragbhandary.jobradar.knowledge.experience.ExperienceIndex(RESUME));
        assertThat(proposer.propose(Concepts.WHY_COMPANY, Contexts.germanyOnsite(), "Why us?"))
                .isEmpty();
    }

    @Test
    @DisplayName("confirmed citations become evidence pointing at real resume items")
    void citationsBecomeEvidence() {
        ProposedAnswer proposal = propose("""
                {"status":"PROPOSED","concept":"why_company",
                 "answer":"Your workflow engine is the kind of system I have built with Java and Spring Boot.",
                 "evidence":["P1"],"confidence":"MEDIUM"}
                """);
        List<Evidence> evidence = proposerReturning("{}").evidenceFor(proposal);

        assertThat(evidence).singleElement().satisfies(item -> {
            assertThat(item.kind()).isEqualTo(Evidence.Kind.RESUME_ITEM);
            assertThat(item.ref()).isEqualTo("P1");
            assertThat(item.excerpt()).contains("Job Radar");
        });
    }
}
