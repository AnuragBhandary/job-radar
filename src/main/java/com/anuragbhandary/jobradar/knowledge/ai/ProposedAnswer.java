package com.anuragbhandary.jobradar.knowledge.ai;

import com.anuragbhandary.jobradar.knowledge.Confidence;
import java.util.List;

/**
 * A model's structured attempt at one open-ended question.
 *
 * <p>Structured rather than prose because the checks that matter are not about
 * style. The old cover-letter path validated against a list of forbidden phrases
 * - "years of experience", "[Company", "as an ai" - which catches how a model
 * sounds and cannot catch what it invents. A model that writes a fluent, dash-free
 * paragraph about his Kubernetes work passes every one of those checks, and he
 * has never touched Kubernetes.
 *
 * <p>So the model is made to say which evidence it used, and the citation is
 * checked against the material it was given. A claim it cannot cite is not a
 * claim it gets to make.
 *
 * @param evidenceRefs ids from the material block, e.g. {@code R3}, {@code P1}.
 *                     Validated by {@link AnswerProposer}, never trusted.
 */
public record ProposedAnswer(
        Status status,
        String conceptId,
        String answer,
        List<String> evidenceRefs,
        Confidence confidence,
        String rejectionReason) {

    public enum Status {
        /** Usable as a draft, pending his approval. */
        PROPOSED,
        /**
         * The material does not support an answer.
         *
         * <p>A first-class outcome and the one the prompt asks for explicitly. A
         * blank on a form is recoverable; a false claim on an application is not.
         */
        NOTHING,
        /** The draft failed a check here. Never shown as an answer. */
        REJECTED
    }

    public static ProposedAnswer nothing(String conceptId) {
        return new ProposedAnswer(Status.NOTHING, conceptId, null, List.of(),
                Confidence.LOW, null);
    }

    public static ProposedAnswer rejected(String conceptId, String why) {
        return new ProposedAnswer(Status.REJECTED, conceptId, null, List.of(),
                Confidence.LOW, why);
    }

    public boolean isUsable() {
        return status == Status.PROPOSED && answer != null && !answer.isBlank();
    }

    public List<String> evidenceRefs() {
        return evidenceRefs == null ? List.of() : evidenceRefs;
    }
}
