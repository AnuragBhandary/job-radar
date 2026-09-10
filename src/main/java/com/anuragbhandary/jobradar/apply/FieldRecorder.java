package com.anuragbhandary.jobradar.apply;

import com.anuragbhandary.jobradar.apply.form.Answer;
import com.anuragbhandary.jobradar.apply.form.FieldKind;
import com.anuragbhandary.jobradar.apply.form.FillReport;
import com.anuragbhandary.jobradar.domain.Posting;
import com.anuragbhandary.jobradar.knowledge.ApplicationContext;
import com.anuragbhandary.jobradar.knowledge.Concept;
import com.anuragbhandary.jobradar.knowledge.Confidence;
import com.anuragbhandary.jobradar.knowledge.Evidence;
import com.anuragbhandary.jobradar.knowledge.KnowledgeResolver;
import com.anuragbhandary.jobradar.knowledge.KnowledgeSource;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns what happened to a form into rows that can be queried.
 *
 * <p>The bridge out of prose. Until now the only record of a filled form was
 * {@code ApplicationAttempt.fieldLog} - the rendered output of
 * {@code FillReport.Entry.describe()} - which the review page parsed back with
 * {@code lastIndexOf("   [")}. That is written still, because it reads well in a
 * terminal, but these rows are the record.
 *
 * <h2>What it does not do</h2>
 * It does not decide any answer. The values here are the ones
 * {@link com.anuragbhandary.jobradar.apply.form.FieldMapper} actually used, which
 * is what was actually typed into a real employer's form. The new resolver's
 * opinion of the same fields goes into the shadow report and nowhere near this
 * table until it has earned it.
 */
@Service
public class FieldRecorder {

    private static final Logger log = LoggerFactory.getLogger(FieldRecorder.class);

    private final ApplicationFieldRepository fields;
    private final QuestionSightingRepository sightings;
    private final KnowledgeResolver resolver;

    public FieldRecorder(ApplicationFieldRepository fields, QuestionSightingRepository sightings,
            KnowledgeResolver resolver) {
        this.fields = fields;
        this.sightings = sightings;
        this.resolver = resolver;
    }

    /**
     * Records one filled form, replacing anything recorded for this attempt.
     *
     * @return the attempt status these fields add up to
     */
    @Transactional
    public AttemptStatus record(Long attemptId, Posting posting, ApplicationContext context,
            FillReport report, String company) {

        fields.deleteByAttemptId(attemptId);
        sightings.deleteByAttemptId(attemptId);

        List<ApplicationField> saved = new ArrayList<>();
        for (FillReport.Entry entry : report.entries()) {
            ApplicationField field = toField(attemptId, entry);
            saved.add(fields.save(field));
            sightings.save(toSighting(attemptId, posting, context, company, field));
        }
        log.debug("Recorded {} structured field(s) for attempt {}", saved.size(), attemptId);
        return statusFor(saved);
    }

    /**
     * One field, as a row.
     *
     * <p>The mapping from the old {@link Answer.Origin} is where the four
     * situations get separated. {@code UNANSWERED} on a required field and
     * {@code UNANSWERED} on an optional one were the same value and are now two
     * states; an answer that filled and an answer that threw were the same state
     * and are now two.
     */
    private ApplicationField toField(Long attemptId, FillReport.Entry entry) {
        Answer answer = entry.answer();
        boolean required = entry.field().required();

        FieldState state = switch (answer.origin()) {
            case PROFILE -> FieldState.RESOLVED;
            case DERIVED -> FieldState.RESOLVED;
            // Written by a model and therefore never settled by the machine, even
            // though it was typed in: the review page is where it gets read.
            case GENERATED -> FieldState.AWAITING_APPROVAL;
            case DECLINED -> FieldState.DECLINED;
            case UNANSWERED -> required
                    ? FieldState.AWAITING_ANSWER : FieldState.SKIPPED_OPTIONAL;
        };

        ApplicationField field = new ApplicationField(attemptId, label(entry), state);
        field.setConceptId(conceptId(entry));
        field.setControlType(entry.field().control() == null ? null
                : entry.field().control().name());
        field.setRequired(required);
        field.setOptionList(entry.field().options());
        field.setResolvedValue(answer.value());
        field.setSource(sourceOf(answer.origin()));
        field.setConfidence(confidenceOf(answer.origin()));
        field.setExplanation(answer.note());
        field.setEvidenceList(evidenceOf(answer));

        if (entry.error() != null) {
            // The rule this whole phase turns on: the answer stays. Job Radar
            // knowing something and the page refusing it is not the same as Job
            // Radar not knowing, and the person finishing by hand needs the value.
            field.blockedBy(entry.error());
        } else if (entry.filled()) {
            field.filled();
        } else {
            field.setAutomationState(state == FieldState.DECLINED
                    || state == FieldState.SKIPPED_OPTIONAL
                    ? AutomationState.NOT_APPLICABLE : AutomationState.NOT_ATTEMPTED);
        }
        return field;
    }

    private QuestionSighting toSighting(Long attemptId, Posting posting,
            ApplicationContext context, String company, ApplicationField field) {

        QuestionSighting sighting = new QuestionSighting(field.getRawLabel(), field.getState());
        sighting.setConceptId(field.getConceptId());
        sighting.setAttemptId(attemptId);
        sighting.setRequired(field.isRequired());
        if (posting != null) {
            sighting.setPostingId(posting.getId());
            sighting.setBoardToken(posting.getBoardToken());
            sighting.setAtsSource(posting.getSource() == null ? null
                    : posting.getSource().name());
        }
        sighting.setCompany(company);
        // The employment country, not the posting's: it is what an answer to a
        // context-sensitive question would have been scoped to.
        sighting.setCountryCode(context == null ? null : context.employmentCountryCode());
        return sighting;
    }

    /**
     * What the fields add up to.
     *
     * <p>Ordered by who has to act. A required question with no answer outranks
     * everything, because answering it teaches Job Radar something every future
     * form will use; a blocked control is the browser's problem, not his.
     */
    static AttemptStatus statusFor(List<ApplicationField> fields) {
        if (fields.stream().anyMatch(f -> f.getState() == FieldState.AWAITING_ANSWER)) {
            return AttemptStatus.AWAITING_ANSWER;
        }
        if (fields.stream().anyMatch(f -> f.getState() == FieldState.AWAITING_APPROVAL)) {
            return AttemptStatus.AWAITING_APPROVAL;
        }
        if (fields.stream().anyMatch(f -> f.getAutomationState() == AutomationState.BLOCKED)) {
            return AttemptStatus.MANUAL_REQUIRED;
        }
        return AttemptStatus.READY_FOR_REVIEW;
    }

    // ------------------------------------------------------------------

    private String conceptId(FillReport.Entry entry) {
        // Read-only: the classifier is consulted for the concept id so sightings
        // can be counted by concept. Nothing about the answer comes from here.
        Concept concept = resolver.conceptFor(entry.field());
        return concept.isUnrecognised() ? null : concept.id();
    }

    private static String label(FillReport.Entry entry) {
        String label = entry.field().label();
        if (label != null && !label.isBlank()) {
            return label.length() > 512 ? label.substring(0, 512) : label.trim();
        }
        return entry.field().selector() == null ? "(unlabelled)" : entry.field().selector();
    }

    private static KnowledgeSource sourceOf(Answer.Origin origin) {
        return switch (origin) {
            case PROFILE -> KnowledgeSource.PROFILE;
            case DERIVED -> KnowledgeSource.DERIVED;
            case GENERATED -> KnowledgeSource.AI_PROPOSED;
            case DECLINED, UNANSWERED -> null;
        };
    }

    private static Confidence confidenceOf(Answer.Origin origin) {
        return switch (origin) {
            case PROFILE, DERIVED, DECLINED -> Confidence.HIGH;
            // A drafted paragraph is never high confidence, whatever it reads like.
            case GENERATED -> Confidence.MEDIUM;
            case UNANSWERED -> null;
        };
    }

    /**
     * The reasoning that was recorded, as a citation.
     *
     * <p>Thin by design: the old mapper carried one prose note, so that is all
     * there is to record. Fields resolved by the knowledge system carry real
     * {@link Evidence} lists, and this is what the difference looks like from
     * the other side.
     */
    private static List<Evidence> evidenceOf(Answer answer) {
        if (answer.note() == null || answer.note().isBlank()) {
            return List.of();
        }
        return List.of(Evidence.context("mapper note", answer.note()));
    }

    /** Only for tests: the kinds whose answers are voluntary rather than missing. */
    static boolean isVoluntary(FieldKind kind) {
        return kind == FieldKind.GENDER || kind == FieldKind.RACE
                || kind == FieldKind.HISPANIC_LATINO || kind == FieldKind.VETERAN_STATUS
                || kind == FieldKind.DISABILITY_STATUS || kind == FieldKind.PRONOUNS;
    }
}
