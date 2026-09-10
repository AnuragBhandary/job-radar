package com.anuragbhandary.jobradar.knowledge;

import com.anuragbhandary.jobradar.apply.ApplicantProfile;
import com.anuragbhandary.jobradar.apply.form.FieldClassifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Brings the old {@code extra-answers} list into the knowledge store.
 *
 * <p>Twenty-one {@code {match, answer}} pairs, each of which applied everywhere
 * and forever because there was no way to say otherwise. The migration's job is
 * not to pretend those were scoped decisions - nobody chose a scope, the concept
 * did not exist - but to record what is known, say what is not, and put the
 * dangerous ones in front of a human.
 *
 * <h2>Three outcomes, and the split is the point</h2>
 * <ul>
 *   <li><b>Safe and classifiable</b> - "Are you at least 18?", "Highest level of
 *       education". The answer genuinely does not change with the country, so it
 *       migrates approved at the concept's own default scope and keeps
 *       working.</li>
 *   <li><b>Context-sensitive</b> - sponsorship, work authorisation, remote
 *       willingness. These migrate <em>pending</em>: the level is recorded, the
 *       value it applies to is not, and the assertion is inert until he says
 *       which country it was for. Guessing "global" here is precisely the bug
 *       this phase exists to prevent, and the guess would be invisible.</li>
 *   <li><b>Unclassifiable</b> - "Which working setup gets the best work out of
 *       you?". Recorded under a legacy concept id so the text is not lost, and
 *       left for review.</li>
 * </ul>
 *
 * <p>Nothing here changes what fills a real form today. {@code AnswerStore} and
 * {@code FieldMapper} are untouched and still authoritative, so every one of
 * these answers keeps working exactly as it did while the new resolver is being
 * checked against it in shadow.
 */
@Service
public class ExtraAnswerMigration {

    private static final Logger log = LoggerFactory.getLogger(ExtraAnswerMigration.class);

    private final ApplicantProfile profile;
    private final AssertionRepository assertions;

    public ExtraAnswerMigration(ApplicantProfile profile, AssertionRepository assertions) {
        this.profile = profile;
        this.assertions = assertions;
    }

    /**
     * @param approved     migrated as usable knowledge
     * @param pendingScope migrated inert, awaiting a scope
     * @param unclassified migrated under a legacy id, awaiting a concept
     * @param skipped      already migrated on an earlier run
     */
    public record Report(
            int total, int approved, int pendingScope, int unclassified, int skipped,
            List<String> notes) {

        public int migrated() {
            return approved + pendingScope + unclassified;
        }
    }

    /** Idempotent: an entry already carrying its original question is left alone. */
    @Transactional
    public Report migrate() {
        List<ApplicantProfile.ExtraAnswer> existing =
                profile == null ? List.of() : profile.extraAnswers();

        int approved = 0;
        int pending = 0;
        int unclassified = 0;
        int skipped = 0;
        List<String> notes = new ArrayList<>();

        for (ApplicantProfile.ExtraAnswer entry : existing) {
            if (entry.match() == null || entry.match().isBlank()) {
                continue;
            }
            if (alreadyMigrated(entry.match())) {
                skipped++;
                continue;
            }

            Optional<Concept> found = Concepts.byAlias(entry.match());
            if (found.isEmpty()) {
                store(legacyConceptId(entry.match()), entry, Scope.application(null),
                        false, true,
                        "no concept matched this question; the old matcher still serves it");
                unclassified++;
                notes.add("unclassified: \"" + entry.match() + "\"");
                continue;
            }

            Concept concept = found.get();
            if (concept.contextSensitive() || !concept.allowsScope(concept.defaultScope())
                    || concept.defaultScope() != Scope.Level.GLOBAL) {
                // Its answer depends on something the old list never recorded.
                // Stored with the level it belongs at and no value, so it cannot
                // resolve until he says where it applies.
                store(concept.id(), entry, Scope.of(concept.defaultScope(), null),
                        false, true,
                        "migrated from extra-answers; the " + concept.defaultScope()
                                .name().toLowerCase(Locale.ROOT)
                                + " it applies to was never recorded");
                pending++;
                notes.add("needs a scope: " + concept.id() + " <- \"" + entry.match() + "\"");
            } else {
                store(concept.id(), entry, Scope.global(), true, false,
                        "migrated from extra-answers; this answer does not change "
                                + "with the country or the employer");
                approved++;
            }
        }

        Report report = new Report(existing.size(), approved, pending, unclassified,
                skipped, List.copyOf(notes));
        log.info("Migrated {} of {} extra-answers: {} usable, {} awaiting a scope, "
                        + "{} unclassified, {} already done",
                report.migrated(), report.total(), approved, pending, unclassified, skipped);
        return report;
    }

    private void store(String conceptId, ApplicantProfile.ExtraAnswer entry, Scope scope,
            boolean approved, boolean needsReview, String note) {

        Assertion assertion = new Assertion(conceptId, entry.answer(), scope,
                KnowledgeSource.USER_RULE);
        assertion.setSourceQuestion(entry.match());
        assertion.setNote(note);
        assertion.setNeedsReview(needsReview);
        // Written directly rather than through KnowledgeService.remember, so that
        // two old entries mapping to one concept both survive. Superseding one
        // with the other would silently discard an answer during a migration
        // whose entire purpose is to not lose any.
        assertion.setConfidence(approved ? Confidence.HIGH : Confidence.LOW);
        assertion.setEvidenceList(List.of(Evidence.rule(
                "applicant.yml extra-answers", entry.match())));
        if (approved) {
            assertion.approve("extra-answers migration");
        }
        assertions.save(assertion);
    }

    private boolean alreadyMigrated(String match) {
        return assertions.findBySupersededByIdIsNull().stream()
                .anyMatch(a -> match.equals(a.getSourceQuestion()));
    }

    /**
     * A distinct id per unclassified answer.
     *
     * <p>Distinct, and that matters: filing them all under one "unrecognised" id
     * would have each one supersede the last, which is the one thing a migration
     * must not do.
     */
    static String legacyConceptId(String match) {
        String slug = FieldClassifier.normalise(match)
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("(^_+|_+$)", "");
        if (slug.length() > 48) {
            slug = slug.substring(0, 48);
        }
        return "legacy." + (slug.isBlank() ? "unnamed" : slug);
    }
}
