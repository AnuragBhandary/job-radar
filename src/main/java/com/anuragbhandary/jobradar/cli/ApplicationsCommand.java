package com.anuragbhandary.jobradar.cli;

import com.anuragbhandary.jobradar.apply.ApplicationAttempt;
import com.anuragbhandary.jobradar.apply.ApplicationAttemptRepository;
import com.anuragbhandary.jobradar.apply.AttemptStatus;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * {@code applications} - what has been prepared, sent or blocked.
 *
 * <p>The blocked ones are the useful output. Each one names a question the form
 * asked and the profile could not answer, which is the list of edits that make
 * the next run go further.
 */
@Component
public class ApplicationsCommand {

    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("dd MMM HH:mm").withZone(ZoneId.systemDefault());

    private final ApplicationAttemptRepository attempts;

    public ApplicationsCommand(ApplicationAttemptRepository attempts) {
        this.attempts = attempts;
    }

    public void run(Map<String, String> options) {
        String wanted = options.get("status");
        List<ApplicationAttempt> rows = wanted == null
                ? attempts.findTop30ByOrderByStartedAtDesc()
                : attempts.findByStatusOrderByStartedAtDesc(
                        AttemptStatus.valueOf(wanted.toUpperCase(java.util.Locale.ROOT)));

        if (rows.isEmpty()) {
            System.out.println("No applications yet.");
            return;
        }

        System.out.printf("%n%-5s %-13s %-24s %-38s %s%n",
                "id", "status", "company", "role", "when");
        System.out.println("─".repeat(100));
        for (ApplicationAttempt row : rows) {
            System.out.printf("%-5d %-13s %-24s %-38s %s%n",
                    row.getId(), row.getStatus(),
                    truncate(row.getCompany(), 24), truncate(row.getRole(), 38),
                    WHEN.format(row.getStartedAt()));
            if (row.getBlockerReason() != null) {
                System.out.println("      ↳ " + row.getBlockerReason());
            }
        }

        long blocked = rows.stream()
                .filter(r -> r.getStatus() == AttemptStatus.NEEDS_HUMAN).count();
        if (blocked > 0) {
            System.out.printf("%n%d blocked. Each ↳ line is a question to add to "
                    + "applicant.yml under extra-answers.%n", blocked);
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }
}
