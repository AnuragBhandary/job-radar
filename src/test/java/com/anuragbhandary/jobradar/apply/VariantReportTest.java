package com.anuragbhandary.jobradar.apply;

import static org.assertj.core.api.Assertions.assertThat;

import com.anuragbhandary.jobradar.sheets.SheetsClient.ExistingApplication;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class VariantReportTest {

    private static ApplicationAttempt sent(String company, String summaryId) {
        ApplicationAttempt attempt = new ApplicationAttempt(1L, company, "Backend Engineer");
        attempt.setStatus(AttemptStatus.SUBMITTED);
        attempt.setSummaryId(summaryId);
        return attempt;
    }

    private static ExistingApplication row(String company, String status) {
        return new ExistingApplication(7, company, "Backend Engineer",
                LocalDate.of(2026, 8, 1), status, "");
    }

    @Test
    void countsRepliesAndProgressPerVariant() {
        List<VariantReport.Variant> variants = VariantReport.compile(
                List.of(sent("Acme", "java"), sent("Beta", "java"), sent("Gamma", "python")),
                List.of(row("Acme", "Round 2"), row("Beta", "Rejected"),
                        row("Gamma", "Applied")));

        VariantReport.Variant java = variants.getFirst();
        assertThat(java.summaryId()).isEqualTo("java");
        assertThat(java.sent()).isEqualTo(2);
        assertThat(java.replied()).isEqualTo(2);
        assertThat(java.progressed()).isEqualTo(1);
        assertThat(variants.get(1).replied()).isZero();
    }

    @Test
    @DisplayName("only submitted attempts count")
    void preparedAttemptsAreNotApplications() {
        ApplicationAttempt prepared = new ApplicationAttempt(1L, "Acme", "Role");
        prepared.setSummaryId("java");
        prepared.setStatus(AttemptStatus.PREPARED);

        assertThat(VariantReport.compile(List.of(prepared), List.of())).isEmpty();
    }

    @Test
    @DisplayName("a company appearing twice keeps its best outcome")
    void bestOutcomeWinsPerCompany() {
        // Letting sheet row order decide would be arbitrary.
        List<VariantReport.Variant> variants = VariantReport.compile(
                List.of(sent("Acme", "java")),
                List.of(row("Acme", "Rejected"), row("Acme", "Round 2")));

        assertThat(variants.getFirst().progressed()).isEqualTo(1);
    }

    @Test
    @DisplayName("the report refuses to be meaningful on a small sample")
    void smallSamplesAreNotConclusions() {
        // 2-from-5 against 1-from-6 looks like a large improvement and is three
        // coin flips.
        List<VariantReport.Variant> small = VariantReport.compile(
                List.of(sent("Acme", "java"), sent("Beta", "python")),
                List.of(row("Acme", "Round 2")));

        assertThat(VariantReport.isMeaningful(small)).isFalse();
        assertThat(VariantReport.isMeaningful(List.of())).isFalse();
        assertThat(VariantReport.isMeaningful(List.of(
                new VariantReport.Variant("java", 60, 10, 4),
                new VariantReport.Variant("python", 55, 8, 3)))).isTrue();
        assertThat(VariantReport.isMeaningful(List.of(
                new VariantReport.Variant("java", 60, 10, 4),
                new VariantReport.Variant("python", 3, 2, 1)))).isFalse();
    }

    @Test
    void matchesCompanyNamesLoosely() {
        assertThat(VariantReport.compile(
                List.of(sent("Acme", "java")),
                List.of(row("  acme  ", "Round 2"))).getFirst().progressed())
                .isEqualTo(1);
    }

    @Test
    void handlesNoTrackerAtAll() {
        List<VariantReport.Variant> variants = VariantReport.compile(
                List.of(sent("Acme", "java")), List.of());

        assertThat(variants.getFirst().sent()).isEqualTo(1);
        assertThat(variants.getFirst().replied()).isZero();
    }
}
