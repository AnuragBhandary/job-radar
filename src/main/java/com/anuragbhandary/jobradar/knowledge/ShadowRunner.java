package com.anuragbhandary.jobradar.knowledge;

import com.anuragbhandary.jobradar.apply.ApplicationAttempt;
import com.anuragbhandary.jobradar.apply.ApplicationAttemptRepository;
import com.anuragbhandary.jobradar.apply.ApplicationDocuments;
import com.anuragbhandary.jobradar.apply.OpenQuestion;
import com.anuragbhandary.jobradar.apply.form.FieldClassifier;
import com.anuragbhandary.jobradar.apply.form.FieldKind;
import com.anuragbhandary.jobradar.apply.form.FormField;
import com.anuragbhandary.jobradar.domain.Posting;
import com.anuragbhandary.jobradar.domain.Verdict;
import com.anuragbhandary.jobradar.repo.PostingRepository;
import com.anuragbhandary.jobradar.web.FieldRow;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Runs every question a board has actually asked against every kind of
 * application the corpus contains.
 *
 * <p>The first shadow run compared 82 fields across four recorded applications,
 * two of which were the same posting prepared twice. That is enough to prove the
 * resolver runs and nothing like enough to trust it: the interesting failures are
 * context-dependent, and four applications between them cover two countries and
 * one work mode.
 *
 * <p>So the two halves are separated and recombined. The <b>questions</b> are
 * real - every distinct wording recorded in a field log or an open-question log,
 * with the control type and options where those were captured. The
 * <b>contexts</b> are real - a stratified sample of screened postings, one per
 * distinct country, work mode, strategic lane and ATS. Each question is asked
 * once per context.
 *
 * <p>That is not the same as observing thousands of real fields, and the report
 * says so. What it does test is the thing worth testing: whether the same
 * question gets a different answer in Berlin, Bengaluru and a US company hiring
 * into Mumbai.
 */
@Service
public class ShadowRunner {

    /** Enough spread to cover the corpus without one popular country dominating. */
    private static final int MAX_PER_STRATUM = 2;

    private final ApplicationAttemptRepository attempts;
    private final PostingRepository postings;
    private final ShadowComparator comparator;
    private final FieldClassifier classifier;

    public ShadowRunner(ApplicationAttemptRepository attempts, PostingRepository postings,
            ShadowComparator comparator, FieldClassifier classifier) {
        this.attempts = attempts;
        this.postings = postings;
        this.comparator = comparator;
        this.classifier = classifier;
    }

    /**
     * @param questions distinct real questions
     * @param contexts  distinct real postings they were asked against
     * @param rows      one row per question per context
     */
    public record Dataset(List<FormField> questions, List<Posting> contexts,
            List<ShadowComparison> rows) {

        public int comparisons() {
            return rows.size();
        }
    }

    public Dataset run() {
        List<FormField> questions = questionCorpus();
        List<Posting> contexts = contextCorpus();

        List<ShadowComparison> rows = new ArrayList<>();
        for (Posting posting : contexts) {
            rows.addAll(comparator.compare(questions, posting, documents()));
        }
        return new Dataset(questions, contexts, List.copyOf(rows));
    }

    // ------------------------------------------------------------------

    /**
     * Every distinct question the boards have asked, best-described version first.
     *
     * <p>Two sources, because they capture different things. A field log has the
     * label of everything on the form; an open-question log has the label, the
     * control type, whether it was required and what the options were - which is
     * most of what decides how a field gets answered. Where both have a question,
     * the richer record wins.
     */
    List<FormField> questionCorpus() {
        Map<String, FormField> byLabel = new LinkedHashMap<>();

        for (ApplicationAttempt attempt : attempts.findAll()) {
            for (OpenQuestion question : OpenQuestion.parse(attempt.getOpenQuestions())) {
                if (usable(question.label())) {
                    byLabel.put(key(question.label()), field(question.label(),
                            control(question.control()), question.required(),
                            question.options()));
                }
            }
        }
        for (ApplicationAttempt attempt : attempts.findAll()) {
            for (FieldRow row : FieldRow.parse(attempt.getFieldLog())) {
                if (usable(row.label()) && !byLabel.containsKey(key(row.label()))) {
                    byLabel.put(key(row.label()), field(row.label(),
                            FormField.ControlType.TEXT, false, List.of()));
                }
            }
        }
        return List.copyOf(byLabel.values());
    }

    /**
     * A spread of real applications rather than a list of them.
     *
     * <p>Stratified on the four things that change an answer - country, work
     * mode, strategic lane, ATS - and capped per stratum so that the 137 American
     * postings do not drown out the one Australian. Sorted before sampling so the
     * dataset is the same on every run and two shadow reports can be diffed.
     */
    List<Posting> contextCorpus() {
        Map<String, List<Posting>> strata = new LinkedHashMap<>();
        postings.findByVerdict(Verdict.CANDIDATE).stream()
                .sorted(Comparator.comparing(Posting::getId))
                .forEach(posting -> strata
                        .computeIfAbsent(stratum(posting), key -> new ArrayList<>())
                        .add(posting));

        List<Posting> sample = new ArrayList<>();
        strata.values().forEach(group ->
                sample.addAll(group.stream().limit(MAX_PER_STRATUM).toList()));
        return List.copyOf(sample);
    }

    private static String stratum(Posting posting) {
        return posting.getCountryCode() + "|" + posting.getWorkMode() + "|"
                + posting.getStrategicClass() + "|" + posting.getSource();
    }

    /** How many distinct values of each context dimension the sample covers. */
    public Map<String, Integer> coverage(List<Posting> contexts) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("countries", distinct(contexts, p -> String.valueOf(p.getCountryCode())));
        counts.put("work modes", distinct(contexts, p -> String.valueOf(p.getWorkMode())));
        counts.put("strategic lanes",
                distinct(contexts, p -> String.valueOf(p.getStrategicClass())));
        counts.put("ATS platforms", distinct(contexts, p -> String.valueOf(p.getSource())));
        counts.put("companies", distinct(contexts, Posting::getBoardToken));
        return counts;
    }

    private static int distinct(List<Posting> postings,
            java.util.function.Function<Posting, String> key) {
        Set<String> values = new LinkedHashSet<>();
        postings.forEach(posting -> values.add(key.apply(posting)));
        return values.size();
    }

    // ------------------------------------------------------------------

    private FormField field(String label, FormField.ControlType control, boolean required,
            List<String> options) {
        FormField field = new FormField("#shadow", label.trim(), control,
                options == null ? List.of() : options, required, FieldKind.UNKNOWN);
        return field.withKind(classifier.classify(field));
    }

    private static FormField.ControlType control(String recorded) {
        try {
            return FormField.ControlType.valueOf(recorded);
        } catch (IllegalArgumentException | NullPointerException e) {
            return FormField.ControlType.TEXT;
        }
    }

    private static boolean usable(String label) {
        return label != null && !label.isBlank() && !"null".equals(label.trim());
    }

    private static String key(String label) {
        return FieldClassifier.normalise(label);
    }

    /**
     * A stand-in resume path.
     *
     * <p>The comparison never opens a file; it only compares the strings each
     * side would type. Both sides receive the same one, so a document field
     * agrees or disagrees on its own merits.
     */
    private static ApplicationDocuments documents() {
        return new ApplicationDocuments(Path.of("/tmp/shadow-resume.pdf"),
                "A drafted letter.", "shadow run");
    }
}
