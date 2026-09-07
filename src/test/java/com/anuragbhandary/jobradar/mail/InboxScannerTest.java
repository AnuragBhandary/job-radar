package com.anuragbhandary.jobradar.mail;

import static org.assertj.core.api.Assertions.assertThat;

import com.anuragbhandary.jobradar.sheets.SheetsClient.ExistingApplication;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class InboxScannerTest {

    private final ReplyClassifier classifier = new ReplyClassifier();

    private static ExistingApplication row(int number, String company, String status) {
        return new ExistingApplication(number, company, "Backend Engineer",
                LocalDate.of(2026, 8, 1), status, "https://example.com");
    }

    private static MailMessage mail(String sender, String subject, String body) {
        return new MailMessage("1", "t", sender, subject, body, Instant.EPOCH);
    }

    @Test
    void matchesARejectionToTheRightRow() {
        List<InboxScanner.Proposal> proposals = InboxScanner.proposals(
                List.of(mail("Celonis Recruiting <r@greenhouse.io>", "Update",
                        "We regret to inform you that we will not be moving forward.")),
                List.of(row(7, "Celonis", "Applied"), row(8, "N26", "Applied")),
                classifier);

        assertThat(proposals).hasSize(1);
        assertThat(proposals.getFirst().row().rowNumber()).isEqualTo(7);
        assertThat(proposals.getFirst().newStatus()).isEqualTo("Rejected");
    }

    @Test
    @DisplayName("acknowledgements produce no proposal at all")
    void acknowledgementsAreDropped() {
        // Every application produces one within a minute. Counting them as
        // replies clears the follow-up list and reports total success.
        assertThat(InboxScanner.proposals(
                List.of(mail("Celonis <r@greenhouse.io>", "Application received",
                        "Thank you for applying.")),
                List.of(row(7, "Celonis", "Applied")),
                classifier)).isEmpty();
    }

    @Test
    @DisplayName("the strongest reply in a thread wins, not the newest")
    void strongestOutcomeWins() {
        // "Thanks for coming in" routinely arrives after the rejection.
        List<InboxScanner.Proposal> proposals = InboxScanner.proposals(
                List.of(
                        mail("Celonis <r@greenhouse.io>", "Update",
                                "We regret to inform you we will not be moving forward."),
                        mail("Celonis <r@greenhouse.io>", "Thanks",
                                "It was good to speak with you about your application.")),
                List.of(row(7, "Celonis", "Applied")),
                classifier);

        assertThat(proposals).hasSize(1);
        assertThat(proposals.getFirst().newStatus()).isEqualTo("Rejected");
    }

    @Test
    @DisplayName("a row a human has already moved on is not downgraded")
    void doesNotContradictTheHuman() {
        // The sheet says Round 2; the only mail found is the original invitation.
        // The sheet knows more than the inbox does.
        assertThat(InboxScanner.proposals(
                List.of(mail("Celonis <r@greenhouse.io>", "Next steps",
                        "We would like to invite you to interview.")),
                List.of(row(7, "Celonis", "Round 2")),
                classifier)).isEmpty();
    }

    @Test
    @DisplayName("a rejection still lands on a row that is mid-process")
    void strongOutcomesStillApplyToInProcessRows() {
        List<InboxScanner.Proposal> proposals = InboxScanner.proposals(
                List.of(mail("Celonis <r@greenhouse.io>", "Update",
                        "We have decided not to proceed with your application.")),
                List.of(row(7, "Celonis", "Round 2")),
                classifier);

        assertThat(proposals).hasSize(1);
        assertThat(proposals.getFirst().newStatus()).isEqualTo("Rejected");
    }

    @Test
    @DisplayName("a closed row is never reopened")
    void closedStaysClosed() {
        assertThat(InboxScanner.proposals(
                List.of(mail("Celonis <r@greenhouse.io>", "Next steps",
                        "We would like to invite you to interview.")),
                List.of(row(7, "Celonis", "Rejected")),
                classifier)).isEmpty();
    }

    @Test
    void unmatchedMailIsIgnored() {
        assertThat(InboxScanner.proposals(
                List.of(mail("Amazon <ship@amazon.in>", "Delivered",
                        "We regret to inform you your parcel is late.")),
                List.of(row(7, "Celonis", "Applied")),
                classifier)).isEmpty();
    }
}
