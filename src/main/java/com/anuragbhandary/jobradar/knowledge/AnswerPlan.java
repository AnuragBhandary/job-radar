package com.anuragbhandary.jobradar.knowledge;

import com.anuragbhandary.jobradar.knowledge.experience.ExperiencePositioner;
import com.anuragbhandary.jobradar.knowledge.experience.ExperienceQuestion;
import com.anuragbhandary.jobradar.knowledge.experience.Positioning;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Decides how a question will be answered before anyone is asked anything.
 *
 * <p>The single place implementing this phase's rule: <em>do not interrupt him
 * for something the system can work out</em>. Everything deterministic is tried
 * first, then experience positioning, and only what survives both reaches him.
 *
 * <h2>The order, and why it is that order</h2>
 * <ol>
 *   <li><b>Resolve.</b> Session answers, stored assertions, profile facts,
 *       derivations - the existing {@link KnowledgeResolver}, unchanged. If it
 *       has an answer, there is nothing to decide.</li>
 *   <li><b>Category.</b> A sensitive or factual question with no answer goes
 *       straight to him. No positioning, no drafting: a missing degree is a
 *       missing degree, and a legal declaration is not something to reason
 *       towards.</li>
 *   <li><b>Position.</b> Only for a technology-experience question. This is the
 *       step that turns "Kubernetes is not in your resume, please answer" into a
 *       draft built from Docker, AWS and distributed systems.</li>
 *   <li><b>Ask.</b> What is left.</li>
 * </ol>
 *
 * <p>Nothing here calls a model. It decides <em>whether</em> a model should be
 * asked, which is a decision that has to be reproducible - and it runs on every
 * field of every form, where a model call per field would be both slow and
 * expensive.
 */
@Service
public class AnswerPlan {

    private final KnowledgeResolver resolver;
    private final ExperiencePositioner positioner;

    public AnswerPlan(KnowledgeResolver resolver, ExperiencePositioner positioner) {
        this.resolver = resolver;
        this.positioner = positioner;
    }

    /**
     * @param positioning present only when the question was recognised as being
     *                    about a technology and the route is {@link
     *                    AnswerRoute#AI_PROPOSE}. Carries what may be claimed.
     * @param because     one sentence naming why this route was chosen, for the
     *                    audit and for the explanation panel
     */
    public record Plan(AnswerRoute route, Resolution resolution, Positioning positioning,
            String because) {

        public boolean isPositioned() {
            return positioning != null;
        }
    }

    /**
     * How this question will be answered.
     *
     * @param concept  what the classifier made of it, possibly
     *                 {@link Concept#UNRECOGNISED} - which is the ordinary case
     *                 for a technology question and no longer a dead end
     * @param label    the question as the board wrote it, which is where the
     *                 technology's name actually lives
     * @param required whether the form insists on an answer
     */
    public Plan planFor(Concept concept, String label, ApplicationContext context,
            boolean required) {

        Concept subject = concept == null ? Concept.UNRECOGNISED : concept;
        Resolution resolution = resolver.resolve(subject, context);

        if (resolution.isAutoFillable()) {
            return new Plan(AnswerRoute.AUTO_RESOLVE, resolution, null,
                    "already known: " + resolution.explanation());
        }
        if (resolution.state() == Resolution.State.DECLINED) {
            return new Plan(AnswerRoute.NOT_APPLICABLE, resolution, null,
                    "recognised and deliberately not answered");
        }
        if (resolution.state() == Resolution.State.CONFLICT) {
            // Two things he has said disagree. A model must not pick between
            // them and neither may this - the disagreement is about him.
            return new Plan(AnswerRoute.USER_REQUIRED, resolution, null,
                    "two saved answers apply here and disagree");
        }
        if (resolution.hasValue()) {
            // Known, and not confident enough to send unread - a stale volatile
            // fact, an unapproved draft, a derivation on an unstated context.
            return new Plan(AnswerRoute.AI_PROPOSE, resolution, null,
                    "an answer applies but is not confident enough to send unread");
        }

        // Nothing answered it. Before asking him, find out whether this is a
        // question about a technology - which is answerable from evidence he
        // already has, and was the single biggest source of interruptions.
        if (subject.isSensitive()) {
            return new Plan(AnswerRoute.USER_REQUIRED, resolution, null,
                    "a declaration only you can make");
        }
        Optional<Positioning> positioned = positionFor(subject, label);
        if (positioned.isPresent()) {
            return new Plan(AnswerRoute.AI_PROPOSE, resolution, positioned.get(),
                    positioned.get().describe());
        }
        if (subject.aiEligible()) {
            return new Plan(AnswerRoute.AI_PROPOSE, resolution, null,
                    "open-ended, and answerable from your own material");
        }
        if (!required) {
            return new Plan(AnswerRoute.NOT_APPLICABLE, resolution, null,
                    "optional, and nothing applies");
        }
        return new Plan(AnswerRoute.USER_REQUIRED, resolution, null,
                subject.isUnrecognised()
                        ? "this question has not been classified and nothing in your "
                                + "material answers it"
                        : "nothing on record answers this");
    }

    /**
     * The positioning for a technology question, if that is what this is.
     *
     * <p>Two ways in, and both are gated. A concept explicitly marked
     * {@link Concept.Category#EXPERIENCE} is one; an unclassified question whose
     * wording names a technology is the other, and it is the common one, because
     * there is no concept for Kubernetes and there is not going to be.
     *
     * <p>A recognised concept in any other category never reaches here, which is
     * what keeps this machinery away from sponsorship, salary and the voluntary
     * questions.
     */
    private Optional<Positioning> positionFor(Concept concept, String label) {
        if (concept.allowsExperiencePositioning()) {
            String subject = ExperienceQuestion.subjectOf(label)
                    .map(ExperienceQuestion.Subject::subject)
                    .orElse(concept.label());
            return Optional.of(positioner.position(subject));
        }
        if (!concept.isUnrecognised()) {
            return Optional.empty();
        }
        return ExperienceQuestion.subjectOf(label)
                .map(subject -> positioner.position(subject.subject()));
    }
}
