package com.anuragbhandary.jobradar.mail;

import com.anuragbhandary.jobradar.followup.ApplicationStage;
import com.anuragbhandary.jobradar.sheets.SheetsClient;
import com.anuragbhandary.jobradar.sheets.SheetsClient.ExistingApplication;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Matches replies to tracker rows and proposes status changes.
 *
 * <p>Proposes. Nothing here writes to the sheet - {@link #proposals} is a pure
 * function of messages plus rows, and applying it is a separate, explicit call
 * from the command. That split is what makes the interesting question - "would
 * this have written the right thing?" - answerable in a test rather than by
 * letting it loose on the only record that cannot be rebuilt.
 */
@Service
public class InboxScanner {

    private final GmailClient gmail;
    private final SheetsClient sheets;
    private final ReplyClassifier classifier;

    public InboxScanner(GmailClient gmail, SheetsClient sheets, ReplyClassifier classifier) {
        this.gmail = gmail;
        this.sheets = sheets;
        this.classifier = classifier;
    }

    public boolean isConfigured() {
        return gmail.isConfigured() && sheets.isConfigured();
    }

    /**
     * @param evidence the message the decision was made from, so a wrong proposal
     *                 can be understood rather than merely reverted
     */
    public record Proposal(
            ExistingApplication row,
            ReplyKind kind,
            String newStatus,
            MailMessage evidence) {
    }

    public List<Proposal> scan(int days) throws IOException {
        return proposals(
                gmail.recent(days, 800),
                sheets.readExistingApplications(),
                classifier);
    }

    /**
     * One proposal per application, strongest outcome winning.
     *
     * <p>Three rules, each of which exists because of a way this goes wrong:
     *
     * <ul>
     *   <li><b>Acknowledgements are dropped.</b> Every application produces one
     *       within a minute, so counting them as replies clears the follow-up list
     *       entirely and reports total success.</li>
     *   <li><b>The strongest reply wins, not the newest.</b> A thread holds an
     *       acknowledgement, an invitation and a rejection, and "thanks for coming
     *       in" often arrives after the rejection.</li>
     *   <li><b>A row already past APPLIED is not downgraded.</b> If a human typed
     *       "Round 2" and the only mail found is the original acknowledgement, the
     *       sheet knows more than the inbox does.</li>
     * </ul>
     */
    static List<Proposal> proposals(
            List<MailMessage> messages,
            List<ExistingApplication> rows,
            ReplyClassifier classifier) {

        List<String> companies = rows.stream().map(ExistingApplication::company).toList();
        Map<Integer, Proposal> best = new LinkedHashMap<>();

        for (MailMessage message : messages) {
            ReplyKind kind = classifier.classify(message.subject(), message.snippet());
            if (!kind.changesStatus()) {
                continue;
            }
            Optional<String> company = CompanyMatcher.match(message, companies);
            if (company.isEmpty()) {
                continue;
            }

            for (ExistingApplication row : rows) {
                if (!row.company().equalsIgnoreCase(company.get())) {
                    continue;
                }
                ApplicationStage stage = ApplicationStage.of(row.status());
                // Never contradict a human who has already moved the row on, and
                // never reopen something closed.
                if (stage == ApplicationStage.CLOSED) {
                    continue;
                }
                // A row mid-process only moves for an ending: an offer or a
                // rejection. Anything weaker is the invitation that got it there.
                if (stage == ApplicationStage.IN_PROCESS
                        && kind.weight() <= ReplyKind.INTERVIEW.weight()) {
                    continue;
                }

                Proposal existing = best.get(row.rowNumber());
                if (existing == null || kind.weight() > existing.kind().weight()) {
                    best.put(row.rowNumber(),
                            new Proposal(row, kind, kind.trackerStatus(), message));
                }
            }
        }

        List<Proposal> proposals = new ArrayList<>(best.values());
        proposals.sort(Comparator.comparingInt((Proposal p) -> p.kind().weight()).reversed()
                .thenComparingInt(p -> p.row().rowNumber()));
        return List.copyOf(proposals);
    }

    /** Writes one proposal through. The only method here that changes anything. */
    public void apply(Proposal proposal) throws IOException {
        sheets.updateStatus(proposal.row().rowNumber(), proposal.newStatus());
    }
}
