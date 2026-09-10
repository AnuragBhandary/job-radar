package com.anuragbhandary.jobradar.cli;

import com.anuragbhandary.jobradar.apply.ApplicationAttempt;
import com.anuragbhandary.jobradar.apply.ApplicationAttemptRepository;
import com.anuragbhandary.jobradar.apply.ApplicationDocuments;
import com.anuragbhandary.jobradar.apply.form.FieldClassifier;
import com.anuragbhandary.jobradar.apply.form.FormField;
import com.anuragbhandary.jobradar.domain.Posting;
import com.anuragbhandary.jobradar.knowledge.ApplicationContext;
import com.anuragbhandary.jobradar.knowledge.ApplicationContextFactory;
import com.anuragbhandary.jobradar.knowledge.Assertion;
import com.anuragbhandary.jobradar.knowledge.Concept;
import com.anuragbhandary.jobradar.knowledge.Concepts;
import com.anuragbhandary.jobradar.knowledge.ExtraAnswerMigration;
import com.anuragbhandary.jobradar.knowledge.KnowledgeAudit;
import com.anuragbhandary.jobradar.knowledge.KnowledgeResolver;
import com.anuragbhandary.jobradar.knowledge.KnowledgeService;
import com.anuragbhandary.jobradar.knowledge.Resolution;
import com.anuragbhandary.jobradar.knowledge.ShadowComparator;
import com.anuragbhandary.jobradar.knowledge.ShadowComparison;
import com.anuragbhandary.jobradar.knowledge.ShadowReport;
import com.anuragbhandary.jobradar.knowledge.ShadowRunner;
import com.anuragbhandary.jobradar.repo.PostingRepository;
import com.anuragbhandary.jobradar.web.FieldRow;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * {@code knowledge} - the debug surface for the knowledge core.
 *
 * <p>Not the eventual Knowledge page, deliberately. The resolver has to be
 * trustworthy before anything is built on top of it, and the way to find out is
 * to be able to ask it questions and to diff it against the mapper that fills
 * real forms - both of which are commands rather than screens.
 */
@Component
public class KnowledgeCommand {

    private final ExtraAnswerMigration migration;
    private final KnowledgeService knowledge;
    private final KnowledgeResolver resolver;
    private final ShadowComparator shadow;
    private final KnowledgeAudit audit;
    private final com.anuragbhandary.jobradar.knowledge.experience.ExperiencePositioner
            positioner;
    private final ApplicationContextFactory contexts;
    private final ApplicationAttemptRepository attempts;
    private final PostingRepository postings;
    private final FieldClassifier classifier;
    private final ShadowRunner runner;

    public KnowledgeCommand(ExtraAnswerMigration migration, KnowledgeService knowledge,
            KnowledgeResolver resolver, ShadowComparator shadow, KnowledgeAudit audit,
            com.anuragbhandary.jobradar.knowledge.experience.ExperiencePositioner positioner,
            ApplicationContextFactory contexts, ApplicationAttemptRepository attempts,
            PostingRepository postings, FieldClassifier classifier,
            ShadowRunner runner) {
        this.migration = migration;
        this.knowledge = knowledge;
        this.resolver = resolver;
        this.shadow = shadow;
        this.audit = audit;
        this.positioner = positioner;
        this.contexts = contexts;
        this.attempts = attempts;
        this.postings = postings;
        this.classifier = classifier;
        this.runner = runner;
    }

    public void run(Map<String, String> options) {
        if (options.containsKey("migrate")) {
            migrate();
        } else if (options.containsKey("review")) {
            review();
        } else if (options.containsKey("shadow")) {
            shadow(options);
        } else if (options.containsKey("repair")) {
            repair(!"dry".equals(options.get("repair")));
        } else if (options.containsKey("adopt")) {
            adopt(!"dry".equals(options.get("adopt")));
        } else if (options.containsKey("audit")) {
            audit();
        } else if (options.containsKey("position")) {
            position(options.get("position"));
        } else if (options.containsKey("explain")) {
            explain(options);
        } else {
            summary();
        }
    }

    // ------------------------------------------------------------------

    /**
     * Marks stored answers that cannot be answers to their own concept.
     *
     * <p>Two rows in the live database hold a rule written in prose where a yes
     * or no belongs. They are approved, live, and outrank the derivation that
     * gets sponsorship right - so the day the resolver becomes the authority,
     * that whole sentence is what reaches a German form's radio button.
     *
     * <p>Nothing is deleted and no value is rewritten. What changes is that the
     * row stops competing, which is the thing that was actually wrong with it:
     * the derivation underneath already answers correctly for every country,
     * which is more than one country-scoped rule could ever do.
     */
    private void repair(boolean apply) {
        List<Assertion> live = knowledge.live();
        List<Assertion> malformed = live.stream()
                .filter(assertion -> !assertion.isInvalid())
                .filter(assertion -> Concepts.byId(assertion.getConceptId())
                        .map(concept -> !concept.answerType()
                                .accepts(assertion.getValue()))
                        .orElse(false))
                .toList();

        System.out.printf("%n%d stored answer(s) are not usable answers to their own "
                + "question%n", malformed.size());
        System.out.println("─".repeat(78));
        if (malformed.isEmpty()) {
            System.out.println("  Nothing to repair.");
        }
        for (Assertion assertion : malformed) {
            Concept concept = Concepts.byId(assertion.getConceptId()).orElseThrow();
            System.out.printf("%n  %s %s at %s%n     wants: %s%n     holds: \"%s\"%n",
                    apply ? "✓" : "·", assertion.getConceptId(),
                    assertion.scope().describe(), concept.answerType(),
                    abbreviate(assertion.getValue(), 66));
        }
        if (apply && !malformed.isEmpty()) {
            List<Assertion> marked = knowledge.markMalformed();
            System.out.printf("%n%d marked unusable. The values are kept and the rows are "
                    + "still in the record; they no longer compete in resolution, so the "
                    + "derivation answers instead.%n", marked.size());
        }

        contradictingDerivations(apply);

        // Two answers that are the same fact written for two different form
        // controls. Neither is wrong, and storing a presentation as knowledge is
        // what let them contradict each other.
        duplicatePresentations(apply);

        if (!apply) {
            System.out.println("\nNothing changed. Run without --repair=dry to apply.");
        }
    }

    /**
     * Well-formed rules that say the opposite of what their own country derives.
     *
     * <p>Separate from the malformed ones because the row is not malformed. "I am
     * a citizen or permanent resident of the country where I plan to live and
     * work from" is a real option string from a real board, and filed under
     * Germany it is false - he is neither. The type is right and the meaning is
     * wrong, so only comparing it against the derivation finds it.
     *
     * <p>Discarded rather than marked invalid: the row is a valid answer to
     * something, just not to this. It stays in the record with its value and its
     * date, and the derivation - which reads the employment country off each
     * application and gets every country right - answers instead.
     */
    private void contradictingDerivations(boolean apply) {
        List<KnowledgeAudit.Finding> contradictions = audit.run().findings().stream()
                .filter(finding -> finding.severity() == KnowledgeAudit.Severity.WRONG)
                .filter(finding -> finding.what().contains("the derivation for that country"))
                .toList();
        if (contradictions.isEmpty()) {
            return;
        }
        System.out.printf("%n%d rule(s) say the opposite of what their own country "
                + "derives%n", contradictions.size());
        System.out.println("─".repeat(78));
        for (KnowledgeAudit.Finding finding : contradictions) {
            System.out.printf("%n  %s %s%n", apply ? "✓" : "·", finding.what());
            if (apply && finding.assertionId() != null) {
                knowledge.discard(finding.assertionId(),
                        "knowledge --repair: contradicted the derivation for its own country");
                System.out.println("     Discarded. Kept in the record; the derivation "
                        + "answers now.");
            }
        }
    }

    /**
     * Stored answers that are really one derivable fact, written twice.
     *
     * <p>"Less than 2 years" and "1" are the same year, formatted for a dropdown
     * and for a number box. Both were approved and global, so whichever the
     * database returned first won - and the fix is not to pick one but to stop
     * storing either, because the employment dates on the resume already say it
     * and the form layer already knows how to shape it.
     */
    private void duplicatePresentations(boolean apply) {
        List<Assertion> years = knowledge.live().stream()
                .filter(Assertion::isUsable)
                .filter(a -> Concepts.YEARS_OF_EXPERIENCE.id().equals(a.getConceptId()))
                .toList();
        if (years.size() < 2) {
            return;
        }
        System.out.printf("%n%d stored answers for '%s', which is derivable from your "
                + "employment dates%n", years.size(), Concepts.YEARS_OF_EXPERIENCE.id());
        System.out.println("─".repeat(78));
        years.forEach(assertion -> System.out.printf("  %s \"%s\" (%s)%n",
                apply ? "✓" : "·", assertion.getValue(), assertion.scope().describe()));

        if (!apply) {
            return;
        }
        years.forEach(assertion -> knowledge.discard(assertion.getId(),
                "knowledge --repair: a presentation of a derivable fact"));
        System.out.println("\n  Discarded, and kept in the record. The derivation counts "
                + "the months on your resume, and the form layer decides whether a "
                + "particular box wants a number or one of its own bands.");
    }

    /**
     * Files legacy answers under the concepts that now exist for them.
     *
     * <p>Three answers are stored under keys made by truncating the question, so
     * nothing resolves against them and each was re-asked in every new wording.
     * The concepts exist now; this moves the answers onto them, superseding
     * rather than rewriting.
     *
     * <p>Only where the wording matches a concept's aliases. An answer that
     * matches nothing is left alone and reported, because inventing a home for it
     * would be guessing at what he meant.
     */
    private void adopt(boolean apply) {
        List<Assertion> legacy = knowledge.needingReview().stream()
                .filter(assertion -> assertion.getConceptId().startsWith("legacy."))
                .toList();
        if (legacy.isEmpty()) {
            System.out.println("\nNothing is stored under a legacy key.");
            return;
        }
        System.out.printf("%n%d answer(s) stored under a truncated question key%n",
                legacy.size());
        System.out.println("─".repeat(78));

        int adopted = 0;
        for (Assertion assertion : legacy) {
            Optional<Concept> home = Concepts.byAlias(assertion.getSourceQuestion());
            if (home.isEmpty()) {
                System.out.printf("%n  ? %s%n     \"%s\"%n     No concept matches this "
                                + "wording. Left as it is.%n",
                        assertion.getConceptId(), assertion.getSourceQuestion());
                continue;
            }
            System.out.printf("%n  %s %s%n     \"%s\"%n     → %s%n",
                    apply ? "✓" : "·", assertion.getConceptId(),
                    assertion.getSourceQuestion(), home.get().id());
            if (!apply) {
                continue;
            }
            try {
                Assertion moved = knowledge.adopt(assertion.getId(), home.get(),
                        "knowledge --adopt");
                adopted++;
                System.out.printf("     now answers %s at %s%n", home.get().label(),
                        moved.scope().describe());
            } catch (IllegalArgumentException e) {
                System.out.printf("     refused: %s%n", e.getMessage());
            }
        }
        System.out.printf("%n%s%n", apply
                ? adopted + " adopted. The old rows are superseded, not deleted."
                : "Nothing changed. Run without --adopt=dry to apply.");
    }

    /**
     * What the system already knows, and what is wrong with what it knows.
     *
     * <p>The answer to "does it really not need to ask me for any of this?".
     * Prints the facts it can answer without anyone, the technologies it read out
     * of the resume, and the findings - most serious first, because two of them
     * are live rules producing wrong answers on real forms.
     */
    private void audit() {
        KnowledgeAudit.Report report = audit.run();

        System.out.printf("%nKnown without asking you: %d facts · %d technologies%n",
                report.known().size(), report.technologies().size());
        System.out.println("─".repeat(78));
        report.known().forEach(line -> System.out.printf("  %-28s %-34s %s%n",
                line.concept(), abbreviate(line.value(), 34), line.source()));

        System.out.printf("%nTechnologies read from your resume (%d)%n",
                report.technologies().size());
        System.out.println("─".repeat(78));
        System.out.println("  " + String.join(", ", report.technologies()));

        System.out.printf("%nStored knowledge: %d in use · %d pending%n",
                report.live(), report.pending());

        if (report.findings().isEmpty()) {
            System.out.println("\nNothing needs a decision.");
            return;
        }
        System.out.printf("%n%d finding(s), most serious first%n", report.findings().size());
        System.out.println("─".repeat(78));
        for (KnowledgeAudit.Finding finding : report.findings()) {
            System.out.printf("%n  [%s] %s%n", finding.severity(), finding.what());
            System.out.printf("      → %s%n", finding.remedy());
            if (finding.assertionId() != null) {
                System.out.printf("      assertion %d%n", finding.assertionId());
            }
        }
        System.out.printf("%n%d of these are producing a wrong answer right now.%n",
                report.serious());
    }

    /**
     * What may be said about one technology, and why.
     *
     * <p>The command for checking a verdict before trusting it: it prints the
     * level, the evidence behind it, and the claims the validator will enforce.
     */
    private void position(String subject) {
        if (subject == null || subject.isBlank()) {
            System.out.println("Usage: knowledge --position=\"kubernetes\"");
            return;
        }
        var positioning = positioner.position(subject);
        System.out.printf("%n%s%n", positioning.describe());
        System.out.println("─".repeat(78));
        System.out.printf("  Level      %s — %s%n", positioning.level(),
                positioning.level().summary());
        System.out.printf("  Direct     %s%n", positioning.isDirect() ? "yes" : "no");
        if (!positioning.evidence().isEmpty()) {
            System.out.println("  Evidence");
            positioning.evidence().forEach(entry -> entry.evidence().forEach(item ->
                    System.out.printf("             %s%n", item.describe())));
        }
        if (!positioning.via().isEmpty()) {
            System.out.printf("  Via        %s%n", String.join(", ", positioning.via()));
        }
        System.out.printf("%n  Framing    %s%n", positioning.framing());
        System.out.println("  May say");
        positioning.mayClaim().forEach(claim -> System.out.printf("             - %s%n", claim));
        System.out.println("  May not say");
        positioning.mustNotSay().forEach(claim ->
                System.out.printf("             - %s%n", claim));
    }

    private static String abbreviate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max - 3) + "...";
    }

    private void migrate() {
        ExtraAnswerMigration.Report report = migration.migrate();
        System.out.printf("%n%d extra-answers: %d migrated as usable, %d awaiting a scope, "
                        + "%d unclassified, %d already done%n",
                report.total(), report.approved(), report.pendingScope(),
                report.unclassified(), report.skipped());
        if (!report.notes().isEmpty()) {
            System.out.println("\nNeeding attention:");
            report.notes().forEach(note -> System.out.println("  " + note));
        }
        System.out.println("\nNothing about form filling has changed. The old matcher still "
                + "serves every one of these until the resolver is made authoritative.");
    }

    private void summary() {
        List<Assertion> live = knowledge.live();
        System.out.printf("%n%d concepts known | %d live assertions | %d awaiting approval "
                        + "| %d needing review%n",
                Concepts.all().size(), live.size(),
                knowledge.awaitingApproval().size(), knowledge.needingReview().size());

        Map<String, Long> byConcept = new java.util.TreeMap<>();
        live.forEach(a -> byConcept.merge(a.getConceptId(), 1L, Long::sum));
        if (!byConcept.isEmpty()) {
            System.out.println("\nAssertions by concept:");
            byConcept.forEach((concept, count) ->
                    System.out.printf("  %3d  %s%n", count, concept));
        }
        System.out.println("\n`knowledge --migrate` imports applicant.yml's extra-answers.");
        System.out.println("`knowledge --shadow` diffs the resolver against the live mapper across the corpus.");
        System.out.println("`knowledge --review` lists knowledge waiting on a scope.");
        System.out.println("`knowledge --audit` prints everything it knows without asking, "
                + "and what is wrong with it.");
        System.out.println("`knowledge --position=\"kubernetes\"` shows what may honestly "
                + "be said about a technology.");
        System.out.println("`knowledge --adopt` files legacy answers under the concepts "
                + "that now exist for them (--adopt=dry to preview).");
        System.out.println("`knowledge --repair` marks stored answers that are not usable "
                + "answers to their own question (--repair=dry to preview).");
        System.out.println("`knowledge --explain=\"do you require sponsorship\" "
                + "--posting-id=N` answers one question.");
    }

    private void review() {
        List<Assertion> needing = knowledge.needingReview();
        if (needing.isEmpty()) {
            System.out.println("\nNothing is waiting on a decision.");
            return;
        }
        System.out.printf("%n%d answer(s) migrated with a scope nobody chose:%n%n",
                needing.size());
        for (Assertion assertion : needing) {
            System.out.printf("  [%d] %s%n", assertion.getId(), assertion.getConceptId());
            System.out.printf("       question: %s%n", assertion.getSourceQuestion());
            System.out.printf("       answer:   %s%n", assertion.getValue());
            System.out.printf("       scope:    %s%n", assertion.scope().describe());
            System.out.printf("       why:      %s%n%n", assertion.getNote());
        }
    }

    /**
     * Diffs the resolver against the mapper over the questions real boards have
     * actually asked.
     *
     * <p>The labels come out of the recorded field logs rather than being
     * invented, so the comparison is against the wording Greenhouse, Ashby and
     * Recruitee really used on the applications already prepared.
     */
    /**
     * Diffs the resolver against the mapper.
     *
     * <p>By default across the whole corpus: every distinct question the boards
     * have actually asked, against a stratified sample of real postings. The
     * per-attempt run that came before is still there behind {@code --attempts},
     * and it is the more literal comparison - the same fields on the same forms -
     * but four applications between them cover two countries.
     */
    private void shadow(Map<String, String> options) {
        if (options.containsKey("attempts")) {
            shadowOverAttempts(options);
            return;
        }
        ShadowRunner.Dataset dataset = runner.run();
        if (dataset.rows().isEmpty()) {
            System.out.println("\nNothing to compare. Prepare an application first, and "
                    + "screen the corpus so there are postings to ask against.");
            return;
        }
        ShadowReport report = ShadowReport.of(dataset.rows(), dataset.questions().size(),
                dataset.contexts().size(), runner.coverage(dataset.contexts()));
        System.out.println();
        System.out.println(report.render());

        if (options.containsKey("verbose")) {
            System.out.println("Every difference:\n");
            dataset.rows().stream().filter(ShadowComparison::isFinding).distinct()
                    .forEach(row -> System.out.println("  " + row.describe()));
        }
    }

    /** The original comparison: the exact fields of the applications already prepared. */
    private void shadowOverAttempts(Map<String, String> options) {
        List<ShadowComparison> all = new ArrayList<>();
        int applications = 0;

        for (ApplicationAttempt attempt : attempts.findAll()) {
            if (attempt.getFieldLog() == null || attempt.getFieldLog().isBlank()) {
                continue;
            }
            Optional<Posting> posting = postings.findById(attempt.getPostingId());
            if (posting.isEmpty()) {
                continue;
            }
            List<FormField> fields = fieldsFrom(attempt);
            if (fields.isEmpty()) {
                continue;
            }
            applications++;
            all.addAll(shadow.compare(fields, posting.get(), documents()));
        }

        if (all.isEmpty()) {
            System.out.println("\nNo recorded field logs to compare against.");
            return;
        }
        System.out.printf("%n%d field(s) across %d recorded application(s)%n",
                all.size(), applications);
        System.out.printf("Agreement on answered fields: %.1f%%%n%n",
                ShadowComparison.agreementRate(all) * 100);
        ShadowComparison.summarise(all).forEach((verdict, count) ->
                System.out.printf("  %4d  %s%n", count, verdict));

        List<ShadowComparison> findings = all.stream()
                .filter(ShadowComparison::isFinding).toList();
        if (!findings.isEmpty()) {
            System.out.printf("%n%d difference(s):%n%n", findings.size());
            findings.forEach(row -> System.out.println("  " + row.describe()));
        }
    }

    private void explain(Map<String, String> options) {
        String question = options.get("explain");
        Concept concept = resolver.conceptFor(question);
        ApplicationContext context = Optional.ofNullable(options.get("posting-id"))
                .map(Long::valueOf)
                .flatMap(postings::findById)
                .map(contexts::of)
                .orElseGet(contexts::empty);

        System.out.printf("%nQuestion: %s%n", question);
        System.out.println(resolver.trace(concept, context, java.time.LocalDate.now()));
    }

    // ------------------------------------------------------------------

    /**
     * Rebuilds form fields from a recorded field log.
     *
     * <p>Only the label survives the log, so the control type is assumed and the
     * options are empty. That is enough for this comparison, which is about which
     * answer each side produces rather than how it would be typed in - and using
     * the real wording matters far more than reconstructing the widget.
     */
    private List<FormField> fieldsFrom(ApplicationAttempt attempt) {
        List<FormField> fields = new ArrayList<>();
        for (FieldRow row : FieldRow.parse(attempt.getFieldLog())) {
            String label = row.label();
            if (label == null || label.isBlank()) {
                continue;
            }
            FormField field = new FormField("#shadow", label.trim(),
                    FormField.ControlType.TEXT, List.of(), false,
                    com.anuragbhandary.jobradar.apply.form.FieldKind.UNKNOWN);
            fields.add(field.withKind(classifier.classify(field)));
        }
        return fields;
    }

    /** A stand-in: the comparison never touches a file, only the answer text. */
    private static ApplicationDocuments documents() {
        return new ApplicationDocuments(Path.of("/tmp/shadow-resume.pdf"), null, null);
    }
}
