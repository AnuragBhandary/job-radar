package com.anuragbhandary.jobradar.knowledge;

import com.anuragbhandary.jobradar.apply.ApplicantProfile;
import com.anuragbhandary.jobradar.apply.ApplicationDocuments;
import com.anuragbhandary.jobradar.apply.form.Answer;
import com.anuragbhandary.jobradar.apply.form.FieldMapper;
import com.anuragbhandary.jobradar.apply.form.FormField;
import com.anuragbhandary.jobradar.apply.form.SemanticOptions;
import com.anuragbhandary.jobradar.apply.form.SalaryPresenter;
import com.anuragbhandary.jobradar.domain.CountryCodes;
import com.anuragbhandary.jobradar.domain.Posting;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Runs the old mapper and the new resolver over the same fields and diffs them.
 *
 * <p><strong>Read-only.</strong> It fills nothing, submits nothing and changes no
 * answer. The mapper remains the only thing that decides what goes into a real
 * form until these numbers say the resolver may take over, which is the whole
 * point of having them.
 */
@Service
public class ShadowComparator {

    private static final Logger log = LoggerFactory.getLogger(ShadowComparator.class);

    private final FieldMapper mapper;
    private final KnowledgeResolver resolver;
    private final ApplicationContextFactory contexts;
    private final ApplicantProfile profile;
    private final AssertionRepository assertions;

    public ShadowComparator(FieldMapper mapper, KnowledgeResolver resolver,
            ApplicationContextFactory contexts, ApplicantProfile profile,
            AssertionRepository assertions) {
        this.mapper = mapper;
        this.resolver = resolver;
        this.contexts = contexts;
        this.profile = profile;
        this.assertions = assertions;
    }

    public List<ShadowComparison> compare(List<FormField> fields, Posting posting,
            ApplicationDocuments documents) {

        ApplicationContext context = contexts.of(posting, documents);
        List<ShadowComparison> rows = new ArrayList<>(fields.size());
        for (FormField field : fields) {
            Answer old;
            try {
                old = mapper.answer(field, posting, documents);
            } catch (RuntimeException e) {
                // The comparison must never be the thing that breaks a run. A
                // mapper that throws on a field is itself a finding.
                log.debug("Old mapper threw on '{}': {}", field.label(), e.getMessage());
                old = Answer.unanswered("mapper threw: " + e.getMessage());
            }
            Resolution current;
            try {
                current = resolver.resolve(field, context);
            } catch (RuntimeException e) {
                log.debug("Resolver threw on '{}': {}", field.label(), e.getMessage());
                current = Resolution.unknown(Concept.UNRECOGNISED,
                        "resolver threw: " + e.getMessage());
            }
            Resolution reconciled = reconcile(current, field);
            rows.add(ShadowComparison.of(field.label(), old, reconciled,
                    classify(old, reconciled, context)));
        }
        return List.copyOf(rows);
    }

    /**
     * Puts the resolved answer through the same option matching the mapper uses.
     *
     * <p>Without this the comparison is unfair to the resolver and useless as a
     * signal. A sponsorship question whose radio group offers "EU citizenship /
     * Valid work permit / I need sponsorship" gets "Yes" from the knowledge
     * layer, which is the right answer to the question and not one of the
     * strings on the page; the mapper reconciles it and the resolver was being
     * compared before it had.
     *
     * <p>It was worth 72 apparent regressions on the first wide run, every one of
     * them the harness comparing a semantic answer against a rendered one.
     */
    private static Resolution reconcile(Resolution resolution, FormField field) {
        if (!field.isChoice() || field.options() == null || field.options().isEmpty()
                || !resolution.hasValue()) {
            return resolution;
        }
        // The same conversion the real fill path makes, so the shadow measures
        // what would actually be typed. It used the word matcher alone until this
        // phase, which meant the eighty-one comparisons where a board words its
        // options "I need sponsorship" / "I do not need sponsorship" were counted
        // as the resolver having no answer - when what it had was an answer and
        // no way to spell it.
        SemanticOptions.Match match = SemanticOptions.choose(resolution.concept(),
                field.label(), resolution.value(), field.options());
        return match.option()
                .map(option -> new Resolution(resolution.state(), option,
                        resolution.concept(), resolution.confidence(), resolution.source(),
                        resolution.evidence(), resolution.applicable(),
                        resolution.competing(), resolution.explanation()))
                // No option fits. Unanswered rather than the closest, because on
                // a yes/no field the closest wrong option is the opposite answer.
                .orElseGet(() -> new Resolution(resolution.state(), null,
                        resolution.concept(), resolution.confidence(), resolution.source(),
                        resolution.evidence(), resolution.applicable(),
                        resolution.competing(),
                        resolution.explanation() + " (no option matched '"
                                + resolution.value() + "': " + match.why() + ")"));
    }

    /**
     * What a difference means.
     *
     * <p>Deliberately pessimistic: anything it cannot positively explain comes
     * back as a regression, so the bar - zero unexplained regressions - is a bar
     * rather than a hope. A classifier that guessed "probably fine" would make
     * the number meaningless in exactly the direction that matters.
     */
    private ShadowComparison.Category classify(Answer old, Resolution current,
            ApplicationContext context) {

        Concept concept = current.concept();
        boolean oldHas = old != null && old.hasValue();
        boolean newHas = current.hasValue();

        if (concept != null && Concepts.SALARY_EXPECTATION.id().equals(concept.id())
                && oldHas && newHas && sameMoney(old.value(), current.value(), context)) {
            // The knowledge layer names the band; the fill layer decides whether
            // this particular box wants a number, a range or a sentence.
            return ShadowComparison.Category.PRESENTATION;
        }

        if (!oldHas && newHas) {
            return ShadowComparison.Category.IMPROVEMENT;
        }

        if (oldHas && !newHas) {
            if (concept == null || concept.isUnrecognised()) {
                // A question no concept covers. The old substring matcher still
                // serves it, and will until it has a concept or an alias.
                return ShadowComparison.Category.LEGITIMATE;
            }
            if (current.state() == Resolution.State.CONFLICT) {
                // Refusing to pick between two contradictory stored answers is
                // the resolver working, not failing.
                return ShadowComparison.Category.LEGITIMATE;
            }
            if (hasPendingKnowledge(concept)) {
                // Migrated from extra-answers and waiting on a scope. The old
                // path serves it meanwhile; the knowledge review page is where
                // this stops being true.
                return ShadowComparison.Category.LEGITIMATE;
            }
            return ShadowComparison.Category.REGRESSION;
        }

        if (oldHas && newHas) {
            if (concept != null && pendingValueMatches(concept, old.value())) {
                // The old answer came from an extra-answers entry that is now
                // imported and waiting on a scope - often a form-specific option
                // string like "I need sponsorship" stored globally, which is
                // exactly why it needed rescoping. The resolver falls back to the
                // derivation meanwhile, and the two agree about the substance.
                return ShadowComparison.Category.LEGITIMATE;
            }
            if (concept != null && concept.contextSensitive()
                    && current.source() == KnowledgeSource.DERIVED
                    && employmentDiffersFromPosting(context)) {
                // The two answers differ because the new one read where the
                // employee will sit and the old one read where the job is. That
                // is the whole point of the phase before this one.
                return ShadowComparison.Category.IMPROVEMENT;
            }
            return ShadowComparison.Category.REGRESSION;
        }
        return ShadowComparison.Category.UNCLASSIFIED;
    }

    /** Two renderings of the same band are the same answer. */
    private boolean sameMoney(String oldValue, String newValue, ApplicationContext context) {
        if (profile == null || profile.compensation() == null) {
            return false;
        }
        ApplicantProfile.Compensation.Band band = profile.compensation().bandFor(
                CountryCodes.toLegacy(context == null ? null : context.employmentCountryCode(),
                        context == null ? null : context.strategicClass()));
        return SalaryPresenter.isSameIntent(profile.compensation(), band, oldValue, newValue);
    }

    /** True when this concept has knowledge imported but not yet scoped. */
    private boolean hasPendingKnowledge(Concept concept) {
        return assertions.findByConceptIdAndSupersededByIdIsNull(concept.id()).stream()
                .anyMatch(assertion -> !assertion.isUsable());
    }

    /** True when the old answer is verbatim a migrated answer awaiting a scope. */
    private boolean pendingValueMatches(Concept concept, String oldValue) {
        return assertions.findByConceptIdAndSupersededByIdIsNull(concept.id()).stream()
                .filter(assertion -> !assertion.isUsable())
                .anyMatch(assertion -> assertion.getValue() != null
                        && assertion.getValue().trim().equalsIgnoreCase(oldValue.trim()));
    }

    private static boolean employmentDiffersFromPosting(ApplicationContext context) {
        if (context == null) {
            return false;
        }
        String employment = context.employmentCountryCode();
        return employment == null || !employment.equals(context.countryCode());
    }

    /** The markdown block written beside the review file. */
    public String render(List<ShadowComparison> rows) {
        StringBuilder out = new StringBuilder("## Shadow comparison\n\n");
        out.append("The new knowledge resolver ran alongside the mapper that actually ")
                .append("filled this form. Nothing here changed what was entered.\n\n");
        out.append(String.format("Agreement on answered fields: %.0f%% of %d compared%n%n",
                ShadowComparison.agreementRate(rows) * 100,
                rows.stream().filter(r -> r.verdict()
                        != ShadowComparison.Verdict.BOTH_BLANK).count()));

        ShadowComparison.summarise(rows).forEach((verdict, count) ->
                out.append("- ").append(verdict).append(": ").append(count).append('\n'));

        List<ShadowComparison> findings = rows.stream()
                .filter(ShadowComparison::isFinding).toList();
        if (!findings.isEmpty()) {
            out.append("\n### Worth reading\n\n```\n");
            findings.forEach(row -> out.append(row.describe()).append('\n'));
            out.append("```\n");
        }
        out.append("\n### Every field\n\n```\n");
        rows.forEach(row -> out.append(row.describe()).append('\n'));
        out.append("```\n");
        return out.toString();
    }
}
