package com.anuragbhandary.jobradar.apply.form;

import com.anuragbhandary.jobradar.apply.ApplicationDocuments;
import com.anuragbhandary.jobradar.knowledge.AnswerPlan;
import com.anuragbhandary.jobradar.knowledge.AnswerRoute;
import com.anuragbhandary.jobradar.knowledge.ApplicationContext;
import com.anuragbhandary.jobradar.knowledge.Concept;
import com.anuragbhandary.jobradar.knowledge.KnowledgeResolver;
import com.anuragbhandary.jobradar.knowledge.Resolution;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * The form adapter: a semantic answer, converted into what this page will accept.
 *
 * <p>The seam the whole architecture turns on. Above it, the resolver decides
 * what is <em>true</em> - "sponsorship is required" - from the profile, the
 * stored rules and the application context, and it has never seen an HTML option.
 * Below it, this class decides what to <em>type</em> - "I will require employer
 * sponsorship" - from the strings this particular board chose, and it has no
 * opinion about the applicant.
 *
 * <p>Keeping those apart is what makes both testable. A resolver that knew about
 * dropdowns would need a form to test a sponsorship rule; an option matcher that
 * knew about visas would have to be re-argued every time a board reworded a radio
 * button.
 *
 * <h2>It refuses rather than guesses</h2>
 * Every path that cannot produce a confident value returns
 * {@link Answer.Origin#UNANSWERED} with the reason attached, and a required field
 * with no answer stops the application. That is not a gap in the feature, it is
 * the feature: the alternative on a two-option sponsorship radio is a fifty-fifty
 * chance of an auto-reject.
 */
@Component
public class KnowledgeAnswers {

    private final AnswerPlan plan;
    private final KnowledgeResolver resolver;
    /** Only for the compensation bands, which the salary widget needs re-shaping. */
    private final com.anuragbhandary.jobradar.apply.ApplicantProfile profile;

    public KnowledgeAnswers(AnswerPlan plan, KnowledgeResolver resolver,
            com.anuragbhandary.jobradar.apply.ApplicantProfile profile) {
        this.plan = plan;
        this.resolver = resolver;
        this.profile = profile;
    }

    /**
     * What to put in one field, decided by the knowledge system.
     *
     * <p>The same shape as {@link FieldMapper#answer}, deliberately: the two are
     * swapped by one flag and compared by the shadow, and a different return type
     * would make both of those harder than the switch is worth.
     */
    public Answer answer(FormField field, ApplicationContext context,
            ApplicationDocuments documents) {

        Concept concept = resolver.conceptFor(field);
        AnswerPlan.Plan route = plan.planFor(concept, field.label(), context,
                field.required());

        return switch (route.route()) {
            case NOT_APPLICABLE -> declined(field, route);
            // A draft or a positioning is not something to type into a live form
            // without being read. The preparation screen is where those are
            // approved; here they are an unanswered field with a reason.
            case AI_PROPOSE -> Answer.unanswered(route.because());
            case USER_REQUIRED -> Answer.unanswered(route.because());
            case AUTO_RESOLVE -> fill(field, concept, route.resolution(), documents);
        };
    }

    /**
     * A settled answer, converted into this form's own wording.
     *
     * <p>Three shapes of control and three conversions. A file input wants the
     * path the run rendered; a salary box wants whatever that particular widget
     * asks for; a dropdown wants one of its own strings. Everything else takes the
     * value as it is.
     */
    private Answer fill(FormField field, Concept concept, Resolution resolution,
            ApplicationDocuments documents) {

        if (field.kind() == FieldKind.RESUME_UPLOAD) {
            return documents == null || documents.resumePdf() == null
                    ? Answer.unanswered("no resume was rendered for this application")
                    : Answer.profile(documents.resumePdf().toString());
        }
        if (!resolution.hasValue()) {
            return Answer.unanswered(resolution.explanation());
        }

        // Salary is the clearest case of the separation this class exists for:
        // which band applies is knowledge, and "does this box want 50000, EUR
        // 50,000 or a sentence" is a fact about the widget. The resolver names
        // the band; the presenter decides its shape for this control.
        String value = field.kind() == FieldKind.SALARY_EXPECTATION
                ? salaryFor(field, resolution)
                : resolution.value();
        if (value == null || value.isBlank()) {
            return Answer.unanswered(resolution.explanation());
        }

        if (!field.isChoice()) {
            return fromResolution(resolution, value);
        }

        SemanticOptions.Match match = SemanticOptions.choose(concept, field.label(),
                value, field.options());
        if (match.matched()) {
            Answer chosen = fromResolution(resolution, match.option().orElseThrow());
            return new Answer(chosen.value(), chosen.origin(),
                    resolution.explanation() + "; " + match.why());
        }
        // The knowledge is right and the wording cannot be mapped. Said plainly,
        // because "no configured answer" would send him looking in the wrong
        // place - the profile is fine and the form is the problem.
        return Answer.unanswered("the answer is '" + value + "' and none of this form's "
                + "options could be matched to it: " + match.why());
    }

    /**
     * The band the resolver chose, written the way this box wants it.
     *
     * <p>The band is found again from the same employment country the resolution
     * used, rather than parsed back out of its text. Reading a currency and two
     * numbers out of a sentence to re-format them would be a second, differently
     * wrong implementation of something the profile already holds structured.
     */
    private String salaryFor(FormField field, Resolution resolution) {
        if (profile == null || profile.compensation() == null) {
            return resolution.value();
        }
        var band = profile.compensation().bands() == null ? null
                : profile.compensation().bands().values().stream()
                        .filter(candidate -> resolution.value() != null
                                && resolution.value().equals(candidate.textAnswer()))
                        .findFirst().orElse(null);
        if (band == null) {
            return resolution.value();
        }
        return SalaryPresenter.render(profile.compensation(), band, field);
    }

    /**
     * A voluntary question, answered by declining where the form offers one.
     *
     * <p>Choosing "prefer not to say" explicitly is a better answer than leaving a
     * required dropdown untouched, and it is what the profile means by leaving the
     * field unset.
     */
    private Answer declined(FormField field, AnswerPlan.Plan route) {
        if (field.isChoice()) {
            List<String> options = field.options();
            return OptionMatcher.declineOption(options)
                    .map(option -> new Answer(option, Answer.Origin.DECLINED,
                            route.because()))
                    .orElseGet(() -> Answer.declined(route.because()));
        }
        return Answer.declined(route.because());
    }

    /**
     * Carries the resolution's provenance onto the answer.
     *
     * <p>{@code DERIVED} rather than {@code PROFILE} for anything worked out, so
     * the review file and the field record keep flagging it for a human - a name
     * copied from the profile is right by construction and a sponsorship answer is
     * right only if the derivation is.
     */
    private static Answer fromResolution(Resolution resolution, String value) {
        return switch (resolution.state()) {
            case DERIVED -> Answer.derived(value, resolution.explanation());
            case AI_PROPOSED -> Answer.generated(value, resolution.explanation());
            default -> resolution.source()
                    == com.anuragbhandary.jobradar.knowledge.KnowledgeSource.PROFILE
                    ? Answer.profile(value)
                    : Answer.derived(value, resolution.explanation());
        };
    }
}
