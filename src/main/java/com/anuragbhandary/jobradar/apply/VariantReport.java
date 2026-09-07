package com.anuragbhandary.jobradar.apply;

import com.anuragbhandary.jobradar.followup.ApplicationStage;
import com.anuragbhandary.jobradar.sheets.SheetsClient.ExistingApplication;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * How each resume variant has actually done.
 *
 * <p>Pure: attempts plus tracker rows in, counts out. Joined on company name,
 * which is loose - two applications to the same company cannot be told apart -
 * and is the only join available, because the sheet has no attempt id in it.
 * That looseness is stated in the output rather than hidden.
 *
 * <h2>Read this with the sample size in mind</h2>
 * The report prints its own confidence, and below about fifty applications per
 * variant the honest answer is that it shows nothing. A callback rate of 2/5
 * against 1/6 looks like a result and is three coin flips. The tool says so
 * rather than leaving a suggestive table on screen - a resume rewritten on
 * eleven data points is worse than one left alone.
 */
public final class VariantReport {

    /** Below this per variant, differences are not worth acting on. */
    public static final int MEANINGFUL_SAMPLE = 50;

    private VariantReport() {
    }

    /**
     * @param sent       applications submitted with this variant
     * @param replied    anything past APPLIED: in process or closed with an outcome
     * @param progressed reached an interview or assessment stage
     */
    public record Variant(String summaryId, int sent, int replied, int progressed) {

        public double replyRate() {
            return sent == 0 ? 0 : (double) replied / sent;
        }

        public double progressRate() {
            return sent == 0 ? 0 : (double) progressed / sent;
        }
    }

    public static List<Variant> compile(
            List<ApplicationAttempt> attempts, List<ExistingApplication> rows) {

        Map<String, ApplicationStage> stageByCompany = new LinkedHashMap<>();
        for (ExistingApplication row : rows) {
            ApplicationStage stage = ApplicationStage.of(row.status());
            // Best outcome wins where a company appears twice - the alternative is
            // letting row order decide, which is arbitrary.
            stageByCompany.merge(key(row.company()), stage,
                    (a, b) -> rank(a) >= rank(b) ? a : b);
        }

        Map<String, int[]> tally = new LinkedHashMap<>();
        for (ApplicationAttempt attempt : attempts) {
            if (attempt.getStatus() != AttemptStatus.SUBMITTED
                    || attempt.getSummaryId() == null) {
                continue;
            }
            int[] counts = tally.computeIfAbsent(attempt.getSummaryId(), k -> new int[3]);
            counts[0]++;

            ApplicationStage stage = stageByCompany.get(key(attempt.getCompany()));
            if (stage == null || stage == ApplicationStage.APPLIED) {
                continue;
            }
            counts[1]++;
            if (stage == ApplicationStage.IN_PROCESS) {
                counts[2]++;
            }
        }

        List<Variant> variants = new ArrayList<>();
        tally.forEach((id, counts) ->
                variants.add(new Variant(id, counts[0], counts[1], counts[2])));
        variants.sort(Comparator.comparingInt(Variant::sent).reversed());
        return List.copyOf(variants);
    }

    /** True once every variant has enough behind it to mean anything. */
    public static boolean isMeaningful(List<Variant> variants) {
        return !variants.isEmpty()
                && variants.stream().allMatch(v -> v.sent() >= MEANINGFUL_SAMPLE);
    }

    private static int rank(ApplicationStage stage) {
        return switch (stage) {
            case IN_PROCESS -> 3;
            case CLOSED -> 2;
            case APPLIED -> 1;
            case UNKNOWN -> 0;
        };
    }

    private static String key(String company) {
        return company == null ? "" : company.trim().toLowerCase(Locale.ROOT);
    }
}
